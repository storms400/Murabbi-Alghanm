package dz.murabbi.alghanm

import org.junit.Assert.*
import org.junit.Test

class BackupArchivePolicyTest {
    @Test fun acceptsOnlyRelativeSafeArchiveNames() {
        assertTrue(BackupArchivePolicy.safeEntryName("farm.db"))
        assertTrue(BackupArchivePolicy.safeEntryName("photos/MTIz.png"))
        assertFalse(BackupArchivePolicy.safeEntryName("../farm.db"))
        assertFalse(BackupArchivePolicy.safeEntryName("photos/../../private"))
        assertFalse(BackupArchivePolicy.safeEntryName("/absolute/path"))
        assertFalse(BackupArchivePolicy.safeEntryName("photos\\\\evil.jpg"))
        assertFalse(BackupArchivePolicy.safeEntryName(""))
    }

    @Test fun acceptsCommonImageFormatsAndRejectsUnknownExtensions() {
        listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif").forEach {
            assertTrue(BackupArchivePolicy.supportedPhotoExtension(it))
        }
        assertFalse(BackupArchivePolicy.supportedPhotoExtension("exe"))
        assertFalse(BackupArchivePolicy.supportedPhotoExtension("html"))
    }
}
