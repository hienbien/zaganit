package com.zaganit.kultfilmler

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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

    // FirePlayer ailesi (movietube, vidpapi): imzali HLS master playlist'i getirir
    private suspend fun resolveFirePlayer(
        embedUrl: String,
        sourceName: String,
        userAgent: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hash = embedUrl.substringAfter("/video/").trimEnd('/').substringBefore('?')
        if (hash.isBlank()) return false
        val base = embedUrl.substringBefore("/video/")
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

        val json = runCatching { org.json.JSONObject(response) }.getOrNull() ?: return false
        val sourceUrl = json.optString("videoSource").replace("\\/", "/").takeIf { it.startsWith("http") }
            ?: json.optString("securedLink").replace("\\/", "/").takeIf { it.startsWith("http") }
            ?: return false

        // Ana playlist'i KENDIMIZ cekip dogrulariz; koruma 200+"security error" metni
        // dondugunde ExoPlayer "malformed manifest" verir. Varyant playlist'ler genelde
        // korumasizdir -> kalite etiketleriyle dogrudan onlari veririz.
        val masterHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to embedUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "*/*"
        )
        var emitted = false
        runCatching {
            val masterText = app.get(sourceUrl, headers = masterHeaders, referer = embedUrl).text
            if (masterText.startsWith("#EXTM3U")) {
                val lines = masterText.lines()
                var i = 0
                while (i < lines.size - 1) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        var uriLine = lines[i + 1].trim()
                        i++
                        while (uriLine.startsWith("#") && i < lines.size - 1) {
                            uriLine = lines[i + 1].trim(); i++
                        }
                        if (uriLine.isNotBlank() && !uriLine.startsWith("#")) {
                            val absUri = if (uriLine.startsWith("http")) uriLine
                                else sourceUrl.substringBeforeLast('/') + "/" + uriLine.removePrefix("/")
                            val name = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1)
                            val res = Regex("""RESOLUTION=(\d+x(\d+))""").find(line)?.groupValues?.get(2)
                            val qualityLabel = name ?: res?.let { "${it}p" }
                            callback(
                                newExtractorLink(
                                    source = sourceName, name = "$sourceName ${qualityLabel ?: ""}".trim(),
                                    url = absUri, type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = base
                                    quality = getQualityFromName(qualityLabel ?: "")
                                    headers = mapOf(
                                        "User-Agent" to userAgent,
                                        "Referer" to base + "/"
                                    )
                                }
                            )
                            emitted = true
                        }
                    }
                    i++
                }
            }
        }

        if (!emitted) {
            // Dogrulama basarisiz -> orijinal URL ile en iyi cabasi
            callback(
                newExtractorLink(source = sourceName, name = sourceName, url = sourceUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = base
                    quality = getQualityUnknown()
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to embedUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                }
            )
        }
        return true
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
