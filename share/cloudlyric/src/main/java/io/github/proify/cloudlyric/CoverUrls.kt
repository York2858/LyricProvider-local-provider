package io.github.proify.cloudlyric

object CoverUrls {
    fun normalize(raw: String?): String {
        val value = raw.orEmpty().trim().replace("{w}", "300").replace("{h}", "300").replace("{size}", "300").replace("{f}", "jpg")
        return when {
            value.startsWith("https://") -> value
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            value.startsWith("//") -> "https:$value"
            else -> ""
        }
    }
    fun qq(albumMid: String?, albumId: Long = 0): String = when {
        !albumMid.isNullOrBlank() && albumMid.all { it.isLetterOrDigit() } -> "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
        albumId > 0 -> "https://imgcache.qq.com/music/photo/album_300/${albumId % 100}/300_albumpic_${albumId}_0.jpg"
        else -> ""
    }
    fun soda(domain: String?, uri: String?): String {
        if (domain.isNullOrBlank() || uri.isNullOrBlank()) return ""
        return normalize(if (domain.contains(uri)) domain else "$domain$uri~c5_300x300.jpg")
    }
}
