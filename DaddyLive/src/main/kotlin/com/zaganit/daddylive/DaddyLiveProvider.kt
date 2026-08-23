package com.zaganit.daddylive

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://dlstreams.st"
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // Cloudflare challenge'ini WebView ile cozer; tek ornek (cerezler korunsun)
    private val cfKiller by lazy { CloudflareKiller() }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // Ana sayfadaki ozel kartlarin disindaki GERCEK kanal listesi kategori sayfalarinda:
        // index.php?cat=<ad> -> takvim olaylarina bagli watch.php?id=N linkleri
        private val CATEGORIES = listOf(
            "All Soccer Events ⚽",
            "Basketball 🏀",
            "Am. Football (NFL) 🏈",
            "Ice Hockey 🏒",
            "Baseball (MLB) ⚾",
            "Tennis 🎾",
            "MMA 🥊👊",
            "WWE",
            "Cricket 🏏",
            "Motorsport 🏎️🏁",
            "Golf ⛳",
            "Rugby Union 🏉",
            "Volleyball 🏐",
            "TV Shows 📺"
        )
    }

    override val mainPage = mainPageOf(
        *(
            listOf("home:$mainUrl/" to "Featured") +
                CATEGORIES.map { "cat:$it" to it }
            ).toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
        val kind = request.data.substringBefore(':')
        val value = request.data.substringAfter(':')
        val url = when (kind) {
            "cat" -> "$mainUrl/index.php?cat=" + URLEncoder.encode(value, "UTF-8")
            else -> value
        }
        return try {
            val document = app.get(
                url,
                headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html"),
                referer = "$mainUrl/",
                interceptor = cfKiller
            ).document
            val results = if (kind == "home") {
                document.select("a.upcoming-card[href*=\"stream-\"], a[href*=\"/stream/stream-\"]")
                    .mapNotNull { it.toStreamCard() }
            } else {
                document.select("a[href*=\"watch.php?id=\"]").mapNotNull { it.toWatchChannel() }
            }.distinctBy { it.url }
            newHomePageResponse(
                HomePageList(name = request.name, list = results, isHorizontalImages = false),
                hasNext = false
            )
        } catch (error: Exception) {
            Log.w(name, "Sayfa yuklenemedi ($url): ${error.message}")
            newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
    }

    private fun Element.toStreamCard(): SearchResponse? {
        val hrefRaw0 = attr("href").trim()
        val hrefRaw = when {
            hrefRaw0.startsWith("http") -> hrefRaw0
            hrefRaw0.startsWith("/") -> mainUrl + hrefRaw0
            else -> return null
        }
        if (!hrefRaw.contains("/stream/stream-")) return null

        val img = selectFirst("img")
        val title = selectFirst(".upcoming-card__title")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: attr("title").trim()
        if (title.isNullOrBlank()) return null

        val poster = img?.attr("src")?.trim()?.takeIf { it.startsWith("http") }
        return newLiveSearchResponse(title, hrefRaw, TvType.Live) { this.posterUrl = poster }
    }

    private fun Element.toWatchChannel(): SearchResponse? {
        val hrefRaw0 = attr("href").trim()
        val hrefRaw = when {
            hrefRaw0.startsWith("http") -> hrefRaw0
            hrefRaw0.startsWith("/") -> mainUrl + hrefRaw0
            else -> return null
        }
        if (!hrefRaw.contains("watch.php?id=")) return null
        val title = attr("title").trim().ifBlank { text().trim() }.ifBlank { return null }
        return newLiveSearchResponse(title, hrefRaw, TvType.Live)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html"),
            referer = "$mainUrl/",
            interceptor = cfKiller
        ).document
        val rawTitle = document.selectFirst("h1, h2")?.text()?.trim()
            ?: document.title().substringBefore('|').trim()
        val title = rawTitle.ifBlank { url.substringAfterLast('/') }
        return newLiveStreamLoadResponse(title, url, url)
    }

    /**
     * Yayin zinciri (birden fazla iframe katmani olabilir):
     *   watch.php?id=N -> stream/stream-N.php -> harici embed (premiumtv/daddyN.php) -> Clappr atob('BASE64')
     * Her seviyede dogrudan m3u8/atob ara; yoksa ilk iframe'i izle (en fazla 4 atlama).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        var current = data
        var hop = 0
        try {
            while (hop < 4) {
                val html = app.get(
                    current,
                    headers = mapOf("User-Agent" to DESKTOP_UA),
                    referer = "$mainUrl/",
                    interceptor = cfKiller
                ).text

                // 1) Clappr tarzi window.atob('BASE64') kaynak
                val b64 = Regex("""atob\(\s*'([A-Za-z0-9+/=]{20,})'\s*\)""")
                    .find(html)?.groupValues?.get(1)
                val streamUrl = b64?.let {
                    runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }
                        .getOrNull()
                        ?.trim()?.takeIf { u -> u.startsWith("http") }
                } ?: Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)?.value

                if (streamUrl != null) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = current
                            quality = getQualityFromName("")
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "$current/"
                            )
                        }
                    )
                    return true
                }

                // 2) Siradaki iframe katmanina in
                val iframeSrc = Regex("""<iframe[^>]*(?:src|data-src)="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
                    ?: return false

                current = when {
                    iframeSrc.startsWith("http") -> iframeSrc
                    iframeSrc.startsWith("//") -> "https:$iframeSrc"
                    iframeSrc.startsWith("/") -> mainUrl + iframeSrc
                    else -> return false
                }
                hop++
            }
            return false
        } catch (error: Exception) {
            Log.e(name, "Yayin cozulemedi ($data): ${error.message}")
            return false
        }
    }
}
