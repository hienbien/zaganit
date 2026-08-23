package com.zaganit.canlitv

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class CanliTVProvider : MainAPI() {
    override var mainUrl = "https://tr.canlitv.watch"
    override var name = "CanliTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl/kanallar/ulusal" to "Ulusal",
        "$mainUrl/kanallar/haber" to "Haber",
        "$mainUrl/kanallar/spor" to "Spor",
        "$mainUrl/kanallar/muzik" to "Muzik",
        "$mainUrl/kanallar/cocuk" to "Cocuk",
        "$mainUrl/kanallar/belgesel" to "Belgesel",
        "$mainUrl/kanallar/yerel" to "Yerel",
        "$mainUrl/kanallar/azerbaycan" to "Azerbaycan",
        "$mainUrl/kanallar/dini" to "Dini",
        "$mainUrl/kanallar/yabanci" to "Yabanci",
        "$mainUrl/kanallar/egitim-kanallari" to "TRT EBA",
        "$mainUrl/kanallar/deutsche-tv" to "Alman Kanallari",
        "$mainUrl/kanallar/kktc" to "KKTC"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Kanal listeleri tek sayfa; sayfa > 1 icin bos don
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
        return try {
            val document = app.get(request.data, referer = "$mainUrl/").document
            val results = document.select("a[href]").mapNotNull { it.toChannel() }.distinctBy { it.url }
            newHomePageResponse(
                HomePageList(name = request.name, list = results, isHorizontalImages = false),
                hasNext = false
            )
        } catch (error: Exception) {
            Log.w(name, "Sayfa yuklenemedi (${request.data}): ${error.message}")
            newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
    }

    private fun Element.toChannel(): SearchResponse? {
        val hrefRaw = attr("abs:href").ifBlank { attr("href") }
        if (!hrefRaw.startsWith("$mainUrl/")) return null
        val path = hrefRaw.removePrefix("$mainUrl/").trimStart('/').removeSuffix("/")
        if (path.isBlank() || path.contains("/") || path.contains("?")) return null
        if (path in STATIC_PATHS || !Regex("""^[a-z0-9-]+$""").matches(path)) return null

        val img = selectFirst("img") ?: return null
        var titleCandidate = attr("title").trim()
        if (titleCandidate.isBlank()) titleCandidate = img.attr("alt").trim()
        if (titleCandidate.isBlank()) titleCandidate = select("span, b, div").text().trim()
        if (titleCandidate.isBlank()) return null
        titleCandidate = titleCandidate.removeSuffix(" izle").removeSuffix(" İzle").trim()

        val poster = img.attr("src").trim().takeIf { it.isNotBlank() }?.let { fix(it) }

        return newLiveSearchResponse(titleCandidate, hrefRaw, TvType.Live) {
            this.posterUrl = poster
        }
    }

    private fun fix(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> mainUrl + url
        else -> "$mainUrl/$url"
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/").document
        val rawTitle = document.selectFirst("#player h1, h1")?.text()?.trim()?.ifBlank { null }
            ?: ogMeta(document, "og:title").orEmpty()
        val title = rawTitle.ifBlank { throw ErrorLoadingException("Baslik bulunamadi: $url") }
        val poster = document.selectFirst(".playerlogo, #player img")?.attr("src")?.let { fix(it) }
            ?: ogMeta(document, "og:image")

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
        }
    }

    /**
     * Yayin akisi:
     *  1. Kanal sayfasindaki JSON-LD VideoObject embedUrl = "saglayici:id" (alfa/omega/gia/mydo)
     *  2. GET /api/bot/{saglayici}?id={id} -> {"status":"success","stream_url":"..."}
     *  stream_url, sitenin kendi HLS proxy'si (m3u8); Referer ile oynatilir.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        return try {
            val html = app.get(data, referer = "$mainUrl/").text
            val embed = Regex("""\"embedUrl\"\s*:\s*\"([a-z]+):(\d+)\"""")
                .find(html)?.groupValues ?: return false
            val provider = embed[1]
            val id = embed[2]

            val apiText = app.get(
                "$mainUrl/api/bot/$provider?id=$id",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to data,
                    "Accept" to "application/json"
                ),
                referer = data
            ).text

            val json = runCatching { org.json.JSONObject(apiText) }.getOrNull() ?: return false
            if (json.optString("status") != "success") return false
            val streamUrl = json.optString("stream_url").replace("\\/", "/")
                .takeIf { it.startsWith("http") } ?: return false

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    quality = getQualityFromName("")
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
            true
        } catch (error: Exception) {
            Log.e(name, "Yayin cozulemedi ($data): ${error.message}")
            false
        }
    }

    private fun ogMeta(document: Element, property: String): String? {
        return document.selectFirst("meta[property=$property]")?.attr("content")
            ?.trim()?.ifBlank { null }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val STATIC_PATHS = setOf(
            "kanallar", "arama", "blog", "rss", "hakkimizda", "iletisim", "gizlilik",
            "add-comment", "apple-touch-icon.png"
        )
    }
}
