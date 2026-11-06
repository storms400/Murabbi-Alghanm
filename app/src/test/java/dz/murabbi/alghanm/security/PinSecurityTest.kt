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
