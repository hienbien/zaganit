package com.zaganit.daddylive

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DaddyLiveProvider : MainAPI() {
    override var mainUrl = "https://dlstreams.st"
    override var name = "DaddyLive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Live Channels",
        "$mainUrl/24-7-channels.php" to "24/7 Channels"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
        return try {
            val document = app.get(request.data, referer = "$mainUrl/").document
            val results = document.select(
                "a.upcoming-card[href*=\"stream-\"], a[href*=\"/stream/stream-\"]"
            ).mapNotNull { it.toChannel() }.distinctBy { it.url }
            newHomePageResponse(
                HomePageList(name = request.name, list = results, isHorizontalImages = false),
                hasNext = false
            )
        } catch (error: Exception) {
            Log.w(name, "Sayfa yuklenemedi (${request.data}): ${error.message}")
            newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
    }

    private fun Element.toChannel(): SearchResponse? {
        val hrefRaw = attr("abs:href").ifBlank { attr("href") }
        if (!hrefRaw.startsWith(mainUrl)) return null
        if (!hrefRaw.contains("/stream/stream-")) return null

        val img = selectFirst("img")
        var titleCandidate = attr("title").trim()
        if (titleCandidate.isBlank()) titleCandidate = selectFirst(".upcoming-card__title")?.text()?.trim().orEmpty()
        if (titleCandidate.isBlank()) titleCandidate = img?.attr("alt")?.trim().orEmpty()
        if (titleCandidate.isBlank()) titleCandidate = text().trim()
        if (titleCandidate.isBlank()) return null

        val poster = img?.attr("src")?.trim()?.takeIf { it.isNotBlank() && it.startsWith("http") }

        return newLiveSearchResponse(titleCandidate, hrefRaw, TvType.Live) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/").document
        val rawTitle = document.selectFirst("h1, h2, .page-title, .channel-title")?.text()?.trim()
            ?: url.substringAfterLast('/').removeSuffix(".php")
        val title = rawTitle.ifBlank { throw ErrorLoadingException("Baslik bulunamadi: $url") }

        return newLiveStreamLoadResponse(title, url, url)
    }

    /**
     * Yayin akisi:
     *  1. Kanal sayfasindaki harici iframe (premiumtv/daddy2.php?id=N tarzi)
     *  2. Embed sayfasinda Clappr config: source = window.atob('BASE64')
     *  3. Base64 cozulmus dogrudan HLS m3u8 (zaman tokenli - her oynatmada taze cekilir)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        return try {
            val html = app.get(data, referer = "$mainUrl/").text
            // Kanal sayfasindaki ilk iframe'in kaynagini al
            val embedSrc = Regex("""<iframe[^>]*(?:src|data-src)="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?: return false
            val embedUrl = when {
                embedSrc.startsWith("http") -> embedSrc
                embedSrc.startsWith("//") -> "https:$embedSrc"
                else -> return false
            }

            val embedHtml = app.get(
                embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$mainUrl/"
            ).text

            // Clappr source: window.atob('BASE64') veya duz m3u8
            val b64 = Regex("""atob\(\s*'([A-Za-z0-9+/=]{20,})'\s*\)""")
                .find(embedHtml)?.groupValues?.get(1)
            val streamUrl = b64?.let {
                runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull()
                    ?.trim()?.takeIf { u -> u.startsWith("http") }
            } ?: Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(embedHtml)?.value
            ?: return false

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl.substringBefore("/video/").ifBlank { embedUrl }
                    quality = getQualityFromName("")
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$embedUrl/"
                    )
                }
            )
            true
        } catch (error: Exception) {
            Log.e(name, "Yayin cozulemedi ($data): ${error.message}")
            false
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
