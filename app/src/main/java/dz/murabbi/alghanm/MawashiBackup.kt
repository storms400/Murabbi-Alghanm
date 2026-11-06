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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streaming, password-encrypted V12 backup. No database, photo, archive or
 * ciphertext is loaded wholesale into memory. The DB is staged and validated
 * before the live database is replaced.
 */
object MawashiBackup {
    private val magic = "MWB12".toByteArray(Charsets.US_ASCII)
    private const val MAX_BACKUP_BYTES = 150L * 1024L * 1024L
    private const val DB_NAME = "mawashi_pro_v12_standalone20261106.db"
    private const val ROOM_VERSION = 9
    private val requiredTables = setOf(
        "animals", "feed_purchases", "feed_consumptions", "farm_tasks",
        "herd_events", "vaccine_dose_records", "vaccine_campaigns",
        "animal_care_status", "treatment_records", "reproduction_records",
        "medicine_stock", "audit_entries", "app_users", "finance_entries"
    )

    suspend fun exportEncrypted(context: Context, dao: FarmDao, destination: Uri, password: String) =
        withContext(Dispatchers.IO) {
            require(password.length >= 12) { "استخدم كلمة مرور من 12 محرفاً على الأقل." }
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
                    val salt = ByteArray(16).also(SecureRandom()::nextBytes)
                    val iv = ByteArray(12).also(SecureRandom()::nextBytes)
                    raw.write(magic)
                    raw.write(salt)
                    raw.write(iv)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
                    CipherOutputStream(BufferedOutputStream(raw), cipher).use { encrypted ->
                        ZipOutputStream(encrypted).use { zip ->
                            zip.putNextEntry(ZipEntry("farm.db"))
                            databaseFile.inputStream().buffered().use { it.copyBoundedTo(zip, MAX_BACKUP_BYTES) }
                            zip.closeEntry()
                            dao.allAnimalsOnce().filter { it.photoUri.isNotBlank() }.forEach { animal ->
                                val uri = Uri.parse(animal.photoUri)
                                val mime = context.contentResolver.getType(uri)
                                    ?: error("تعذر تحديد صيغة صورة الحيوان ${animal.tag}.")
                                val extension = extensionForMime(mime)
                                    ?: error("صيغة صورة غير مدعومة للحيوان ${animal.tag}: $mime")
                                zip.putNextEntry(ZipEntry("photos/${safeTag(animal.tag)}.$extension"))
                                context.contentResolver.openInputStream(uri)?.use {
                                    it.copyBoundedTo(zip, MAX_BACKUP_BYTES)
                                } ?: error("تعذرت قراءة صورة الحيوان ${animal.tag}.")
                                zip.closeEntry()
                            }
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
            val work = File(context.cacheDir, "mawashi-v12-restore-${System.currentTimeMillis()}").apply { mkdirs() }
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
                    val header = ByteArray(magic.size + 16 + 12)
                    input.readFully(header)
                    require(header.copyOfRange(0, magic.size).contentEquals(magic)) {
                        "صيغة النسخة غير معروفة أو ليست نسخة مواشي V12."
                    }
                    val salt = header.copyOfRange(magic.size, magic.size + 16)
                    val iv = header.copyOfRange(magic.size + 16, header.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
                    val bounded = CountingInputStream(input, MAX_BACKUP_BYTES)
                    CipherInputStream(bounded, cipher).use { decrypted ->
                        plainZip.outputStream().buffered().use { out ->
                            decrypted.copyBoundedTo(out, MAX_BACKUP_BYTES)
                        }
                    }
                }

                var databaseSeen = false
                var totalUncompressed = 0L
                ZipInputStream(plainZip.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(isSafeEntryName(entry.name)) { "ملف النسخة يحتوي مساراً غير آمن." }
                        val output = when {
                            entry.name == "farm.db" -> {
                                require(!databaseSeen) { "النسخة تحتوي قاعدة بيانات مكررة." }
                                databaseSeen = true
                                stagedDb
                            }
                            entry.name.startsWith("photos/") && isSupportedPhotoEntry(entry.name) -> {
                                val target = File(photoDir, entry.name.removePrefix("photos/"))
                                require(target.parentFile == photoDir) { "مسار صورة غير آمن." }
                                target
                            }
                            else -> null
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

                val live = FarmDatabase.get(context)
                live.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { }
                FarmDatabase.closeInstance()
                currentDb.parentFile?.mkdirs()
                if (currentDb.exists()) currentDb.copyTo(rollbackDb, overwrite = true)
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
                        if (photo != null) {
                            val extension = photo.extension.lowercase()
                            val mime = mimeForExtension(extension)
                                ?: error("صيغة صورة مستعادة غير مدعومة: $extension")
                            val photoUri = publishPhoto(context, animal.tag, photo, mime, extension)
                            if (photoUri != null) {
                                createdMedia.add(photoUri)
                                restoredDao.updateAnimalPhoto(animal.tag, photoUri.toString())
                            } else {
                                restoredDao.updateAnimalPhoto(animal.tag, "")
                            }
                        } else if (animal.photoUri.isNotBlank()) {
                            restoredDao.updateAnimalPhoto(animal.tag, "")
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

    private fun validateDatabase(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                require(c.moveToFirst() && c.getString(0) == "ok") { "قاعدة البيانات داخل النسخة تالفة." }
            }
            db.rawQuery("PRAGMA user_version", null).use { c ->
                require(c.moveToFirst() && c.getInt(0) == ROOM_VERSION) {
                    "إصدار قاعدة البيانات غير متوافق مع مواشي V12."
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

    private fun publishPhoto(context: Context, tag: String, file: File, mime: String, extension: String): Uri? =
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Mawashi-$tag.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mawashi")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    file.inputStream().buffered().use { it.copyTo(out) }
                } ?: error("تعذر كتابة الصورة المستعادة.")
                uri
            } catch (failure: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }.getOrNull()

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

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 250_000, 256)
        val bytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
        return SecretKeySpec(bytes, "AES")
    }

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

    internal fun isSafeEntryName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("/") && !name.contains("..") &&
            !name.contains('\\') && !name.contains('\u0000')

    internal fun isSupportedPhotoEntry(name: String): Boolean {
        if (!isSafeEntryName(name) || !name.startsWith("photos/")) return false
        val leaf = name.removePrefix("photos/")
        if (leaf.isBlank() || leaf.contains('/')) return false
        return leaf.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")
    }
}
