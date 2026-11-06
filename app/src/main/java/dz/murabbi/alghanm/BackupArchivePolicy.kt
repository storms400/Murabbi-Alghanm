package dz.murabbi.alghanm

/** Pure validation rules for untrusted backup archives; deliberately JVM-testable. */
internal object BackupArchivePolicy {
    private val photoExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")

    fun safeEntryName(name: String): Boolean {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") ||
            name.contains('\\') || name.contains('\u0000') || name.contains(':')) return false
        val segments = name.split('/')
        return segments.none { it.isBlank() || it == "." || it == ".." } &&
            segments.all { segment ->
                segment.all { ch -> ch.isLetterOrDigit() || ch in "._-" }
            }
    }

    fun supportedPhotoExtension(extension: String): Boolean =
        extension.lowercase() in photoExtensions

    fun safePhotoEntry(name: String): Boolean {
        if (!safeEntryName(name) || !name.startsWith("photos/")) return false
        val leaf = name.removePrefix("photos/")
        if (leaf.contains('/')) return false
        val base = leaf.substringBeforeLast('.', "")
        val extension = leaf.substringAfterLast('.', "")
        return base.isNotBlank() && supportedPhotoExtension(extension)
    }

    fun isDuplicateEntry(seen: Set<String>, name: String): Boolean = name in seen

    fun withinLimit(currentBytes: Long, incomingBytes: Long, maxBytes: Long): Boolean =
        currentBytes >= 0 && incomingBytes >= 0 && maxBytes >= 0 &&
            currentBytes <= maxBytes && incomingBytes <= maxBytes - currentBytes
}
