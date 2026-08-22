package com.zaganit.tvjustin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder

class TVJustinProvider : MainAPI() {
    override var mainUrl = "https://tvjustin.com"
    override var name = "TVJustin"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "all" to "Tümü",
        "channels" to "TV Kanalları",
        "football" to "Futbol",
        "basketball" to "Basketbol",
        "volleyball" to "Voleybol",
        "tennis" to "Tenis"
    )

    private val mapper = jacksonObjectMapper()
    private var cache: ScheduleCache? = null

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = getSections()
        val items = if (request.data == "all") {
            sections.values.flatten().distinctBy { it.key() }
        } else {
            sections[request.data].orEmpty()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items.mapNotNull { it.toSearchResponse() },
                isHorizontalImages = true
            )
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        return newSearchResponseList(findSearchResults(query), hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return findSearchResults(query)
    }

    private suspend fun findSearchResults(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        return getSections().values
            .flatten()
            .distinctBy { it.key() }
            .filter { item ->
                item.title.orEmpty().contains(term, ignoreCase = true) ||
                    item.league.orEmpty().contains(term, ignoreCase = true)
            }
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val title = queryValue(url, "title") ?: return null
        val league = queryValue(url, "league")
        val date = queryValue(url, "date")
        val time = queryValue(url, "time")
        val schedule = listOfNotNull(date, time)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        val details = listOfNotNull(
            league?.takeIf { it.isNotBlank() },
            schedule
        )

        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = POSTER_URL
            plot = details.joinToString(" • ").ifBlank { "TVJustin canlı yayını" }
            tags = listOfNotNull(league?.takeIf { it.isNotBlank() })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = queryValue(data, "id")?.takeIf { it.isValidStreamId() } ?: return false
        val playerUrl = "$mainUrl/event.html?id=${encode(id)}"

        return try {
            val playerHtml = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$mainUrl/"
            ).text

            if (id.startsWith("androstreamlivech")) {
                loadDynamicLinks(id, playerHtml, callback)
            } else {
                loadChecklistLinks(id, playerUrl, playerHtml, callback)
            }
        } catch (error: Exception) {
            Log.e(name, "Yayın bağlantısı çözülemedi: ${error.message}")
            false
        }
    }

    private suspend fun getSections(): Map<String, List<SiteItem>> {
        val currentTime = System.currentTimeMillis()
        cache?.takeIf { currentTime - it.createdAt < CACHE_DURATION }?.let {
            return it.sections
        }

        return try {
            val script = app.get(
                "$mainUrl/script3.js",
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$mainUrl/"
            ).text
            val sections = linkedMapOf(
                "schedule" to parseArray(script, "karsilasmalar"),
                "channels" to parseArray(script, "channels"),
                "football" to parseArray(script, "futbolMatches"),
                "basketball" to parseArray(script, "basketbolMatches"),
                "volleyball" to parseArray(script, "voleybolMatches"),
                "tennis" to parseArray(script, "tenisMatches")
            ).mapValues { (_, items) ->
                items.filter { it.isValid() }.distinctBy { it.key() }
            }

            if (sections.values.all { it.isEmpty() }) {
                throw ErrorLoadingException("TVJustin yayın listesi okunamadı")
            }

            cache = ScheduleCache(currentTime, sections)
            sections
        } catch (error: Exception) {
            cache?.sections ?: throw error
        }
    }

    private fun parseArray(script: String, variable: String): List<SiteItem> {
        val expression = Regex(
            """(?:const|let|var)\s+${Regex.escape(variable)}\s*=\s*(\[.*?\])\s*;""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(script)?.groupValues?.get(1) ?: return emptyList()

        return runCatching { mapper.readValue<List<SiteItem>>(expression) }
            .getOrElse {
                Log.e(name, "$variable listesi okunamadı: ${it.message}")
                emptyList()
            }
    }

    private fun SiteItem.toSearchResponse(): SearchResponse? {
        val itemTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val id = url?.let { queryValue(it, "id") }?.takeIf { it.isValidStreamId() }
            ?: return null
        val dataUrl = buildString {
            append(mainUrl)
            append("/event.html?id=")
            append(encode(id))
            append("&title=")
            append(encode(itemTitle))
            league?.takeIf { it.isNotBlank() }?.let {
                append("&league=")
                append(encode(it))
            }
            tarih?.takeIf { it.isNotBlank() }?.let {
                append("&date=")
                append(encode(it))
            }
            time?.takeIf { it.isNotBlank() }?.let {
                append("&time=")
                append(encode(it))
            }
        }

        return newLiveSearchResponse(itemTitle, dataUrl, TvType.Live, fix = false) {
            posterUrl = POSTER_URL
        }
    }

    private suspend fun loadChecklistLinks(
        id: String,
        playerUrl: String,
        playerHtml: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directUrls = findM3u8Urls(playerHtml)
        val streamName = if (id == "androstreamlivebs1" || id == "facebooklivebs1") {
            "batutest.m3u8"
        } else {
            "$id.m3u8"
        }
        val generatedUrls = CHECKLIST_BASE_REGEX.findAll(playerHtml)
            .map { it.groupValues[1].replace("\\/", "/") + streamName }
            .toList()
        val urls = (directUrls + generatedUrls).distinct()

        urls.forEachIndexed { index, streamUrl ->
            emitLink(
                streamUrl = streamUrl,
                label = "Kaynak ${index + 1}",
                referer = playerUrl,
                origin = mainUrl,
                callback = callback
            )
        }

        return urls.isNotEmpty()
    }

    private suspend fun loadDynamicLinks(
        id: String,
        mainPlayerHtml: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = id.replace(Regex("^androstreamlivech(?:stream)?"), "")
            .takeIf { it.isNotBlank() } ?: return false
        val dynamicHost = DYNAMIC_HOST_REGEX.find(mainPlayerHtml)?.groupValues?.get(1)
            ?: DEFAULT_DYNAMIC_HOST
        val dynamicPlayerUrl =
            "${dynamicHost.trimEnd('/')}/event.html?id=${encode(id)}"
        val dynamicHtml = app.get(
            dynamicPlayerUrl,
            headers = mapOf("User-Agent" to USER_AGENT),
            referer = "$mainUrl/"
        ).text

        val urls = linkedSetOf<String>()
        urls.addAll(findM3u8Urls(dynamicHtml))

        if (videoId.matches(PPH_ID_REGEX)) {
            resolvePph(videoId, dynamicHtml)?.let(urls::add)
        } else {
            resolveCinemaApi(videoId, dynamicHtml)?.let(urls::add)
        }

        if (urls.isEmpty()) {
            resolveCinemaApi(videoId, dynamicHtml)?.let(urls::add)
            resolvePph(videoId, dynamicHtml)?.let(urls::add)
        }

        urls.forEachIndexed { index, streamUrl ->
            emitLink(
                streamUrl = streamUrl,
                label = "Canlı ${index + 1}",
                referer = dynamicPlayerUrl,
                origin = originOf(dynamicPlayerUrl),
                callback = callback
            )
        }

        return urls.isNotEmpty()
    }

    private suspend fun resolveCinemaApi(videoId: String, playerHtml: String): String? {
        val apiUrl = CINEMA_API_REGEX.find(playerHtml)?.groupValues?.get(1)
            ?: DEFAULT_CINEMA_API
        val apiOrigin = originOf(apiUrl)
        val body = mapper.writeValueAsString(
            mapOf(
                "AppId" to scriptValue(playerHtml, "AppId", "5000"),
                "AppVer" to scriptValue(playerHtml, "AppVer", "1"),
                "VpcVer" to scriptValue(playerHtml, "VpcVer", "1.0.12"),
                "Language" to scriptValue(playerHtml, "Language", "en"),
                "Token" to scriptValue(playerHtml, "Token", ""),
                "VideoId" to videoId
            )
        ).toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        return runCatching {
            app.post(
                apiUrl,
                requestBody = body,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to apiOrigin,
                    "Referer" to "$apiOrigin/"
                )
            ).parsedSafe<CinemaResponse>()?.url
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private suspend fun resolvePph(videoId: String, playerHtml: String): String? {
        val prefix = PPH_PREFIX_REGEX.find(playerHtml)?.groupValues?.get(1)
            ?: DEFAULT_PPH_PREFIX
        val playerUrl = prefix + encode(videoId)

        return runCatching {
            val response = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = DEFAULT_DYNAMIC_HOST
            )
            response.document.selectFirst("source[src]")?.attr("src")
                ?.takeIf { it.startsWith("http") }
                ?: findM3u8Urls(response.text).firstOrNull()
        }.getOrNull()
    }

    private suspend fun emitLink(
        streamUrl: String,
        label: String,
        referer: String,
        origin: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$name $label",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to origin
                )
            }
        )
    }

    private fun findM3u8Urls(text: String): List<String> {
        return M3U8_REGEX.findAll(text)
            .map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .filterNot { it.contains("\${") }
            .distinct()
            .toList()
    }

    private fun scriptValue(script: String, key: String, fallback: String): String {
        return Regex("""["']${Regex.escape(key)}["']\s*:\s*["']([^"']*)["']""")
            .find(script)?.groupValues?.get(1) ?: fallback
    }

    private fun queryValue(url: String, key: String): String? {
        val value = Regex("""(?:[?&])${Regex.escape(key)}=([^&#]*)""")
            .find(url)?.groupValues?.get(1) ?: return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun originOf(url: String): String {
        return Regex("""^https?://[^/]+""").find(url)?.value ?: mainUrl
    }

    private fun String.isValidStreamId(): Boolean {
        return isNotBlank() &&
            !endsWith("None", ignoreCase = true) &&
            !equals("null", ignoreCase = true)
    }

    private fun SiteItem.isValid(): Boolean {
        return !title.isNullOrBlank() &&
            url?.let { queryValue(it, "id")?.isValidStreamId() } == true
    }

    private fun SiteItem.key(): String {
        return listOf(title, url, tarih, time).joinToString("|")
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SiteItem(
        @JsonProperty("tarih") val tarih: String? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("league") val league: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("live") val live: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CinemaResponse(
        @JsonProperty("URL") val url: String? = null
    )

    private data class ScheduleCache(
        val createdAt: Long,
        val sections: Map<String, List<SiteItem>>
    )

    companion object {
        private const val CACHE_DURATION = 120_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val POSTER_URL = "https://tvjustin.com/justin-tv-izle.jpg"
        private const val DEFAULT_DYNAMIC_HOST = "https://favorisentv1o6.xyz"
        private const val DEFAULT_CINEMA_API = "https://streamsport365.com/cinema"
        private const val DEFAULT_PPH_PREFIX = "https://pph.player-us.xyz/tv/?stream_id="

        private val CHECKLIST_BASE_REGEX =
            Regex("""["'](https?://[^"']+/checklist/)["']""", RegexOption.IGNORE_CASE)
        private val DYNAMIC_HOST_REGEX = Regex(
            """(?:var|let|const)\s+target\s*=\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        private val CINEMA_API_REGEX =
            Regex("""fetch\s*\(\s*["'](https?://[^"']+/cinema)["']""", RegexOption.IGNORE_CASE)
        private val PPH_PREFIX_REGEX = Regex(
            """(?:location(?:\.href)?|replace)\s*(?:=|\()\s*["'](https?://[^"']*stream_id=)["']""",
            RegexOption.IGNORE_CASE
        )
        private val PPH_ID_REGEX = Regex("""^(?:3\d{7}|400\d{5})$""")
        private val M3U8_REGEX = Regex(
            """https?:(?:\\?/){2}[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?""",
            RegexOption.IGNORE_CASE
        )
    }
}
