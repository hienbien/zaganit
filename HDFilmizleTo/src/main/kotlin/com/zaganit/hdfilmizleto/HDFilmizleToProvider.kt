package com.zaganit.hdfilmizleto

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class HDFilmizleToProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmizle.to"
    override var name = "HDFilmizleTo"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Tum Icerik",
        "$mainUrl/diziler/" to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Ana liste /page/N/ ile sayfalanir
        val url = if (page > 1) {
            "${request.data.trimEnd('/')}/page/$page/"
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
        var path = url.removePrefix(mainUrl).trimStart('/').removeSuffix("/")
        // Sorgu parametreli izleme baglantilari (/izle/{slug}?kaynak=) da iceriktir
        path = path.substringBefore('?')
        if (path.isBlank()) return false
        if (path.startsWith("dizi/")) return true
        if (path.startsWith("izle/")) return true
        if (path.contains("/")) return false
        return path !in STATIC_SLUGS && !path.contains(".")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val hrefRaw = attr("abs:href").ifBlank { attr("href") }
        if (!isContentUrl(hrefRaw)) return null
        val img = selectFirst("img") ?: return null
        var titleCandidate = attr("title").trim()
        if (titleCandidate.isBlank()) titleCandidate = img.attr("title").trim()
        if (titleCandidate.isBlank()) titleCandidate = img.attr("alt").trim()
        val title = titleCandidate.ifBlank { null } ?: return null

        val poster = img.attr("src").trim().takeIf { it.isNotBlank() && !it.contains("svg") }?.let { fix(it) }
            ?: img.attr("data-src")?.trim()?.takeIf { it.isNotBlank() }?.let { fix(it) }

        return if (hrefRaw.contains("/dizi/")) {
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
            ?: document.selectFirst(".poster-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val title = rawTitle.removeSuffix(" izle").removeSuffix(" İzle").trim().ifBlank {
            throw ErrorLoadingException("Baslik bulunamadi: $url")
        }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
            ?: document.selectFirst(".poster-image img")?.attr("src")?.let { fix(it) }
        val plot = document.selectFirst(".aciklama, .ozet, .icerik, .movie-description")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(
            document.selectFirst(".poster-year")?.text() ?: title
        )?.value?.toIntOrNull()

        val imdbScore = document.selectFirst(".poster-imdb, .imdb-puan")?.text()?.trim()
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value }
        val score = imdbScore?.let {
            runCatching { Score.from10(it.replace(",", ".").toDouble().toInt()) }.getOrNull()
        }

        val tags = document.select("a[href*=\"/tur/\"], a[href*=\"/kategori/\"], a[rel=tag]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val isSeries = url.contains("/dizi/")
        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*=\"bolum\"]").forEach { anchor ->
                val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
                if (!href.startsWith("http")) return@forEach
                var epTitle = anchor.attr("title").trim().ifBlank { anchor.text().trim() }
                if (epTitle.isBlank()) return@forEach
                val seasonNum = Regex("(?:sezon|season)-?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
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
            "filmler", "diziler", "imdb", "trend", "seriler", "oyuncular", "yonetmenler",
            "yakinda", "filmle", "film-robotu", "canli", "tur", "kategori", "kesfet",
            "iletisim", "hukuksal", "gizlilik", "dmca", "istek", "vip", "uyelik", "giris",
            "kayit", "wp-json", "xmlrpc.php", "feed", "comments", "page"
        )
    }
}
