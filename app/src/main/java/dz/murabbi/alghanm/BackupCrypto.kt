package dz.murabbi.alghanm

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Streaming implementation of the MWB12 AES-GCM envelope; Android-independent for JVM tests. */
internal object BackupCrypto {
    val MAGIC: ByteArray = "MWB12".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val ITERATIONS = 250_000

    fun encryptingStream(destination: OutputStream, password: String): OutputStream {
        require(password.length >= 12)
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        destination.write(MAGIC); destination.write(salt); destination.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        return CipherOutputStream(BufferedOutputStream(destination), cipher)
    }

    /** Writes only to a staging destination; callers publish it only after this method returns. */
    fun decryptTo(source: InputStream, destination: OutputStream, password: String,
                  maxCipherBytes: Long, maxPlainBytes: Long) {
        require(password.length >= 12 && maxCipherBytes >= 0 && maxPlainBytes >= 0)
        val header = ByteArray(MAGIC.size + SALT_BYTES + IV_BYTES)
        var offset = 0
        while (offset < header.size) {
            val n = source.read(header, offset, header.size - offset)
            if (n < 0) throw IllegalArgumentException("ملف النسخة قصير أو تالف.")
            offset += n
        }
        require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "صيغة النسخة الاحتياطية غير معروفة." }
        val salt = header.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = header.copyOfRange(MAGIC.size + SALT_BYTES, header.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val bounded = object : java.io.FilterInputStream(source) {
            var consumed = 0L
            override fun read(): Int {
                val value = super.read()
                if (value >= 0) { consumed++; require(consumed <= maxCipherBytes) }
                return value
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) { consumed += n; require(consumed <= maxCipherBytes) }
                return n
            }
        }
        BufferedInputStream(bounded).use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                val plain = cipher.update(buffer, 0, n)
                if (plain != null && plain.isNotEmpty()) {
                    total += plain.size; require(total <= maxPlainBytes)
                    destination.write(plain)
                }
            }
            val tail = cipher.doFinal() // GCM tag verification; rejects wrong passwords and tampering.
            if (tail.isNotEmpty()) {
                total += tail.size; require(total <= maxPlainBytes)
                destination.write(tail)
            }
            destination.flush()
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
                  finally { spec.clearPassword() }
        return SecretKeySpec(key, "AES")
    }
}
