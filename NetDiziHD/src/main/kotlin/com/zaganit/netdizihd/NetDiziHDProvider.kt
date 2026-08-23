package com.zaganit.netdizihd

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class NetDiziHDProvider : MainAPI() {
    override var mainUrl = "https://netdizihd.com"
    override var name = "NetDiziHD"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/shows?page={p}" to "Tüm Diziler",
        "$mainUrl/collections/son-eklenen-diziler/2" to "Son Eklenen Diziler",
        "$mainUrl/collections/pop%C3%BCler-diziler/6" to "Popüler Diziler",
        "$mainUrl/turkce-dizi-izle" to "Türkçe Diziler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/full-dizi-izle" to "Full Diziler"
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
        if (!hrefRaw.contains("/shows/details/")) return null

        // Rozet simgeleri (ic-premium vb.) hariç gerçek posteri seç
        val posterImg = select("img").firstOrNull { img ->
            val src = (img.attr("src") + "|" + img.attr("data-src")).lowercase()
            !src.contains("site_assets") && !src.contains("ic-premium")
        }

        var titleCandidate: String? = anchor.attr("title").trim()
        if (titleCandidate.isNullOrBlank()) {
            titleCandidate = selectFirst("span.video-item-content")?.text()?.trim()
        }
        if (titleCandidate.isNullOrBlank()) {
            titleCandidate = posterImg?.attr("title")?.trim()
        }
        val title = titleCandidate.takeIf { !it.isNullOrBlank() } ?: return null

        val posterSrc = posterImg?.attr("src")?.trim().takeIf { !it.isNullOrBlank() }
            ?: posterImg?.attr("data-src")?.trim().takeIf { !it.isNullOrBlank() }
        val poster = posterSrc?.let { fixUrlSafe(it) }

        val ratingText = selectFirst("div.vid-lab-premium")?.text()?.trim()
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value }

        return newTvSeriesSearchResponse(title, hrefRaw, TvType.TvSeries) {
            this.posterUrl = poster
            score = ratingText?.let { value ->
                runCatching { Score.from10(value.replace(",", ".").toDouble().toInt()) }.getOrNull()
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false)

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

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/").document

        val rawTitle = document.selectFirst(".poster-dtl-item h2 a")?.text()?.trim()
            ?: document.selectFirst("h1.movie-detail-title")?.text()?.trim()
            ?: ogMeta(document, "og:title")?.substringBefore("|")?.trim().orEmpty()
        val title = rawTitle.removeSuffix(" İzle").removeSuffix(" izle").trim().ifBlank {
            throw ErrorLoadingException("Başlık bulunamadı: $url")
        }

        val poster = ogMeta(document, "og:image")
        val plot = document.selectFirst("p.movie-detail-description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val tags = document.select(
            "ul.dtl-list-link a[href*=\"genre\"], ul.dtl-list-link a[href*=\"lang_id=\"]"
        ).mapNotNull { it.attr("title").ifBlank { it.text() }.trim().takeIf { t -> t.isNotBlank() } }
            .distinct()

        val imdbScore = document.selectFirst("span.video-imdb-view")?.ownText()?.trim()
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value }
        val score = imdbScore?.let { value ->
            runCatching { Score.from10(value.replace(",", ".").toDouble().toInt()) }.getOrNull()
        }

        val actorData = parsePeople(document, "Oyuncular", roleLabel = null)
        val directorData = parsePeople(document, "Yönetmenler", roleLabel = "Yönetmen")

        val recommendations = document.select("div.related-video-item div.single-video")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val episodes = fetchEpisodes(document, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = tags
            this.score = score
            this.actors = actorData + directorData
            this.recommendations = recommendations
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
            val seasonMatch = SEASON_NUMBER_REGEX.find(seasonHref)
            val seasonNumber = seasonMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                ?: seasonMatch?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                ?: seasonUrls.size + 1
            seasonUrls[seasonHref] = seasonNumber
        }

        if (seasonUrls.isEmpty()) {
            // Sezon listesi yoksa dizi sayfasındaki bölümleri doğrudan dene
            return parseEpisodesFromDocument(seriesDocument, season = 1)
        }

        // Sezonlar paralel yüklenir (çok sezonlu dizilerde hız için)
        val parsed = seasonUrls.entries.sortedBy { it.value }.amap { entry ->
            try {
                val seasonDocument = app.get(entry.key, referer = seriesUrl).document
                parseEpisodesFromDocument(seasonDocument, entry.value)
            } catch (error: Exception) {
                Log.w(name, "Sezon yüklenemedi (${entry.key}): ${error.message}")
                emptyList()
            }
        }.flatten()

        // Sezon sayfalarından bölüm çıkmadıysa dizi sayfasını doğrudan dene
        if (parsed.isEmpty()) {
            return parseEpisodesFromDocument(seriesDocument, season = 1)
        }

        return parsed
    }

    private fun parseEpisodesFromDocument(
        document: Element,
        season: Int
    ): List<Episode> {
        val episodeRegex = Regex(".*/shows/[^/]+/[^/]+/\\d+$")
        val cards = document.select("div.single-video")
        val episodes = mutableListOf<Episode>()
        var index = 0

        cards.forEach { card ->
            val anchor = card.selectFirst("a[href]") ?: return@forEach
            val episodeHref = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (!episodeHref.startsWith("http") ||
                episodeHref.contains("/seasons/") ||
                episodeHref.contains("/shows/details/")
            ) return@forEach
            if (!episodeRegex.matches(episodeHref)) return@forEach

            var episodeTitle: String? = anchor.attr("title").trim()
            if (episodeTitle.isNullOrBlank()) {
                episodeTitle = card.selectFirst("span.video-item-content")?.text()?.trim()
            }
            episodeTitle = episodeTitle?.takeIf { it.isNotBlank() } ?: return@forEach

            val episodeSlug = episodeHref.trimEnd('/').substringAfterLast('/')
            val explicitNumber = Regex("(?:bolum|bölüm)-?(\\d+)", RegexOption.IGNORE_CASE)
                .find(episodeSlug)?.groupValues?.get(1)?.toIntOrNull()

            val episodePoster = card.select("img").firstOrNull { img ->
                val src = (img.attr("src") + "|" + img.attr("data-src")).lowercase()
                !src.contains("site_assets") && !src.contains("ic-premium")
            }?.let { posterImg ->
                posterImg.attr("src").trim().takeIf { it.isNotBlank() }
                    ?: posterImg.attr("data-src").trim().takeIf { it.isNotBlank() }
            }?.let { fixUrlSafe(it) }

            episodes.add(
                newEpisode(episodeHref) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = explicitNumber ?: ++index
                    this.posterUrl = episodePoster
                }
            )
        }

        return episodes.distinctBy { it.data }
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

            // Asıl akış kaynakları: YouTube (fragman) olanlar hariç tutulur
            val directSources = matches.mapNotNull { match ->
                val streamUrl = decodeSource(match.groupValues[1]) ?: return@mapNotNull null
                if (streamUrl.isYoutubeUrl()) return@mapNotNull null
                streamUrl to match.groupValues[2].trim()
            }

            var linkFound = false

            directSources.forEachIndexed { index, (streamUrl, label) ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = label.ifBlank {
                            if (directSources.size > 1) "$name ${index + 1}" else name
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

            if (!linkFound) {
                // Asıl akış yoksa YouTube kaynağı oynatılabilir olarak denenir
                matches.forEach { match ->
                    val streamUrl = decodeSource(match.groupValues[1]) ?: return@forEach
                    if (!streamUrl.isYoutubeUrl()) return@forEach
                    if (loadExtractor(streamUrl, data, subtitleCallback, callback)) {
                        linkFound = true
                    }
                }
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

        // netfilim13 formati: sezon-1 | netdizihd formati: 1-sezon
        private val SEASON_NUMBER_REGEX = Regex("(?:sezon-(\\d+)|(\\d+)-sezon)", RegexOption.IGNORE_CASE)
        private val SOURCE_REGEX = Regex(
            """\{\s*source\s*:\s*"([^"]*)"\s*,\s*label\s*:\s*"([^"]*)"\s*,\s*isLive\s*:\s*"[^"]*"\s*\}"""
        )
        private val SUBTITLE_REGEX = Regex(
            """\{\s*subtitlePath\s*:\s*"([^"]*)"\s*,\s*subtileLabel\s*:\s*"([^"]*)"\s*\}"""
        )
    }
}
