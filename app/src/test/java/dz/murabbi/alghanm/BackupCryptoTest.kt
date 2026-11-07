package dz.murabbi.alghanm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    private val password = "Mawashi-Test-2026!"
    private val db = "SQLite fixture: animals, finance, treatments".toByteArray()
    private val photo = ByteArray(4096) { (it % 251).toByte() }

    private fun encryptedArchive(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(BackupCrypto.encryptingStream(output, password)).use { zip ->
            zip.putNextEntry(ZipEntry("farm.db")); zip.write(db); zip.closeEntry()
            zip.putNextEntry(ZipEntry("photos/animal-01.jpg")); zip.write(photo); zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun decrypt(bytes: ByteArray, pass: String = password, maxCipher: Long = 2_000_000,
                        maxPlain: Long = 2_000_000): Map<String, ByteArray> {
        val plain = ByteArrayOutputStream()
        BackupCrypto.decryptTo(ByteArrayInputStream(bytes), plain, pass, maxCipher, maxPlain)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(plain.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    @Test fun backupArchiveRoundTripsDatabaseAndPhoto() {
        val restored = decrypt(encryptedArchive())
        assertArrayEquals(db, restored["farm.db"])
        assertArrayEquals(photo, restored["photos/animal-01.jpg"])
        assertEquals(setOf("farm.db", "photos/animal-01.jpg"), restored.keys)
    }

    @Test fun wrongPasswordFailsAuthentication() {
        try { decrypt(encryptedArchive(), "Wrong-Password-2026!"); fail("wrong password accepted") }
        catch (_: Exception) { }
    }

    @Test fun tamperedCiphertextOrTagFailsAuthentication() {
        val bytes = encryptedArchive()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x40).toByte()
        try { decrypt(bytes); fail("tampered archive accepted") }
        catch (_: Exception) { }
    }

    @Test fun rejectsTruncatedEnvelopeBeforeDecrypting() {
        val shortHeader = BackupCrypto.MAGIC.copyOf()
        try {
            decrypt(shortHeader)
            fail("truncated header accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsEmptyAndTooShortPasswords() {
        try {
            BackupCrypto.encryptingStream(ByteArrayOutputStream(), "short")
            fail("short password accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            BackupCrypto.decryptTo(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(),
                "short", 100, 100)
            fail("short password accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun cipherAndPlaintextSizeLimitsAreEnforced() {
        val bytes = encryptedArchive()
        try {
            decrypt(bytes, maxCipher = 8); fail("cipher limit ignored")
        } catch (_: IllegalArgumentException) { }
        try {
            decrypt(bytes, maxPlain = 8); fail("plaintext limit ignored")
        } catch (_: IllegalArgumentException) { }
    }
}
