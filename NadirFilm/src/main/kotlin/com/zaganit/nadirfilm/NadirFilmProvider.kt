package com.zaganit.nadirfilm

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class NadirFilmProvider : MainAPI() {
    override var mainUrl = "https://www.nadirfilm1.com"
    override var name = "NadirFilm"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Tum Filmler",
        "$mainUrl/en-cok-begenilen-filmler/" to "En Cok Begenilen Filmler",
        "$mainUrl/kategori/aksiyon/" to "Aksiyon",
        "$mainUrl/kategori/gerilim/" to "Gerilim",
        "$mainUrl/kategori/korku/" to "Korku",
        "$mainUrl/kategori/dram/" to "Dram",
        "$mainUrl/kategori/komedi/" to "Komedi",
        "$mainUrl/kategori/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/kategori/macera/" to "Macera",
        "$mainUrl/kategori/polisiye/" to "Polisiye"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Ana liste /page/N/ ile sayfalanir (1. sayfa icin ek yol yok)
        val url = if (page > 1 && request.data == mainUrl) {
            "$mainUrl/page/$page/"
        } else {
            "${request.data.trimEnd('/')}/"
        }
        return try {
            val document = app.get(url, headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml"), referer = "$mainUrl/").document
            val results = document.select("a[href]").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            newHomePageResponse(
                HomePageList(name = request.name, list = results, isHorizontalImages = false),
                hasNext = results.isNotEmpty()
            )
        } catch (error: Exception) {
            Log.w(name, "Sayfa yuklenemedi ($url): ${error.message}")
            newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
    }

    private fun isContentUrl(url: String): Boolean {
        if (!url.startsWith(mainUrl)) return false
        val path = url.removePrefix(mainUrl).trimStart('/').removeSuffix("/")
        if (path.isBlank() || path.contains("/")) return false
        return path !in STATIC_SLUGS &&
            !path.startsWith("kategori") && !path.startsWith("yil") &&
            !path.startsWith("yonetmen") && !path.startsWith("ulke") &&
            !path.startsWith("oyuncu") && !path.startsWith("seri-film") &&
            !path.startsWith("page") && !path.startsWith("wp-")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val hrefRaw = attr("abs:href").ifBlank { attr("href") }
        if (!isContentUrl(hrefRaw)) return null
        val img = selectFirst("img") ?: return null
        var titleCandidate = attr("title").trim()
        if (titleCandidate.isBlank()) titleCandidate = img.attr("title").trim()
        if (titleCandidate.isBlank()) titleCandidate = img.attr("alt").trim()
        val title = titleCandidate.ifBlank { null } ?: return null

        val poster = img.attr("src").trim().takeIf { it.isNotBlank() }?.let { fix(it) }
            ?: img.attr("data-src")?.trim()?.takeIf { it.isNotBlank() }?.let { fix(it) }

        return if (hrefRaw.contains("/dizi")) {
            newTvSeriesSearchResponse(title, hrefRaw, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, hrefRaw, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun fix(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> mainUrl + url
        else -> "$mainUrl/$url"
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        return try {
            val document = app.get("$mainUrl/", params = mapOf("s" to query.trim()), headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml"), referer = "$mainUrl/").document
            val results = document.select("a[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            newSearchResponseList(results, hasNext = false)
        } catch (error: Exception) {
            Log.e(name, "Arama basarisiz: ${error.message}")
            newSearchResponseList(emptyList(), hasNext = false)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml"), referer = "$mainUrl/").document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val title = rawTitle.removeSuffix(" izle").removeSuffix(" İzle").trim().ifBlank {
            throw ErrorLoadingException("Baslik bulunamadi: $url")
        }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        val plot = document.selectFirst(".aciklama, .ozet, .icerik, .entry-content")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("\\((19\\d{2}|20\\d{2})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("a[href*=\"/yil/\"]")?.text()?.trim()?.toIntOrNull()

        val tags = document.select("a[href*=\"/kategori/\"], a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val isSeries = url.contains("/dizi")
        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*=\"/bolum/\"]").forEach { anchor ->
                val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
                if (!href.startsWith("http")) return@forEach
                var epTitle = anchor.attr("title").trim().ifBlank { anchor.text().trim() }
                if (epTitle.isBlank()) return@forEach
                val seasonNum = Regex("sezon-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(?:bolum|bölüm)-?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)
                episodes.add(newEpisode(href) { this.name = epTitle; this.season = seasonNum; this.episode = epNum })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        return try {
            // Once normal yol
            val html = app.get(data, headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml"), referer = "$mainUrl/").text
            val viaEmbeds = EmbedResolver.resolveAll(html, name, mainUrl, USER_AGENT, subtitleCallback, callback)
            if (viaEmbeds) return true

            // vidsrcme tarzi WASM-korumali oynaticilar: sayfayi gercek WebView'de acip
            // oynaticinin kendi cektigi medya istegini yakala
            Log.i(name, "WebView yolu deneniyor ($data)")
            val viaWebView = runCatching {
                val resolver = WebViewResolver(Regex("""\.(?:m3u8|mp4)(\?.*)?$"""))
                val response = app.get(
                    data,
                    interceptor = resolver,
                    headers = mapOf("User-Agent" to DESKTOP_UA),
                    referer = "$mainUrl/"
                )
                val mediaUrl = response.url
                if (!mediaUrl.startsWith("http")) return@runCatching false

                val origin = mediaUrl.split("/").take(3).joinToString("/")
                val cookieHeader = runCatching {
                    android.webkit.CookieManager.getInstance().getCookie(origin)
                }.getOrNull()

                val headers = mutableMapOf("User-Agent" to DESKTOP_UA)
                if (!cookieHeader.isNullOrBlank()) headers["Cookie"] = cookieHeader

                callback(
                    newExtractorLink(
                        source = name, name = name, url = mediaUrl,
                        type = if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        quality = getQualityFromName("")
                        this.headers = headers
                    }
                )
                true
            }.getOrDefault(false)
            return viaWebView
        } catch (error: Exception) {
            Log.e(name, "Baglantilar cozulemedi ($data): ${error.message}")
            false
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val STATIC_SLUGS = setOf(
            "bize-ulasin", "en-cok-begenilen-filmler", "seri-filmler", "hukuksal", "dmca",
            "gizlilik", "iletisim", "hakkinda", "istek", "vip", "uyelik", "giris", "kayit",
            "wp-json", "xmlrpc.php", "feed", "comments"
        )
    }
}
