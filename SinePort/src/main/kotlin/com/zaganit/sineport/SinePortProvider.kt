package com.zaganit.sineport

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class SinePortProvider : MainAPI() {
    override var mainUrl = "https://sineport.net"
    override var name = "SinePort"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Tum Filmler",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/tur/dram/" to "Dram",
        "$mainUrl/tur/fantastik/" to "Fantastik",
        "$mainUrl/tur/gerilim/" to "Gerilim",
        "$mainUrl/tur/gizem/" to "Gizem",
        "$mainUrl/tur/komedi/" to "Komedi",
        "$mainUrl/tur/korku/" to "Korku",
        "$mainUrl/tur/macera/" to "Macera"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data.trimEnd('/')}/page/$page/" else "${request.data.trimEnd('/')}/"
        return try {
            val document = app.get(url, headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html,application/xhtml+xml"), referer = "$mainUrl/").document
            val results = document.select("a[href*=\"/film/\"], a[href*=\"/dizi/\"]")
                .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
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
        val path = hrefRaw.removePrefix(mainUrl).trimStart('/').removeSuffix("/")
        if (!path.startsWith("film/") && !path.startsWith("dizi/")) return null
        val slug = path.substringAfter('/')

        val img = selectFirst("img")
        var titleCandidate = attr("title").trim()
        if (titleCandidate.isBlank()) titleCandidate = img?.attr("title")?.trim().orEmpty()
        if (titleCandidate.isBlank()) titleCandidate = img?.attr("alt")?.trim().orEmpty()
        if (titleCandidate.isBlank()) return null

        val poster = img?.attr("src")?.trim()?.takeIf { it.isNotBlank() }?.let { fix(it) }

        // Basligi slug'dan da kurtarabiliriz: kainatin-hakimleri -> Kainatin Hakimleri
        val fallbackTitle = slug.split('-').joinToString(" ") { w ->
            w.replaceFirstChar { it.uppercase() }
        }

        return if (hrefRaw.contains("/dizi/")) {
            newTvSeriesSearchResponse(titleCandidate.ifBlank { fallbackTitle }, hrefRaw, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(titleCandidate.ifBlank { fallbackTitle }, hrefRaw, TvType.Movie) {
                this.posterUrl = poster
            }
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
        val title = rawTitle.removeSuffix(" izle").removeSuffix(" İzle").trim().ifBlank {
            throw ErrorLoadingException("Baslik bulunamadi: $url")
        }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        val plot = document.selectFirst(".aciklama, .ozet, .icerik")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("\\((19\\d{2}|20\\d{2})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("a[href*=\"/yil/\"], .yil, .year")?.text()?.trim()?.toIntOrNull()

        val tags = document.select("a[href*=\"/tur/\"], a[rel=tag]").map { it.text().trim() }
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
