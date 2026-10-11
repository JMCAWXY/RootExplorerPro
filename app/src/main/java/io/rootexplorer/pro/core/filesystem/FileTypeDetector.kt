package io.rootexplorer.pro.core.filesystem

/**
 * Maps a file name to a category, a MIME type and a short type description.
 *
 * Used by the file list (to draw the right glyph), the filter (to match "images") and the
 * properties dialog (to print "类型：PNG 图片"). It is a plain lookup table rather than a
 * guess, and anything unknown reports itself as unknown.
 */
object FileTypeDetector {

    /** Coarse category, used to pick an icon and to group filters. */
    enum class Category { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, CODE, FONT, DATABASE, UNKNOWN }

    data class Info(
        val category: Category,
        /** Human readable, e.g. "PNG 图片" or "未知类型". */
        val description: String,
        /** Best-effort MIME type; "application/octet-stream" when not recognised. */
        val mimeType: String,
    )

    private val table: Map<String, Info> = buildMap {
        fun put(ext: String, category: Category, description: String, mime: String) {
            this[ext] = Info(category, description, mime)
        }

        // Images
        put("jpg", Category.IMAGE, "JPEG 图片", "image/jpeg")
        put("jpeg", Category.IMAGE, "JPEG 图片", "image/jpeg")
        put("png", Category.IMAGE, "PNG 图片", "image/png")
        put("gif", Category.IMAGE, "GIF 图片", "image/gif")
        put("bmp", Category.IMAGE, "BMP 图片", "image/bmp")
        put("webp", Category.IMAGE, "WebP 图片", "image/webp")
        put("heic", Category.IMAGE, "HEIC 图片", "image/heic")
        put("heif", Category.IMAGE, "HEIF 图片", "image/heif")
        put("avif", Category.IMAGE, "AVIF 图片", "image/avif")
        put("svg", Category.IMAGE, "SVG 矢量图", "image/svg+xml")

        // Video
        put("mp4", Category.VIDEO, "MP4 视频", "video/mp4")
        put("mkv", Category.VIDEO, "MKV 视频", "video/x-matroska")
        put("avi", Category.VIDEO, "AVI 视频", "video/x-msvideo")
        put("mov", Category.VIDEO, "QuickTime 视频", "video/quicktime")
        put("wmv", Category.VIDEO, "WMV 视频", "video/x-ms-wmv")
        put("flv", Category.VIDEO, "FLV 视频", "video/x-flv")
        put("webm", Category.VIDEO, "WebM 视频", "video/webm")
        put("3gp", Category.VIDEO, "3GP 视频", "video/3gpp")
        put("m4v", Category.VIDEO, "M4V 视频", "video/x-m4v")
        put("ts", Category.VIDEO, "MPEG-TS 视频", "video/mp2t")

        // Audio
        put("mp3", Category.AUDIO, "MP3 音频", "audio/mpeg")
        put("flac", Category.AUDIO, "FLAC 音频", "audio/flac")
        put("wav", Category.AUDIO, "WAV 音频", "audio/wav")
        put("aac", Category.AUDIO, "AAC 音频", "audio/aac")
        put("ogg", Category.AUDIO, "OGG 音频", "audio/ogg")
        put("m4a", Category.AUDIO, "M4A 音频", "audio/mp4")
        put("opus", Category.AUDIO, "Opus 音频", "audio/opus")
        put("amr", Category.AUDIO, "AMR 音频", "audio/amr")

        // Documents
        put("txt", Category.DOCUMENT, "文本文档", "text/plain")
        put("log", Category.DOCUMENT, "日志文件", "text/plain")
        put("md", Category.DOCUMENT, "Markdown 文档", "text/markdown")
        put("pdf", Category.DOCUMENT, "PDF 文档", "application/pdf")
        put("doc", Category.DOCUMENT, "Word 文档", "application/msword")
        put("docx", Category.DOCUMENT, "Word 文档", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        put("xls", Category.DOCUMENT, "Excel 表格", "application/vnd.ms-excel")
        put("xlsx", Category.DOCUMENT, "Excel 表格", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        put("ppt", Category.DOCUMENT, "PowerPoint 演示", "application/vnd.ms-powerpoint")
        put("pptx", Category.DOCUMENT, "PowerPoint 演示", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        put("csv", Category.DOCUMENT, "CSV 表格", "text/csv")
        put("epub", Category.DOCUMENT, "EPUB 电子书", "application/epub+zip")

        // Archives
        put("zip", Category.ARCHIVE, "ZIP 压缩包", "application/zip")
        put("rar", Category.ARCHIVE, "RAR 压缩包", "application/vnd.rar")
        put("7z", Category.ARCHIVE, "7z 压缩包", "application/x-7z-compressed")
        put("tar", Category.ARCHIVE, "TAR 归档", "application/x-tar")
        put("gz", Category.ARCHIVE, "GZip 压缩", "application/gzip")
        put("bz2", Category.ARCHIVE, "BZip2 压缩", "application/x-bzip2")
        put("xz", Category.ARCHIVE, "XZ 压缩", "application/x-xz")
        put("zst", Category.ARCHIVE, "Zstandard 压缩", "application/zstd")
        put("jar", Category.ARCHIVE, "Java 归档", "application/java-archive")
        put("iso", Category.ARCHIVE, "光盘镜像", "application/x-iso9660-image")

        // Code and configuration
        put("json", Category.CODE, "JSON 数据", "application/json")
        put("xml", Category.CODE, "XML 文档", "text/xml")
        put("html", Category.CODE, "HTML 网页", "text/html")
        put("htm", Category.CODE, "HTML 网页", "text/html")
        put("js", Category.CODE, "JavaScript 脚本", "text/javascript")
        put("kt", Category.CODE, "Kotlin 源码", "text/x-kotlin")
        put("java", Category.CODE, "Java 源码", "text/x-java")
        put("sh", Category.CODE, "Shell 脚本", "text/x-shellscript")
        put("py", Category.CODE, "Python 源码", "text/x-python")
        put("c", Category.CODE, "C 源码", "text/x-c")
        put("cpp", Category.CODE, "C++ 源码", "text/x-c++")
        put("h", Category.CODE, "C 头文件", "text/x-c")
        put("ini", Category.CODE, "配置文件", "text/plain")
        put("conf", Category.CODE, "配置文件", "text/plain")
        put("prop", Category.CODE, "属性文件", "text/plain")
        put("yaml", Category.CODE, "YAML 配置", "text/yaml")
        put("yml", Category.CODE, "YAML 配置", "text/yaml")

        // Fonts and databases
        put("ttf", Category.FONT, "TrueType 字体", "font/ttf")
        put("otf", Category.FONT, "OpenType 字体", "font/otf")
        put("db", Category.DATABASE, "SQLite 数据库", "application/vnd.sqlite3")
        put("sqlite", Category.DATABASE, "SQLite 数据库", "application/vnd.sqlite3")

        // Android specific
        put("apk", Category.APK, "Android 安装包", "application/vnd.android.package-archive")
        put("dex", Category.APK, "Dalvik 可执行文件", "application/x-dex")
        put("odex", Category.APK, "优化后的 Dex", "application/x-dex")
        put("vdex", Category.APK, "VDEX 文件", "application/octet-stream")
        put("so", Category.UNKNOWN, "共享库", "application/x-sharedlib")
        put("img", Category.UNKNOWN, "磁盘镜像", "application/octet-stream")
        put("bin", Category.UNKNOWN, "二进制文件", "application/octet-stream")
    }

    /** Detect from a [FileEntry]; directories are reported as folders. */
    fun detect(entry: FileEntry): Info = when {
        entry.isDirectory -> Info(Category.FOLDER, "文件夹", "inode/directory")
        entry.isSymlink -> {
            val target = detectByExtension(entry.extension)
            Info(Category.UNKNOWN, "符号链接 → ${target.description}", target.mimeType)
        }
        entry.extension.isEmpty() -> Info(Category.UNKNOWN, "无扩展名文件", "application/octet-stream")
        else -> detectByExtension(entry.extension)
    }

    /** Detect from a bare file name. */
    fun detectByName(name: String): Info {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return Info(Category.UNKNOWN, "无扩展名文件", "application/octet-stream")
        return detectByExtension(name.substring(dot + 1).lowercase())
    }

    private fun detectByExtension(extension: String): Info =
        table[extension] ?: Info(Category.UNKNOWN, "${extension.uppercase()} 文件", "application/octet-stream")
}
