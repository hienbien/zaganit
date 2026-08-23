package com.zaganit.filmdozu

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class FilmDozuProvider : MainAPI() {
    override var mainUrl = "https://www.filmdozu.com"
    override var name = "FilmDozu"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/dublaj/" to "Turkce Dublajli",
        "$mainUrl/altyazili/" to "Turkce Altyazili",
        "$mainUrl/kategori/aksiyon/" to "Aksiyon",
        "$mainUrl/kategori/macera/" to "Macera",
        "$mainUrl/kategori/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/kategori/fantastik/" to "Fantastik",
        "$mainUrl/kategori/animasyon/" to "Animasyon",
        "$mainUrl/kategori/komedi/" to "Komedi",
        "$mainUrl/kategori/dram/" to "Dram",
        "$mainUrl/kategori/romantik/" to "Romantik",
        "$mainUrl/kategori/aile/" to "Aile",
        "$mainUrl/kategori/korku/" to "Korku",
        "$mainUrl/kategori/gerilim/" to "Gerilim",
        "$mainUrl/kategori/gizem/" to "Gizem",
        "$mainUrl/kategori/suc/" to "Suc",
        "$mainUrl/kategori/tarih/" to "Tarih",
        "$mainUrl/kategori/savas/" to "Savas",
        "$mainUrl/kategori/belgesel/" to "Belgesel"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data.trimEnd('/')}/page/$page/" else request.data
        return try {
            val document = app.get(url, headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml"), referer = "$mainUrl/").document
            val results = document.select("a[href*=\"/film/\"], a[href*=\"/dizi/\"]")
                .mapNotNull { it.toSearchResult() }
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

    private fun Element.toSearchResult(): SearchResponse? {
        val hrefRaw = attr("abs:href").ifBlank { attr("href") }
        if (!hrefRaw.startsWith(mainUrl)) return null
        val path = hrefRaw.removePrefix(mainUrl).trimStart('/')
        // Icerik: /film/{slug} veya /dizi/{slug}; kategori/liste sayfalari elenir
        if (!path.startsWith("film/") && !path.startsWith("dizi/")) return null
        if (path.trimEnd('/').removePrefix("film/").removePrefix("dizi/").contains("/")) return null

        val img = selectFirst("img")
        var titleCandidate = attr("title").trim()
        if (titleCandidate.isBlank()) titleCandidate = img?.attr("title")?.trim().orEmpty()
        if (titleCandidate.isBlank()) titleCandidate = img?.attr("alt")?.trim().orEmpty()
        // Baslik yoksa slug'dan uret (makadam-hikayeleri -> Makadam Hikayeleri)
        if (titleCandidate.isBlank()) {
            titleCandidate = hrefRaw.substringAfterLast('/')
                .removeSuffix("/").split("-").joinToString(" ") { w ->
                    w.replaceFirstChar { it.uppercase() }
                }
        }
        if (titleCandidate.isBlank() || img == null) return null
        val title = titleCandidate

        val poster = listOf(img.attr("data-src"), img.attr("src"))
            .firstOrNull { it.trim().startsWith("http") }?.let { fix(it) }

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
            val results = document.select("a[href*=\"/film/\"], a[href*=\"/dizi/\"]")
                .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
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
        val title = rawTitle.removeSuffix(" izle").removeSuffix(" İzle")
            .removeSuffix(" İzlenir").trim().ifBlank {
                throw ErrorLoadingException("Baslik bulunamadi: $url")
            }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        val plot = document.selectFirst(".aciklama, .ozet, .card-info, .icerik")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("\\((19\\d{2}|20\\d{2})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("a[href*=\"/yil/\"], .yil, .year")?.text()?.trim()?.toIntOrNull()

        val tags = document.select("a[href*=\"/kategori/\"], a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val isSeries = url.contains("/dizi/")
        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*=\"/bolum/\"], a[href*=\"/dizi/\"]").forEach { anchor ->
                val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
                if (!href.startsWith("http") || href == url) return@forEach
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
    }
}
