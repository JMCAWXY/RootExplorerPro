package io.rootexplorer.pro.core.filesystem

/** Which attribute the file list is sorted by. */
enum class FileSortField(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    DATE("修改时间"),
    TYPE("类型"),
}

/** Sort direction. */
enum class SortDirection(val label: String) {
    ASCENDING("升序"),
    DESCENDING("降序"),
    ;

    fun toggled(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

/** A complete sort instruction: what to sort by and which way. */
data class SortSpec(
    val field: FileSortField = FileSortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
) {
    /**
     * Order a list of entries.
     *
     * Directories always come before files, which is what every desktop file manager does
     * and what the reference screenshots show. Within each group the chosen field decides.
     *
     * Entries whose value is unknown (no size, no timestamp — which is exactly what a root
     * `ls` listing gives us) always sort last, in both directions. Reporting an unknown value
     * as if it were zero would put it at the top of an ascending size sort, which reads as a
     * bug rather than as missing data.
     */
    fun apply(entries: List<FileEntry>): List<FileEntry> {
        val descending = direction == SortDirection.DESCENDING
        val comparator = when (field) {
            FileSortField.NAME -> Comparator<FileEntry> { a, b ->
                val c = a.name.compareTo(b.name, ignoreCase = true)
                if (descending) -c else c
            }

            FileSortField.SIZE -> Comparator<FileEntry> { a, b ->
                compareNullable(a.sizeBytes, b.sizeBytes, descending) { x, y -> x.compareTo(y) }
            }

            FileSortField.DATE -> Comparator<FileEntry> { a, b ->
                compareNullable(a.lastModifiedMillis, b.lastModifiedMillis, descending) { x, y ->
                    x.compareTo(y)
                }
            }

            FileSortField.TYPE -> Comparator<FileEntry> { a, b ->
                val c = a.extension.compareTo(b.extension, ignoreCase = true)
                if (c != 0) {
                    if (descending) -c else c
                } else {
                    val n = a.name.compareTo(b.name, ignoreCase = true)
                    if (descending) -n else n
                }
            }
        }

        return entries.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }.then(comparator),
        )
    }

    /**
     * Compare two optional values, keeping nulls last whichever way we are sorting.
     *
     * Non-null values are compared with [compare] and the result flipped for descending
     * order; a null is treated as "after everything" so it stays at the bottom either way.
     */
    private fun <T : Comparable<T>> compareNullable(
        left: T?,
        right: T?,
        descending: Boolean,
        compare: (T, T) -> Int,
    ): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> {
            val c = compare(left, right)
            if (descending) -c else c
        }
    }
}

/** A filter the user can apply to the current listing. */
data class FileFilter(
    /** Substring match against the file name; blank means no constraint. */
    val nameQuery: String = "",
    /** Match the extension (without dot); blank means no constraint. */
    val extension: String = "",
    /** When set, only this kind of entry is shown. */
    val kind: KindFilter = KindFilter.ALL,
) {
    val isActive: Boolean
        get() = nameQuery.isNotBlank() || extension.isNotBlank() || kind != KindFilter.ALL

    fun matches(entry: FileEntry): Boolean {
        if (nameQuery.isNotBlank() && !entry.name.contains(nameQuery, ignoreCase = true)) return false
        if (extension.isNotBlank()) {
            val wanted = extension.removePrefix(".").lowercase()
            if (entry.extension != wanted) return false
        }
        return when (kind) {
            KindFilter.ALL -> true
            KindFilter.FOLDERS_ONLY -> entry.isDirectory
            KindFilter.FILES_ONLY -> !entry.isDirectory
            KindFilter.IMAGES -> !entry.isDirectory && entry.extension in IMAGE_EXTENSIONS
            KindFilter.VIDEO -> !entry.isDirectory && entry.extension in VIDEO_EXTENSIONS
            KindFilter.AUDIO -> !entry.isDirectory && entry.extension in AUDIO_EXTENSIONS
            KindFilter.DOCUMENTS -> !entry.isDirectory && entry.extension in DOCUMENT_EXTENSIONS
            KindFilter.ARCHIVES -> !entry.isDirectory && entry.extension in ARCHIVE_EXTENSIONS
            KindFilter.APK -> !entry.isDirectory && entry.extension == "apk"
        }
    }

    enum class KindFilter(val label: String) {
        ALL("全部"),
        FOLDERS_ONLY("仅文件夹"),
        FILES_ONLY("仅文件"),
        IMAGES("图片"),
        VIDEO("视频"),
        AUDIO("音频"),
        DOCUMENTS("文档"),
        ARCHIVES("压缩包"),
        APK("安装包"),
    }

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg", "avif")
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "ts")
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "opus", "wma", "amr")
        val DOCUMENT_EXTENSIONS = setOf(
            "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "md", "json", "xml", "csv", "log", "ini", "conf", "yaml", "yml", "html", "htm",
        )
        val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar", "apk", "iso", "zst")
    }
}
