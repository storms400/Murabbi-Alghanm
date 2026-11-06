package dz.murabbi.alghanm

import org.junit.Assert.*
import org.junit.Test

class BackupArchivePolicyTest {
    @Test fun acceptsOnlySafeRelativePaths() {
        listOf("farm.db", "photos/MTIz.png", "photos/animal-01.webp").forEach {
            assertTrue("Expected safe: $it", BackupArchivePolicy.safeEntryName(it))
        }
        listOf("", "../farm.db", "photos/../../private", "/absolute/path",
            "\\\\server\\share", "photos\\\\evil.jpg", "photos//evil.jpg",
            "photos/./evil.jpg", "C:/farm.db", "photos/evil?.jpg").forEach {
            assertFalse("Expected rejected: $it", BackupArchivePolicy.safeEntryName(it))
        }
    }

    @Test fun acceptsOnlySupportedPhotoEntries() {
        listOf("photos/a.jpg", "photos/a.jpeg", "photos/a.png", "photos/a.webp",
            "photos/a.gif", "photos/a.heic", "photos/a.heif").forEach {
            assertTrue("Expected photo: $it", BackupArchivePolicy.safePhotoEntry(it))
        }
        listOf("farm.db", "photos/a.exe", "photos/a.html", "photos/sub/a.jpg",
            "photos/.jpg", "photos/../a.jpg").forEach {
            assertFalse("Expected rejected: $it", BackupArchivePolicy.safePhotoEntry(it))
        }
    }

    @Test fun detectsDuplicateArchiveEntries() {
        val seen = setOf("farm.db", "photos/a.jpg")
        assertTrue(BackupArchivePolicy.isDuplicateEntry(seen, "farm.db"))
        assertFalse(BackupArchivePolicy.isDuplicateEntry(seen, "photos/b.jpg"))
    }

    @Test fun enforcesAggregateSizeWithoutOverflow() {
        assertTrue(BackupArchivePolicy.withinLimit(10, 5, 15))
        assertFalse(BackupArchivePolicy.withinLimit(10, 6, 15))
        assertFalse(BackupArchivePolicy.withinLimit(Long.MAX_VALUE, 1, Long.MAX_VALUE))
        assertFalse(BackupArchivePolicy.withinLimit(-1, 1, 10))
    }
}
