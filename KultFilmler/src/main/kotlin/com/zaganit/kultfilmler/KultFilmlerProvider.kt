package com.zaganit.kultfilmler

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class KultFilmlerProvider : MainAPI() {
    override var mainUrl = "https://kultfilmler.net"
    override var name = "KultFilmler"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Tum Filmler",
        "$mainUrl/category/aksiyon-filmleri-izle/" to "Aksiyon",
        "$mainUrl/category/bilim-kurgu-filmleri-izle/" to "Bilim Kurgu",
        "$mainUrl/category/animasyon-filmleri-izle/" to "Animasyon",
        "$mainUrl/category/aile-filmleri-izle/" to "Aile",
        "$mainUrl/category/biyografi-filmleri-izle/" to "Biyografi",
        "$mainUrl/category/belgesel-izle/" to "Belgesel"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data.trimEnd('/')}/page/$page/" else "${request.data.trimEnd('/')}/"
        return try {
            val document = app.get(url, headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml"), referer = "$mainUrl/").document
            val results = document.select("div.frag-k, article, div.item, div.movie, li.movie-item")
                .mapNotNull { it.toSearchResult() }
                .ifEmpty { document.select("a[href]").mapNotNull { it.toAnchorResult() } }
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
        return path !in STATIC_SLUGS && !path.startsWith("category") &&
            !path.startsWith("page") && !path.startsWith("oyuncular") && !path.startsWith("liste")
    }

    private fun Element.toAnchorResult(): SearchResponse? {
        val hrefRaw = attr("abs:href").ifBlank { attr("href") }
        if (!isContentUrl(hrefRaw)) return null
        val img = selectFirst("img") ?: return null
        val title = (img.attr("title").ifBlank { img.attr("alt") }).trim().ifBlank { null } ?: return null
        val poster = img.attr("src").trim().takeIf { it.isNotBlank() }?.let { fix(it) }
        return if (hrefRaw.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, hrefRaw, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, hrefRaw, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? = toAnchorResult()

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
            val results = document.select("a[href]").mapNotNull { it.toAnchorResult() }.distinctBy { it.url }
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
        val plot = document.selectFirst(".aciklama, .icerik, .content, .ozet")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("\\((19\\d{2}|20\\d{2})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("a[href*=\"/yil/\"], .yil, .year")?.text()?.trim()?.toIntOrNull()
        val imdbScore = document.select("span, div, b").firstOrNull {
            it.ownText().matches(Regex("""[0-9],[0-9]|[0-9]\.[0-9]"""))
        }?.ownText()
        val score = imdbScore?.let { runCatching { Score.from10(it.replace(",", ".").toDouble().toInt()) }.getOrNull() }
        val tags = document.select("a[href*=\"/category/\"], a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val isSeries = url.contains("/dizi/")
        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*=\"/bolum/\"]").forEach { anchor ->
                val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
                if (!href.startsWith("http")) return@forEach
                var epTitle = anchor.attr("title").trim().ifBlank { anchor.text().trim() }
                if (epTitle.isBlank()) return@forEach
                val seasonNum = Regex("sezon-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(?:bolum|bölüm)-?(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)
                episodes.add(newEpisode(href) { this.name = epTitle; this.season = seasonNum; this.episode = epNum })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
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
            val html = app.get(data, headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml"), referer = "$mainUrl/").text
            EmbedResolver.resolveAll(html, name, mainUrl, USER_AGENT, subtitleCallback, callback)
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
            "liste", "oyuncular", "iletisim", "hukuksal", "gizlilik", "dmca", "hakkinda",
            "bize-ulasin", "istek", "vip", "uyelik", "kayit", "giris"
        )
    }
}
