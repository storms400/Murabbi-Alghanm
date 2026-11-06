package dz.murabbi.alghanm

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.database.sqlite.SQLiteDatabase
import dz.murabbi.alghanm.data.FarmDao
import dz.murabbi.alghanm.data.FarmDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Password-encrypted portable backup. Contains the complete SQLite database
 * and byte copies of every animal photo reachable through its saved URI.
 * The password is never persisted by this component.
 */
object MawashiBackup {
    private val magic = "MWB11".toByteArray(Charsets.US_ASCII)
    private const val MAX_BACKUP_BYTES = 150 * 1024 * 1024
    private const val DB_NAME = "mawashi_pro_v11_standalone20261106.db"

    suspend fun exportEncrypted(context: Context, dao: FarmDao, destination: Uri, password: String) =
        withContext(Dispatchers.IO) {
            require(password.length >= 12) { "استخدم كلمة مرور من 12 محرفاً على الأقل." }
            val animals = dao.allAnimalsOnce()
            val db = FarmDatabase.get(context)
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { }
            FarmDatabase.closeInstance()
            val archive = File(context.cacheDir, "mawashi-backup-${System.currentTimeMillis()}.zip")
            try {
                val databaseFile = context.getDatabasePath(DB_NAME)
                check(databaseFile.isFile) { "ملف قاعدة البيانات غير موجود." }
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("farm.db"))
                    databaseFile.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    animals.filter { it.photoUri.isNotBlank() }.forEach { animal ->
                        val uri = Uri.parse(animal.photoUri)
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("تعذرت قراءة صورة الحيوان ${animal.tag}")
                        val safeTag = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(animal.tag.toByteArray(Charsets.UTF_8))
                        zip.putNextEntry(ZipEntry("photos/$safeTag.img"))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                val plain = archive.readBytes()
                check(plain.size <= MAX_BACKUP_BYTES) { "حجم النسخة الاحتياطية تجاوز الحد المسموح." }
                val encrypted = encrypt(plain, password)
                context.contentResolver.openOutputStream(destination, "w")?.use { it.write(encrypted) }
                    ?: throw IllegalStateException("تعذر إنشاء ملف النسخة الاحتياطية.")
            } finally {
                archive.delete()
                FarmDatabase.get(context)
            }
        }

    suspend fun restoreEncrypted(context: Context, source: Uri, password: String) = withContext(Dispatchers.IO) {
        require(password.length >= 12) { "كلمة المرور غير صالحة." }
        val encrypted = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw IllegalStateException("تعذرت قراءة ملف النسخة الاحتياطية.")
        require(encrypted.size in (magic.size + 16 + 12 + 16)..MAX_BACKUP_BYTES) { "حجم ملف النسخة غير صالح." }
        val plain = decrypt(encrypted, password)
        require(plain.size <= MAX_BACKUP_BYTES) { "حجم البيانات بعد فك التشفير غير مسموح." }

        val work = File(context.cacheDir, "mawashi-restore-${System.currentTimeMillis()}").apply { mkdirs() }
        val stagedDb = File(work, "farm.db")
        val photoDir = File(work, "photos").apply { mkdirs() }
        val currentDb = context.getDatabasePath(DB_NAME)
        val rollbackDb = File(work, "previous.db")
        try {
            ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.name.startsWith("/") && !entry.name.contains("..") && !entry.name.contains("\\")) {
                        "ملف النسخة يحتوي مساراً غير آمن."
                    }
                    val out = when {
                        entry.name == "farm.db" -> stagedDb
                        entry.name.startsWith("photos/") && entry.name.endsWith(".img") ->
                            File(photoDir, entry.name.removePrefix("photos/"))
                        else -> null
                    }
                    if (out != null) {
                        out.outputStream().buffered().use { target ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BACKUP_BYTES) { "حجم محتويات النسخة أكبر من الحد." }
                                target.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(stagedDb.isFile && stagedDb.length() > 0) { "النسخة لا تحتوي قاعدة بيانات." }
            val checkDb = SQLiteDatabase.openDatabase(stagedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "قاعدة البيانات داخل النسخة تالفة." }
                }
            } finally { checkDb.close() }

            FarmDatabase.get(context).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { }
            FarmDatabase.closeInstance()
            currentDb.parentFile?.mkdirs()
            if (currentDb.exists()) currentDb.copyTo(rollbackDb, overwrite = true)
            File(currentDb.absolutePath + "-wal").delete()
            File(currentDb.absolutePath + "-shm").delete()
            try {
                stagedDb.copyTo(currentDb, overwrite = true)
                val restored = FarmDatabase.get(context).farmDao()
                val animals = restored.allAnimalsOnce()
                animals.forEach { animal ->
                    val safeTag = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(animal.tag.toByteArray(Charsets.UTF_8))
                    val photo = File(photoDir, "$safeTag.img")
                    if (photo.isFile) {
                        val photoUri = publishPhoto(context, animal.tag, photo)
                        if (photoUri != null) restored.updateAnimalPhoto(animal.tag, photoUri.toString())
                        else restored.updateAnimalPhoto(animal.tag, "")
                    } else if (animal.photoUri.isNotBlank()) {
                        restored.updateAnimalPhoto(animal.tag, "")
                    }
                }
            } catch (failure: Exception) {
                FarmDatabase.closeInstance()
                if (rollbackDb.isFile) rollbackDb.copyTo(currentDb, overwrite = true)
                FarmDatabase.get(context)
                throw failure
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun publishPhoto(context: Context, tag: String, file: File): Uri? {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Mawashi-$tag.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mawashi")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: return@runCatching null
            output.use { stream -> file.inputStream().buffered().use { it.copyTo(stream) } }
            uri
        }.getOrNull()
    }

    private fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plain)
        return magic + salt + iv + ciphertext
    }

    private fun decrypt(input: ByteArray, password: String): ByteArray {
        require(input.size > magic.size + 16 + 12 && input.copyOfRange(0, magic.size).contentEquals(magic)) {
            "صيغة ملف النسخة غير معروفة."
        }
        val salt = input.copyOfRange(magic.size, magic.size + 16)
        val iv = input.copyOfRange(magic.size + 16, magic.size + 28)
        val ciphertext = input.copyOfRange(magic.size + 28, input.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 250_000, 256)
        val bytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
        return SecretKeySpec(bytes, "AES")
    }
}
