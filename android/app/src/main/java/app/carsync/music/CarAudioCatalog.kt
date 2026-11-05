package app.carsync.music

/** Identifiers never contain a caller-supplied path or URL. */
object CarAudioCatalog {
    const val ROOT = "carsync-audio"
    fun mediaId(hash: String) = "audio:$hash"
    fun hash(mediaId: String): String? = mediaId.removePrefix("audio:").takeIf {
        mediaId.startsWith("audio:") && it.matches(Regex("[a-f0-9]{64}"))
    }
    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize < 1) return emptyList()
        val start = page.toLong() * pageSize
        if (start >= items.size) return emptyList()
        return items.subList(start.toInt(), minOf(items.size.toLong(), start + pageSize).toInt())
    }
}
