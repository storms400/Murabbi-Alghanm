package dz.murabbi.alghanm

/** Pure archive policy helpers kept separate so CI can exercise hostile ZIP paths. */
internal object BackupArchivePolicy {
    fun safeEntryName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("/") && !name.contains("..") &&
            !name.contains('\\') && !name.contains('\u0000')

    fun supportedPhotoExtension(extension: String): Boolean =
        extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")
}
