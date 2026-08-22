package com.zaganit.netfilim

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class NetFilimProvider : MainAPI() {
    override var mainUrl = "https://netfilim13.com"
    override var name = "NetFilim"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page={p}" to "Tüm Filmler",
        "$mainUrl/shows?page={p}" to "Diziler",
        "$mainUrl/turkce-dublaj-film-izle" to "Türkçe Dublajlı Filmler",
        "$mainUrl/turkce-altyazili-film-izle" to "Türkçe Altyazılı Filmler",
        "$mainUrl/hd-film-izle" to "HD Filmler",
        "$mainUrl/imdb-7-uzeri-filmler" to "IMDb 7+ Filmler",
        "$mainUrl/movies?lang_id=9&page={p}" to "Türkçe Yapım Filmler",
        "$mainUrl/movies?genre_id=14&page={p}" to "Aksiyon",
        "$mainUrl/movies?genre_id=15&page={p}" to "Gerilim",
        "$mainUrl/movies?genre_id=6&page={p}" to "Korku",
        "$mainUrl/movies?genre_id=21&page={p}" to "Yerli Korku",
        "$mainUrl/movies?genre_id=1&page={p}" to "Dram",
        "$mainUrl/movies?genre_id=3&page={p}" to "Komedi",
        "$mainUrl/movies?genre_id=8&page={p}" to "Romantik",
        "$mainUrl/movies?genre_id=9&page={p}" to "Macera",
        "$mainUrl/movies?genre_id=10&page={p}" to "Bilim Kurgu",
        "$mainUrl/movies?genre_id=13&page={p}" to "Suç ve Gizem",
        "$mainUrl/movies?genre_id=16&page={p}" to "Spor",
        "$mainUrl/movies?genre_id=17&page={p}" to "Animasyon",
        "$mainUrl/movies?genre_id=18&page={p}" to "Belgesel",
        "$mainUrl/movies?genre_id=5&page={p}" to "Seri Filmler",
        "$mainUrl/movies?genre_id=19&page={p}" to "Yetişkin",
        "$mainUrl/movies?genre_id=20&page={p}" to "Yasaklanmış Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paginated = request.data.contains(PAGE_TOKEN)
        if (!paginated && page > 1) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }

        val url = request.data.replace(PAGE_TOKEN, page.toString())
        val document = app.get(url, referer = "$mainUrl/").document

        val results = document.select("div.single-video").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        if (paginated && page > 1 && results.isEmpty()) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = results,
                isHorizontalImages = false
            ),
            hasNext = paginated && results.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val hrefRaw = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!hrefRaw.startsWith("http")) return null

        val isShow = hrefRaw.contains("/shows/")
        val isMovie = hrefRaw.contains("/film/")
        if (!isShow && !isMovie) return null
        // Dizi listelerinde yalnızca seri detay kartlarını kabul et
        if (isShow && !hrefRaw.contains("/shows/details/")) return null

        var titleCandidate: String? = anchor.attr("title").trim()
        if (titleCandidate.isNullOrBlank()) {
            titleCandidate = selectFirst("span.video-item-content")?.text()?.trim()
        }
        if (titleCandidate.isNullOrBlank()) {
            titleCandidate = selectFirst("img")?.attr("title")?.trim()
        }
        val title = titleCandidate.takeIf { !it.isNullOrBlank() } ?: return null

        val posterImg = selectFirst("img")
        val posterSrc = posterImg?.attr("src")?.trim().takeIf { !it.isNullOrBlank() }
            ?: posterImg?.attr("data-src")?.trim().takeIf { !it.isNullOrBlank() }
        val poster = posterSrc?.let { fixUrlSafe(it) }

        val ratingText = selectFirst("div.vid-lab-premium")?.text()?.trim()
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value }

        return if (isShow) {
            newTvSeriesSearchResponse(title, hrefRaw, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, hrefRaw, TvType.Movie) {
                this.posterUrl = poster
                score = ratingText?.let { value ->
                    runCatching { Score.from10(value.replace(",", ".").toDouble().toInt()) }.getOrNull()
                }
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        return elasticSearch(query)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return elasticSearch(query).items
    }

    private suspend fun elasticSearch(query: String): SearchResponseList {
        val term = query.trim()
        if (term.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        return try {
            val document = app.get(
                "$mainUrl/search_elastic",
                params = mapOf("s" to term),
                referer = "$mainUrl/"
            ).document

            val results = document.select("div.single-video").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            newSearchResponseList(results, hasNext = false)
        } catch (error: Exception) {
            Log.e(name, "Arama başarısız: ${error.message}")
            newSearchResponseList(emptyList(), hasNext = false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/").document

        val rawTitle = document.selectFirst("h1.movie-detail-title")?.text()?.trim()
            ?: document.selectFirst(".poster-dtl-item h2 a")?.text()?.trim()
            ?: ogMeta(document, "og:title")?.substringBefore("|")?.trim().orEmpty()
        val title = rawTitle.ifBlank {
            throw ErrorLoadingException("Başlık bulunamadı: $url")
        }

        val poster = ogMeta(document, "og:image")
        val plot = document.selectFirst("p.movie-detail-description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val jsonLd = document.select("script[type=application/ld+json]")
            .firstOrNull { it.html().contains("\"@type\":\"Movie\"") }
            ?.html().orEmpty()

        val year = Regex("\"datePublished\"\\s*:\\s*\"(\\d{4})")
            .find(jsonLd)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\((19\\d{2}|20\\d{2})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select(
            "ul.dtl-list-link a[href*=\"genre_id=\"], ul.dtl-list-link a[href*=\"lang_id=\"]"
        ).mapNotNull { it.attr("title").ifBlank { it.text() }.trim().takeIf { t -> t.isNotBlank() } }
            .distinct()

        val imdbScore = document.selectFirst("span.video-imdb-view")?.ownText()?.trim()
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value }
        val score = imdbScore?.let { value ->
            runCatching { Score.from10(value.replace(",", ".").toDouble().toInt()) }.getOrNull()
        }

        val duration = document.select("span.video-posts-author")
            .firstOrNull { it.selectFirst("i.fa-clock") != null }
            ?.text()?.trim()?.parseDuration()

        val actorData = parsePeople(document, "Oyuncular", roleLabel = null)
        val directorData = parsePeople(document, "Yönetmenler", roleLabel = "Yönetmen")

        val recommendations = document.select("div.related-video-item div.single-video")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val isSeries = url.contains("/shows/")

        return if (isSeries) {
            val episodes = fetchEpisodes(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
                this.actors = actorData + directorData
                this.recommendations = recommendations
            }
        } else {
            val watchUrl = url.replace("/film/", "/izle/")
            val trailers = fetchTrailers(watchUrl)

            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
                this.actors = actorData + directorData
                this.recommendations = recommendations
                this.trailers = trailers.toMutableList()
            }
        }
    }

    private fun parsePeople(
        document: Element,
        sectionKeyword: String,
        roleLabel: String?
    ): List<ActorData> {
        return document.select("span.des-bold-text")
            .firstOrNull { block ->
                block.selectFirst("strong")?.text()?.contains(sectionKeyword, ignoreCase = true) == true
            }
            ?.select("a[href*=\"/actors/\"]")
            ?.mapNotNull { person ->
                val personName = person.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ActorData(Actor(personName), roleString = roleLabel)
            }
            .orEmpty()
            .distinctBy { it.actor.name }
    }

    private suspend fun fetchEpisodes(seriesDocument: Element, seriesUrl: String): List<Episode> {
        val seasonUrls = linkedMapOf<String, Int>()

        seriesDocument.select("div.season-item-related a[href*=\"/seasons/\"]").forEach { anchor ->
            val seasonHref = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (!seasonHref.startsWith("http") || seasonUrls.containsKey(seasonHref)) return@forEach
            val seasonNumber = Regex("sezon-(\\d+)").find(seasonHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: seasonUrls.size + 1
            seasonUrls[seasonHref] = seasonNumber
        }

        if (seasonUrls.isEmpty()) {
            // Sezon listesi yoksa dizi sayfasındaki bölümleri doğrudan dene
            return parseEpisodesFromDocument(seriesDocument, season = 1)
        }

        return seasonUrls.entries.toList().amap { entry ->
            try {
                val seasonDocument = app.get(entry.key, referer = seriesUrl).document
                parseEpisodesFromDocument(seasonDocument, entry.value)
            } catch (error: Exception) {
                Log.w(name, "Sezon yüklenemedi (${entry.key}): ${error.message}")
                emptyList()
            }
        }.flatten().sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    private fun parseEpisodesFromDocument(document: Element, season: Int): List<Episode> {
        val episodeRegex = Regex(".*/shows/[^/]+/[^/]+/\\d+$")
        return document.select("div.single-video").mapNotNull { card ->
            val anchor = card.selectFirst("a[href]") ?: return@mapNotNull null
            val episodeHref = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (!episodeHref.startsWith("http") ||
                episodeHref.contains("/seasons/") ||
                episodeHref.contains("/shows/details/")
            ) return@mapNotNull null
            if (!episodeRegex.matches(episodeHref)) return@mapNotNull null

            var episodeTitle: String? = anchor.attr("title").trim()
            if (episodeTitle.isNullOrBlank()) {
                episodeTitle = card.selectFirst("span.video-item-content")?.text()?.trim()
            }
            episodeTitle = episodeTitle?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val episodeSlug = episodeHref.trimEnd('/').substringAfterLast('/')
            val episodeNumber = Regex("(?:bolum|bölüm)-?(\\d+)", RegexOption.IGNORE_CASE)
                .find(episodeSlug)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(?:bolum|bölüm)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull()

            val episodePoster = card.selectFirst("img")?.attr("src")?.trim()
                ?.takeIf { it.isNotBlank() }?.let { fixUrlSafe(it) }

            newEpisode(episodeHref) {
                this.name = episodeTitle
                this.season = season
                this.episode = episodeNumber
                this.posterUrl = episodePoster
            }
        }.distinctBy { it.data }
    }

    private suspend fun fetchTrailers(watchUrl: String): List<TrailerData> {
        return try {
            val watchText = app.get(watchUrl, referer = "$mainUrl/").text
            SOURCE_REGEX.findAll(watchText)
                .mapNotNull { match -> decodeSource(match.groupValues[1]) }
                .filter { it.isYoutubeUrl() }
                .toList()
                .distinct()
                .map { trailerUrl ->
                    TrailerData(
                        extractorUrl = trailerUrl,
                        referer = mainUrl,
                        raw = false,
                        headers = mapOf("User-Agent" to USER_AGENT)
                    )
                }
        } catch (error: Exception) {
            Log.w(name, "Fragman bilgisi alınamadı ($watchUrl): ${error.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) {
            Log.e(name, "Geçersiz yayın adresi: $data")
            return false
        }

        return try {
            val watchHtml = app.get(data, referer = "$mainUrl/").text
            val matches = SOURCE_REGEX.findAll(watchHtml).toList()
            var linkFound = false

            matches.forEachIndexed { index, match ->
                val streamUrl = decodeSource(match.groupValues[1]) ?: return@forEachIndexed
                val label = match.groupValues[2].trim()

                if (streamUrl.isYoutubeUrl()) {
                    // YouTube kaynakları (fragman vb.) dahili çıkarıcı ile denenir
                    if (loadExtractor(streamUrl, data, subtitleCallback, callback)) {
                        linkFound = true
                    }
                    return@forEachIndexed
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = label.ifBlank {
                            if (matches.size > 1) "$name ${index + 1}" else name
                        },
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = mainUrl
                        quality = getQualityFromName(label)
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to mainUrl
                        )
                    }
                )
                linkFound = true
            }

            SUBTITLE_REGEX.findAll(watchHtml).forEach { match ->
                val subUrl = match.groupValues[1].trim()
                val subLabel = match.groupValues[2].trim().ifBlank { "Türkçe" }
                if (subUrl.isNotBlank()) {
                    subtitleCallback(
                        newSubtitleFile(
                            subLabel,
                            fixUrlSafe(subUrl) ?: subUrl
                        )
                    )
                }
            }

            linkFound
        } catch (error: Exception) {
            Log.e(name, "Bağlantılar çözülemedi ($data): ${error.message}")
            false
        }
    }

    private fun decodeSource(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val decoded = if (trimmed.startsWith("encrypt:", ignoreCase = true)) {
            runCatching {
                String(
                    android.util.Base64.decode(
                        trimmed.substringAfter("encrypt:"),
                        android.util.Base64.DEFAULT
                    )
                )
            }.getOrElse {
                Log.w(name, "Kaynak çözülemedi: ${it.message}")
                return null
            }.trim()
        } else {
            trimmed
        }

        return decoded.takeIf { it.startsWith("http") }
    }

    private fun String.isYoutubeUrl(): Boolean {
        return contains("youtube.com/watch", ignoreCase = true) ||
            contains("youtu.be/", ignoreCase = true) ||
            contains("youtube.com/embed", ignoreCase = true)
    }

    private fun String.parseDuration(): Int? {
        val hours = Regex("(\\d+)\\s*(?:h|sa|sat)\\b", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*(?:m|dk|min)\\b", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun ogMeta(document: Element, property: String): String? {
        return document.selectFirst("meta[property=$property]")?.attr("content")
            ?.trim()?.ifBlank { null }
    }

    private fun fixUrlSafe(url: String?): String? {
        val trimmed = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> mainUrl + trimmed
            else -> "$mainUrl/$trimmed"
        }
    }

    companion object {
        private const val PAGE_TOKEN = "{p}"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val SOURCE_REGEX = Regex(
            """\{\s*source\s*:\s*"([^"]*)"\s*,\s*label\s*:\s*"([^"]*)"\s*,\s*isLive\s*:\s*"[^"]*"\s*\}"""
        )
        private val SUBTITLE_REGEX = Regex(
            """\{\s*subtitlePath\s*:\s*"([^"]*)"\s*,\s*subtileLabel\s*:\s*"([^"]*)"\s*\}"""
        )
    }
}
