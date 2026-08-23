package com.zaganit.filmzal

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Ortak oynatici cozumleyici:
 *  - movietubeNN.xyz / vidpapi.xyz (FirePlayer): POST do=getVideo -> videoSource (master.txt)
 *  - vidmoly: embed sayfasinda duz m3u8/mp4
 *  - player.html?url=... parametresindeki dogrudan baglanti (pixeldrain vb.)
 *  - digerleri: CloudStream yerlesik extractor'lari
 */
object EmbedResolver {

    private val IFRAME_REGEX = Regex("""<iframe[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val DIRECT_MEDIA_REGEX = Regex("""https?://[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*""", RegexOption.IGNORE_CASE)
    private val FIREPLAYER_HOST = Regex("""(?:movietube\d*|vidpapi)\.[a-z.]+""", RegexOption.IGNORE_CASE)
    private val VIDMOLY_HOST = Regex("""vidmoly\.[a-z.]+|molyhost\.[a-z.]+""", RegexOption.IGNORE_CASE)
    private val PLAYER_PARAM = Regex("""player\.html\?[^"']*url=([^"'&]+)""", RegexOption.IGNORE_CASE)

    suspend fun resolveAll(
        html: String,
        sourceName: String,
        siteUrl: String,
        userAgent: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val frames = LinkedHashSet<String>()
        IFRAME_REGEX.findAll(html).forEach { frames.add(decode(it.groupValues[1].trim())) }

        // iframe yoksa sayfada dogrudan medya baglantisi ara
        var found = false
        if (frames.isEmpty()) {
            DIRECT_MEDIA_REGEX.findAll(html).forEach { match ->
                callback(directLink(sourceName, match.value, siteUrl, userAgent))
                found = true
            }
            return found
        }

        for (frame in frames) {
            val url = when {
                frame.startsWith("//") -> "https:$frame"
                frame.startsWith("http") -> frame
                else -> null
            } ?: continue

            // YouTube iframe'leri fragman olur; oynatma listesini kirletmesin
            if (url.contains("youtube.com", ignoreCase = true) ||
                url.contains("youtu.be", ignoreCase = true)
            ) continue

            try {
                when {
                    FIREPLAYER_HOST.containsMatchIn(url) -> {
                        if (resolveFirePlayer(url, sourceName, userAgent, callback)) found = true
                    }
                    VIDMOLY_HOST.containsMatchIn(url) -> {
                        if (resolveDirectMedia(url, sourceName, siteUrl, userAgent, callback)) found = true
                    }
                    PLAYER_PARAM.containsMatchIn(frame) -> {
                        val inner = PLAYER_PARAM.find(frame)?.groupValues?.get(1) ?: continue
                        val innerUrl = decode(inner)
                        if (innerUrl.contains("pixeldrain.com/u/", ignoreCase = true)) {
                            val id = innerUrl.substringAfter("/u/").substringBefore('?').substringBefore('#')
                            callback(
                                newExtractorLink(source = sourceName, name = sourceName, url = "https://pixeldrain.com/api/file/$id") {
                                    this.referer = "https://pixeldrain.com/"
                                    quality = getQualityUnknown()
                                    headers = mapOf("User-Agent" to userAgent)
                                }
                            )
                            found = true
                        } else if (innerUrl.startsWith("http")) {
                            if (resolveDirectMedia(innerUrl, sourceName, siteUrl, userAgent, callback)) found = true
                            else if (loadExtractor(innerUrl, siteUrl, subtitleCallback, callback)) found = true
                        }
                    }
                    else -> {
                        if (runCatching { loadExtractor(url, siteUrl, subtitleCallback, callback) }.getOrDefault(false)) {
                            found = true
                        } else if (resolveDirectMedia(url, sourceName, siteUrl, userAgent, callback)) {
                            found = true
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(sourceName, "Embed cozulemedi ($url): ${error.message}")
            }
        }
        return found
    }

    private fun getQualityUnknown(): Int = 0

    // FirePlayer ailesi (movietube, vidpapi): once API + dogrulama, olmazsa WebView
    private suspend fun resolveFirePlayer(
        embedUrl: String,
        sourceName: String,
        userAgent: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hash = embedUrl.substringAfter("/video/").trimEnd('/').substringBefore('?')
        if (hash.isBlank()) return false
        val base = embedUrl.substringBefore("/video/")

        var sourceUrl: String? = null
        runCatching {
            val response = app.post(
                "$base/player/index.php?data=$hash&do=getVideo",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*"
                ),
                referer = embedUrl
            ).text
            val json = runCatching { org.json.JSONObject(response) }.getOrNull()
            sourceUrl = json?.optString("videoSource")?.replace("\\/", "/")
                ?.takeIf { it.startsWith("http") }
        }

        if (sourceUrl != null) {
            val ok = runCatching {
                app.get(
                    sourceUrl!!,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "*/*"
                    ),
                    referer = embedUrl
                ).text.startsWith("#EXTM3U")
            }.getOrDefault(false)

            if (ok) {
                emitFirePlayerLink(sourceUrl!!, base, embedUrl, sourceName, userAgent, callback)
                return true
            }
        }

        // WebView yolu: gercek tarayici motoru sayfayi acar, kendi oynaticisi master'i ceker
        Log.i(sourceName, "FirePlayer WebView yolu ($embedUrl)")
        runCatching {
            val resolver = WebViewResolver(Regex("""master\.txt|/cdn/hls/"""))
            val response = app.get(embedUrl, interceptor = resolver, referer = "$base/")
            val webViewUrl = response.url
            if (webViewUrl.startsWith("http")) {
                emitFirePlayerLink(webViewUrl, base, embedUrl, sourceName, userAgent, callback)
                return true
            }
        }
        return false
    }

    private suspend fun emitFirePlayerLink(
        url: String,
        base: String,
        embedUrl: String,
        sourceName: String,
        userAgent: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val cookieHeader = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(base)
        }.getOrNull()

        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "*/*"
        )
        if (!cookieHeader.isNullOrBlank()) headers["Cookie"] = cookieHeader

        callback(
            newExtractorLink(
                source = sourceName, name = sourceName, url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                quality = getQualityUnknown()
                this.headers = headers
            }
        )
    }

    // Embed sayfasinda duz m3u8/mp4 arar (vidmoly vb.)
    private suspend fun resolveDirectMedia(
        embedUrl: String,
        sourceName: String,
        siteUrl: String,
        userAgent: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val body = app.get(
                embedUrl,
                headers = mapOf("User-Agent" to userAgent, "Referer" to siteUrl),
                referer = siteUrl
            ).text
            val body2 = decode(body)
            val media = DIRECT_MEDIA_REGEX.find(body2)?.value ?: return false
            callback(directLink(sourceName, media, embedUrl, userAgent))
            true
        } catch (error: Exception) {
            Log.w(sourceName, "Dogrudan medya bulunamadi ($embedUrl): ${error.message}")
            false
        }
    }

    private suspend fun directLink(sourceName: String, url: String, referer: String, userAgent: String): ExtractorLink {
        return newExtractorLink(
            source = sourceName,
            name = sourceName,
            url = url,
            type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            quality = getQualityUnknown()
            headers = mapOf("User-Agent" to userAgent, "Referer" to referer)
        }
    }

    private fun decode(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#38;", "&")
            .replace("&#039;", "'")
            .replace("&#8217;", "'")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
    }
}
