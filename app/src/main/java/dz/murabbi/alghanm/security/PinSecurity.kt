package dz.murabbi.alghanm.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local PIN/recovery secret hashing. Format v2 uses PBKDF2-HMAC-SHA256,
 * 210k iterations, random 128-bit salt and 256-bit derived key.
 * v1 remains readable for databases upgraded from V10.
 */
object PinSecurity {
    private const val ITERATIONS_V2 = 210_000
    private const val ITERATIONS_V1 = 120_000
    private const val KEY_BITS = 256

    fun hash(secret: String): String {
        require(secret.isNotEmpty()) { "Secret must not be empty" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val spec = PBEKeySpec(secret.toCharArray(), salt, ITERATIONS_V2, KEY_BITS)
        val derived = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return "v2:${Base64.getEncoder().withoutPadding().encodeToString(salt)}:${Base64.getEncoder().withoutPadding().encodeToString(derived)}"
    }

    fun verify(secret: String, encoded: String): Boolean {
        return try {
            val parts = encoded.split(':')
            if (parts.size != 3) return false
            val iterations = when (parts[0]) {
                "v1" -> ITERATIONS_V1
                "v2" -> ITERATIONS_V2
                else -> return false
            }
            val salt = Base64.getDecoder().decode(parts[1])
            val expected = Base64.getDecoder().decode(parts[2])
            if (salt.size != 16 || expected.size != KEY_BITS / 8) return false
            val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_BITS)
            val actual = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        }
    }

    fun newRecoveryCode(): String =
        ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02X".format(it) }
}
