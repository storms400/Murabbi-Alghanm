package dz.murabbi.alghanm.security

import org.junit.Assert.*
import org.junit.Test

class PinSecurityTest {
    @Test fun correctSecretVerifiesAndWrongSecretFails() {
        val encoded = PinSecurity.hash("582941")
        assertTrue(PinSecurity.verify("582941", encoded))
        assertFalse(PinSecurity.verify("582942", encoded))
    }

    @Test fun eachHashUsesAnIndependentSalt() {
        val first = PinSecurity.hash("582941")
        val second = PinSecurity.hash("582941")
        assertNotEquals(first, second)
        assertTrue(PinSecurity.verify("582941", first))
        assertTrue(PinSecurity.verify("582941", second))
    }

    @Test fun v10PinHashFormatRemainsCompatibleAfterUpgrade() {
        val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val spec = javax.crypto.spec.PBEKeySpec("4826".toCharArray(), salt, 120000, 256)
        val hash = try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
        val encoded = "v1:${java.util.Base64.getEncoder().encodeToString(salt)}:${java.util.Base64.getEncoder().encodeToString(hash)}"
        assertTrue(PinSecurity.verify("4826", encoded))
        assertFalse(PinSecurity.verify("4827", encoded))
    }

    @Test fun malformedAndUnknownHashFormatsFailClosed() {
        assertFalse(PinSecurity.verify("1234", ""))
        assertFalse(PinSecurity.verify("1234", "v9:bad:bad"))
        assertFalse(PinSecurity.verify("1234", "v2:bad"))
    }

    @Test fun recoveryCodeIsRandomAndStrongEnoughForOneTimeRecovery() {
        val first = PinSecurity.newRecoveryCode()
        val second = PinSecurity.newRecoveryCode()
        assertEquals(24, first.length)
        assertTrue(first.all { it in "0123456789ABCDEF" })
        assertNotEquals(first, second)
        assertTrue(PinSecurity.verify(first, PinSecurity.hash(first)))
    }
}
