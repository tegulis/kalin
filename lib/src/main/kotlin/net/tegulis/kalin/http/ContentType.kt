package net.tegulis.kalin.http

/** Mime types for the most common file types. */
public enum class ContentType(
    /** The standardized mime type. */
    public val mimeType: String,
    /** True if the format is readable as text or viewable as media by common tools such as browsers. */
    public val isHumanReadable: Boolean,
    /** Common file extensions for this type. */
    public vararg val extensions: String,
) {
    TEXT_PLAIN("text/plain", true, "txt", "text", "log"),
    TEXT_CSS("text/css", true, "css"),
    TEXT_CSV("text/csv", false, "csv"),
    TEXT_HTML("text/html", true, "html", "htm"),
    TEXT_JS("text/javascript", true, "js", "mjs", "cjs"),
    TEXT_MARKDOWN("text/markdown", true, "md", "markdown"),
    TEXT_PROPERTIES("text/x-java-properties", true, "properties"),
    TEXT_XML("text/xml", true, "xml"),
    TEXT_EVENT_STREAM("text/event-stream", true),
    IMAGE_AVIF("image/avif", true, "avif"),
    IMAGE_BMP("image/bmp", true, "bmp"),
    IMAGE_GIF("image/gif", true, "gif"),
    IMAGE_ICO("image/vnd.microsoft.icon", true, "ico"),
    IMAGE_JPEG("image/jpeg", true, "jpeg", "jpg"),
    IMAGE_PNG("image/png", true, "png"),
    IMAGE_SVG("image/svg+xml", true, "svg"),
    IMAGE_TIFF("image/tiff", true, "tiff", "tif"),
    IMAGE_WEBP("image/webp", true, "webp"),
    AUDIO_AAC("audio/aac", true, "aac"),
    AUDIO_FLAC("audio/flac", true, "flac"),
    AUDIO_MIDI("audio/midi", true, "mid", "midi"),
    AUDIO_MPEG("audio/mpeg", true, "mp3"),
    AUDIO_OGA("audio/ogg", true, "oga"),
    AUDIO_OPUS("audio/opus", true, "opus"),
    AUDIO_WAV("audio/wav", true, "wav"),
    AUDIO_WEBA("audio/webm", true, "weba"),
    VIDEO_AVI("video/x-msvideo", true, "avi"),
    VIDEO_MP4("video/mp4", true, "mp4", "m4v"),
    VIDEO_MPEG("video/mpeg", true, "mpeg", "mpg"),
    VIDEO_OGG("video/ogg", true, "ogv", "ogg"),
    VIDEO_WEBM("video/webm", true, "webm"),
    VIDEO_QUICKTIME("video/quicktime", true, "mov"),
    FONT_OTF("font/otf", false, "otf"),
    FONT_TTF("font/ttf", false, "ttf"),
    FONT_WOFF("font/woff", false, "woff"),
    FONT_WOFF2("font/woff2", false, "woff2"),
    APPLICATION_OCTET_STREAM("application/octet-stream", false, "bin"),
    APPLICATION_BZ("application/x-bzip", false, "bz"),
    APPLICATION_BZ2("application/x-bzip2", false, "bz2"),
    APPLICATION_BR("application/brotli", false, "br"),
    APPLICATION_DOC("application/msword", false, "doc"),
    APPLICATION_DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, "docx"),
    APPLICATION_XLS("application/vnd.ms-excel", false, "xls"),
    APPLICATION_XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false, "xlsx"),
    APPLICATION_PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", false, "pptx"),
    APPLICATION_EPUB("application/epub+zip", false, "epub"),
    APPLICATION_GZ("application/gzip", false, "gz", "tgz"),
    APPLICATION_JSON("application/json", true, "json", "map"),
    APPLICATION_LD_JSON("application/ld+json", true, "jsonld"),
    APPLICATION_MANIFEST_JSON("application/manifest+json", true, "webmanifest"),
    APPLICATION_JAR("application/java-archive", false, "jar"),
    APPLICATION_PDF("application/pdf", true, "pdf"),
    APPLICATION_XML("application/xml", true),
    APPLICATION_POM("application/xml", true, "pom", "xsd", "xsl"),
    APPLICATION_RAR("application/vnd.rar", false, "rar"),
    APPLICATION_RSS("application/rss+xml", true, "rss"),
    APPLICATION_ATOM("application/atom+xml", true, "atom"),
    APPLICATION_SH("application/x-sh", true, "sh"),
    APPLICATION_TAR("application/x-tar", false, "tar"),
    APPLICATION_WASM("application/wasm", false, "wasm"),
    APPLICATION_XHTML("application/xhtml+xml", true, "xhtml"),
    APPLICATION_YAML("application/yaml", true, "yaml", "yml"),
    APPLICATION_TOML("application/toml", true, "toml"),
    APPLICATION_ZIP("application/zip", false, "zip"),
    APPLICATION_7Z("application/x-7z-compressed", false, "7z"),
    APPLICATION_FORM_URLENCODED("application/x-www-form-urlencoded", true),
    MULTIPART_FORM_DATA("multipart/form-data", false);

    override fun toString(): String = mimeType

    public companion object {
        public const val PLAIN: String = "text/plain"
        public const val CSS: String = "text/css"
        public const val HTML: String = "text/html"
        public const val XML: String = "text/xml"
        public const val APPLICATION_XML_MIME_TYPE: String = "application/xml"
        public const val OCTET_STREAM: String = "application/octet-stream"
        public const val JAVASCRIPT: String = "text/javascript"
        public const val JSON: String = "application/json"
        public const val FORM_DATA: String = "multipart/form-data"
        public const val FORM_URLENCODED: String = "application/x-www-form-urlencoded"
        public const val EVENT_STREAM: String = "text/event-stream"

        private val byExtension: Map<String, ContentType> = buildMap {
            for (type in ContentType.entries) for (ext in type.extensions) putIfAbsent(ext, type)
        }

        /** Finds the content type for a mime type (parameters are ignored). */
        @JvmStatic
        public fun contentType(mimeType: String): ContentType? {
            val bare = mimeType.substringBefore(';').trim()
            return entries.find { it.mimeType.equals(bare, ignoreCase = true) }
        }

        /** Finds the content type for a file extension (without the dot). */
        @JvmStatic
        public fun contentTypeByExtension(extension: String): ContentType? =
            byExtension[extension] ?: byExtension[extension.lowercase()]

        /** Finds the mime type for a file extension (without the dot). */
        @JvmStatic
        public fun mimeTypeByExtension(extension: String): String? = contentTypeByExtension(extension)?.mimeType
    }
}
