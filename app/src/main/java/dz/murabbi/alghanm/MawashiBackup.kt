package dz.murabbi.alghanm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dz.murabbi.alghanm.data.FarmDao
import dz.murabbi.alghanm.data.FarmDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streaming, password-encrypted V17 backup. No database, photo, archive or
 * ciphertext is loaded wholesale into memory. The DB is staged and validated
 * before the live database is replaced.
 */
object MawashiBackup {
    private val magic = BackupCrypto.MAGIC
    private const val MAX_BACKUP_BYTES = 150L * 1024L * 1024L
    private const val DB_NAME = "mawashi_pro_v18_standalone20261107.db"
    private const val ROOM_VERSION = 10
    private val requiredTables = setOf(
        "animals", "feed_purchases", "feed_consumptions", "farm_tasks",
        "herd_events", "vaccine_dose_records", "vaccine_campaigns",
        "animal_care_status", "treatment_records", "reproduction_records",
        "medicine_stock", "audit_entries", "app_users", "finance_entries"
    )

    suspend fun exportEncrypted(context: Context, dao: FarmDao, destination: Uri, password: String) =
        withContext(Dispatchers.IO) {
            require(password.length >= 12) { "استخدم كلمة مرور من 12 محرفاً على الأقل." }
            val animals = dao.allAnimalsOnce()
            val db = FarmDatabase.get(context)
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { }
            FarmDatabase.closeInstance()
            val databaseFile = context.getDatabasePath(DB_NAME)
            try {
                check(databaseFile.isFile) { "ملف قاعدة البيانات غير موجود." }
                validateDatabase(databaseFile)
                val output = context.contentResolver.openOutputStream(destination, "w")
                    ?: error("تعذر إنشاء ملف النسخة الاحتياطية.")
                output.use { raw ->
                    val encrypted = BackupCrypto.encryptingStream(raw, password)
                    ZipOutputStream(encrypted).use { zip ->
                            var totalPayload = 0L
                            zip.putNextEntry(ZipEntry("farm.db"))
                            databaseFile.inputStream().buffered().use { totalPayload += it.copyBoundedTo(zip, MAX_BACKUP_BYTES - totalPayload) }
                            zip.closeEntry()
                            animals.filter { it.photoUri.isNotBlank() }.forEach { animal ->
                                val uri = Uri.parse(animal.photoUri)
                                val mime = context.contentResolver.getType(uri)
                                    ?: error("تعذر تحديد صيغة صورة الحيوان ${animal.tag}.")
                                val extension = extensionForMime(mime)
                                    ?: error("صيغة صورة غير مدعومة للحيوان ${animal.tag}: $mime")
                                zip.putNextEntry(ZipEntry("photos/${safeTag(animal.tag)}.$extension"))
                                context.contentResolver.openInputStream(uri)?.use {
                                    totalPayload += it.copyBoundedTo(zip, MAX_BACKUP_BYTES - totalPayload)
                                } ?: error("تعذرت قراءة صورة الحيوان ${animal.tag}.")
                                zip.closeEntry()
                            }
                        }
                    }
            } finally {
                FarmDatabase.get(context)
            }
        }

    suspend fun restoreEncrypted(context: Context, source: Uri, password: String) =
        withContext(Dispatchers.IO) {
            require(password.length >= 12) { "كلمة المرور غير صالحة." }
            val work = File(context.cacheDir, "mawashi-v18-restore-${System.currentTimeMillis()}").apply { mkdirs() }
            val plainZip = File(work, "decrypted.zip")
            val stagedDb = File(work, "farm.db")
            val photoDir = File(work, "photos").apply { mkdirs() }
            val currentDb = context.getDatabasePath(DB_NAME)
            val rollbackDb = File(work, "previous.db")
            val createdMedia = mutableListOf<Uri>()
            try {
                val raw = context.contentResolver.openInputStream(source)
                    ?: error("تعذرت قراءة ملف النسخة الاحتياطية.")
                raw.use { input ->
                    plainZip.outputStream().buffered().use { staged ->
                        BackupCrypto.decryptTo(input, staged, password, MAX_BACKUP_BYTES, MAX_BACKUP_BYTES)
                    }
                }

                var databaseSeen = false
                val seenEntries = mutableSetOf<String>()
                var totalUncompressed = 0L
                ZipInputStream(plainZip.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(isSafeEntryName(entry.name)) { "ملف النسخة يحتوي مساراً غير آمن." }
                        require(seenEntries.add(entry.name)) { "النسخة تحتوي عنصراً مكرراً: ${entry.name}" }
                        val output = when {
                            entry.name == "farm.db" -> {
                                require(!databaseSeen) { "النسخة تحتوي قاعدة بيانات مكررة." }
                                databaseSeen = true
                                stagedDb
                            }
                            BackupArchivePolicy.safePhotoEntry(entry.name) -> {
                                val target = File(photoDir, entry.name.removePrefix("photos/"))
                                require(target.parentFile == photoDir) { "مسار صورة غير آمن." }
                                target
                            }
                            else -> error("عنصر غير معروف داخل النسخة الاحتياطية: ${entry.name}")
                        }
                        if (output != null) {
                            output.outputStream().buffered().use { target ->
                                totalUncompressed += zip.copyBoundedTo(target, MAX_BACKUP_BYTES - totalUncompressed)
                            }
                        }
                        zip.closeEntry()
                    }
                }
                require(databaseSeen && stagedDb.isFile && stagedDb.length() > 0) {
                    "النسخة لا تحتوي قاعدة بيانات صالحة."
                }
                validateDatabase(stagedDb)
                val expectedPhotoTags = readStoredPhotoTags(stagedDb)
                val archivedPhotoNames = photoDir.listFiles()?.map { it.name }.orEmpty()
                require(BackupArchivePolicy.photoEntriesMatchAnimals(expectedPhotoTags, archivedPhotoNames)) {
                    "صور النسخة لا تطابق صور الحيوانات المسجلة في قاعدة البيانات؛ أُلغيت الاستعادة قبل استبدال البيانات."
                }

                val live = FarmDatabase.get(context)
                live.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { }
                FarmDatabase.closeInstance()
                currentDb.parentFile?.mkdirs()
                if (currentDb.exists()) currentDb.copyTo(rollbackDb, overwrite = true)
                require(!currentDb.exists() || rollbackDb.isFile) { "تعذر إنشاء نسخة التراجع؛ أُلغيت الاستعادة لحماية البيانات الحالية." }
                File(currentDb.absolutePath + "-wal").delete()
                File(currentDb.absolutePath + "-shm").delete()
                try {
                    stagedDb.copyTo(currentDb, overwrite = true)
                    val restoredDao = FarmDatabase.get(context).farmDao()
                    restoredDao.allAnimalsOnce().forEach { animal ->
                        val prefix = safeTag(animal.tag) + "."
                        val photo = photoDir.listFiles()?.firstOrNull {
                            it.name.startsWith(prefix) && isSupportedPhotoEntry("photos/" + it.name)
                        }
                        require(BackupArchivePolicy.photoArchiveMatchesDatabase(
                            hasStoredPhoto = animal.photoUri.isNotBlank(),
                            hasArchivedPhoto = photo != null
                        )) {
                            "الصورة المرتبطة بالحيوان ${animal.tag} غير موجودة في النسخة؛ أُلغيت الاستعادة لحماية البيانات."
                        }
                        if (photo != null) {
                            val extension = photo.extension.lowercase()
                            val mime = mimeForExtension(extension)
                                ?: error("صيغة صورة مستعادة غير مدعومة: $extension")
                            val photoUri = publishPhoto(context, animal.tag, photo, mime, extension) {
                                createdMedia.add(it)
                            }
                            restoredDao.updateAnimalPhoto(animal.tag, photoUri.toString())
                        }
                    }
                    FarmDatabase.get(context).openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
                        check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                            "فشل فحص قاعدة البيانات بعد الاستعادة."
                        }
                    }
                } catch (failure: Exception) {
                    FarmDatabase.closeInstance()
                    currentDb.delete()
                    File(currentDb.absolutePath + "-wal").delete()
                    File(currentDb.absolutePath + "-shm").delete()
                    if (rollbackDb.isFile) rollbackDb.copyTo(currentDb, overwrite = true)
                    FarmDatabase.get(context)
                    createdMedia.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    throw failure
                }
            } finally {
                work.deleteRecursively()
            }
        }

    private fun readStoredPhotoTags(file: File): Set<String> {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tags = mutableSetOf<String>()
            db.rawQuery("SELECT tag FROM animals WHERE photoUri IS NOT NULL AND photoUri != ''", null).use { cursor ->
                while (cursor.moveToNext()) tags.add(safeTag(cursor.getString(0)))
            }
            return tags
        } finally { db.close() }
    }

    private fun validateDatabase(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                require(c.moveToFirst() && c.getString(0) == "ok") { "قاعدة البيانات داخل النسخة تالفة." }
            }
            db.rawQuery("PRAGMA user_version", null).use { c ->
                require(c.moveToFirst() && c.getInt(0) == ROOM_VERSION) {
                    "إصدار قاعدة البيانات غير متوافق مع مواشي V18."
                }
            }
            val tables = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0))
            }
            require(tables.containsAll(requiredTables)) {
                "قاعدة البيانات ناقصة الجداول المطلوبة؛ لم يتم استبدال بياناتك."
            }
        } finally {
            db.close()
        }
    }

    private fun publishPhoto(
        context: Context,
        tag: String,
        file: File,
        mime: String,
        extension: String,
        onCreated: (Uri) -> Unit
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Mawashi-$tag.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mawashi")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("تعذر إنشاء ملف الصورة المستعادة للحيوان $tag.")
        // Track the MediaStore row before writing so the outer rollback can always clean it up.
        onCreated(uri)
        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            file.inputStream().buffered().use { it.copyTo(out) }
        } ?: error("تعذر فتح ملف الصورة المستعادة للحيوان $tag.")
        return uri
    }

    private fun safeTag(tag: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(tag.toByteArray(Charsets.UTF_8))

    private fun extensionForMime(mime: String): String? =
        when (MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif" ->
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()
            else -> null
        }

    private fun mimeForExtension(extension: String): String? =
        if (extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif"))
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) else null

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            if (count < 0) throw IllegalArgumentException("ملف النسخة قصير أو تالف.")
            offset += count
        }
    }

    private fun InputStream.copyBoundedTo(output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192)
        var copied = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= limit) { "حجم النسخة تجاوز الحد المسموح (150 ميغابايت)." }
            output.write(buffer, 0, count)
        }
        return copied
    }

    private class CountingInputStream(input: InputStream, private val limit: Long) :
        java.io.FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++count > limit) throw IllegalArgumentException("ملف النسخة أكبر من الحد المسموح.")
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count += read
                require(count <= limit) { "ملف النسخة أكبر من الحد المسموح." }
            }
            return read
        }
    }

    internal fun isSafeEntryName(name: String): Boolean = BackupArchivePolicy.safeEntryName(name)

    internal fun isSupportedPhotoEntry(name: String): Boolean {
        if (!isSafeEntryName(name) || !name.startsWith("photos/")) return false
        val leaf = name.removePrefix("photos/")
        if (leaf.isBlank() || leaf.contains('/')) return false
        return BackupArchivePolicy.supportedPhotoExtension(leaf.substringAfterLast('.', ""))
    }
}
