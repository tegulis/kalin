package net.tegulis.kalin.staticfiles

import net.tegulis.kalin.http.ContentType

/** Mime type lookup by file name. */
internal object MimeTypes {
    private val extra =
        mapOf(
            "apng" to "image/apng",
            "heic" to "image/heic",
            "jxl" to "image/jxl",
            "m3u8" to "application/vnd.apple.mpegurl",
            "ts" to "video/mp2t",
            "mkv" to "video/x-matroska",
            "m4a" to "audio/mp4",
            "vtt" to "text/vtt",
            "srt" to "application/x-subrip",
            "ics" to "text/calendar",
            "eot" to "application/vnd.ms-fontobject",
            "swf" to "application/x-shockwave-flash",
            "exe" to "application/vnd.microsoft.portable-executable",
            "dmg" to "application/x-apple-diskimage",
            "iso" to "application/x-iso9660-image",
            "deb" to "application/vnd.debian.binary-package",
            "rpm" to "application/x-rpm",
            "apk" to "application/vnd.android.package-archive",
            "xz" to "application/x-xz",
            "zst" to "application/zstd",
            "sql" to "application/sql",
            "kt" to "text/plain",
            "kts" to "text/plain",
            "java" to "text/plain",
            "c" to "text/plain",
            "h" to "text/plain",
            "py" to "text/plain",
            "rs" to "text/plain",
            "go" to "text/plain",
            "gradle" to "text/plain",
            "conf" to "text/plain",
            "ini" to "text/plain",
            "cfg" to "text/plain",
            "diff" to "text/plain",
            "patch" to "text/plain",
            "gpx" to "application/gpx+xml",
            "geojson" to "application/geo+json",
            "odt" to "application/vnd.oasis.opendocument.text",
            "ods" to "application/vnd.oasis.opendocument.spreadsheet",
            "odp" to "application/vnd.oasis.opendocument.presentation",
            "ppt" to "application/vnd.ms-powerpoint",
            "rtf" to "application/rtf",
            "glb" to "model/gltf-binary",
            "gltf" to "model/gltf+json",
        )

    /** The mime type for [fileName], or `application/octet-stream`. */
    fun forFileName(fileName: String, custom: Map<String, String> = emptyMap()): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return ContentType.OCTET_STREAM
        return custom[ext] ?: ContentType.mimeTypeByExtension(ext) ?: extra[ext] ?: ContentType.OCTET_STREAM
    }

    /** Adds `charset=utf-8` to textual types. */
    fun withCharset(mimeType: String): String {
        if (mimeType.contains("charset")) return mimeType
        val textual =
            mimeType.startsWith("text/") ||
                mimeType == "application/json" ||
                mimeType == "application/javascript" ||
                mimeType == "application/xml" ||
                mimeType.endsWith("+xml") ||
                mimeType.endsWith("+json") ||
                mimeType == "application/yaml" ||
                mimeType == "application/toml"
        return if (textual) "$mimeType;charset=utf-8" else mimeType
    }
}
