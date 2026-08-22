package com.cloudtvstream.filmmirasim

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmmirasimProvider : MainAPI() {
    override var mainUrl = "https://filmmirasim.ktb.gov.tr"
    override var name = "Film Mirasım"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Documentary)

    override val mainPage = mainPageOf(
        "$mainUrl/tr/categories/6/" to "1895-1918",
        "$mainUrl/tr/categories/5/" to "1918-1938",
        "$mainUrl/tr/categories/18/" to "1938-1950",
        "$mainUrl/tr/categories/19/" to "1950-1960",
        "$mainUrl/tr/categories/20/" to "1960 Sonrası",
        "$mainUrl/tr/categories/23/" to "Diğer"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/$page"
        }

        val results = app.get(url).document
            .select("div.edd_download")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = results,
                isHorizontalImages = true
            )
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val title = anchor.attr("title").ifBlank { anchor.text() }.ifBlank { return null }
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Documentary) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "$mainUrl/tr/search/0/0/$query"
        } else {
            "$mainUrl/tr/search/0/0/$query/all/all/page/$page"
        }

        val document = app.get(url).document
        val results = document.select("article.entry-item").mapNotNull { item ->
            val anchor = item.selectFirst("h3.entry-title a") ?: return@mapNotNull null
            val title = anchor.text().ifBlank { return@mapNotNull null }
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val poster = fixUrlNull(item.selectFirst("div.entry-thumb img")?.attr("src"))

            newMovieSearchResponse(title, href, TvType.Documentary) {
                posterUrl = poster
            }
        }

        val hasNext = document.selectFirst("ul.pagination a.next") != null
        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            ?.ifBlank { null }
            ?: return null

        val thumbnailScript = document.select("script")
            .firstOrNull { it.html().contains("var videoThumbnail") }
            ?.html()
            .orEmpty()
        val thumbnail = Regex("""var videoThumbnail\s*=\s*"([^"]+)";""")
            .find(thumbnailScript)
            ?.groupValues
            ?.get(1)

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
        val duration = parseDuration(
            document.selectFirst("span#ctl00_ContentPlaceHolder1_lblVideoSuresi")?.text()
        )
        val recommendations = document.select("div.latest_slider .item").mapNotNull { item ->
            val recTitle = item.selectFirst("span.title")?.text()?.trim()
                ?.ifBlank { null }
                ?: return@mapNotNull null
            val recHref = fixUrlNull(item.selectFirst("a.hover-link")?.attr("href"))
                ?: return@mapNotNull null
            val recPoster = fixUrlNull(item.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(recTitle, recHref, TvType.Documentary) {
                posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.Documentary, url) {
            posterUrl = fixUrlNull(thumbnail)
            plot = description
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    private fun parseDuration(value: String?): Int? {
        val parts = value
            ?.substringAfter("Süre :", "")
            ?.trim()
            ?.split(":")
            ?: return null

        if (parts.size < 2) return null

        val hours = parts.getOrNull(parts.size - 3)?.toIntOrNull() ?: 0
        val minutes = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return null
        return hours * 60 + minutes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) {
            Log.e(name, "Geçersiz detay adresi: $data")
            return false
        }

        val sourceScript = app.get(data).document
            .select("script")
            .firstOrNull { it.html().contains("var sources = JSON.parse") }
            ?.html()
            ?: return false
        val encodedSources = Regex(
            """JSON\.parse\('(.+?)'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(sourceScript)?.groupValues?.get(1) ?: return false

        return try {
            val json = encodedSources.replace("\\'", "'")
            val sources: List<Map<String, String>> = jacksonObjectMapper().readValue(json)
            var linkFound = false

            sources.forEach { source ->
                val videoUrl = source["src"]?.takeIf { it.isNotBlank() } ?: return@forEach
                val qualityLabel = source["label"].orEmpty()

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        INFER_TYPE
                    ) {
                        referer = data
                        headers = mapOf("Origin" to mainUrl)
                        quality = getQualityFromName(qualityLabel)
                    }
                )
                linkFound = true
            }

            linkFound
        } catch (error: Exception) {
            Log.e(name, "Video bağlantıları ayrıştırılamadı: ${error.message}")
            false
        }
    }
}
