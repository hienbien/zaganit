package com.zaganit.korkutv

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class KorkuTVProvider : MainAPI() {
    override var mainUrl = "https://korku.tv"
    override var name = "KorkuTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // WordPress sayfalama: /page/N/ (en-cok-izlenenler ve tum-diziler tek sayfa)
    private val singlePageRows = setOf("en-cok-izlenenler", "tum-diziler")

    override val mainPage = mainPageOf(
        "$mainUrl/izle/korku-filmleri" to "Korku Filmleri",
        "$mainUrl/izle/gerilim-filmleri" to "Gerilim Filmleri",
        "$mainUrl/izle/gizem-filmleri" to "Gizem Filmleri",
        "$mainUrl/izle/psikolojik-filmler" to "Psikolojik Filmler",
        "$mainUrl/izle/seri-katiller" to "Seri Katiller",
        "$mainUrl/izle/vampir-filmleri" to "Vampir Filmleri",
        "$mainUrl/izle/zombi-filmleri" to "Zombi Filmleri",
        "$mainUrl/izle/canavarlar" to "Canavarlar",
        "$mainUrl/izle/cinli-filmler" to "Cinli Filmler",
        "$mainUrl/izle/kan-ve-gore" to "Kan ve Gore",
        "$mainUrl/izle/klasik-korku-filmleri" to "Klasik Korku",
        "$mainUrl/izle/18-yasakli-filmler" to "18+ Yasakli Filmler",
        "$mainUrl/turkce-dublaj-filmler" to "Turkce Dublajli Filmler",
        "$mainUrl/altyazili-filmler" to "Turkce Altyazili Filmler",
        "$mainUrl/imdb-7-ve-uzeri-filmler" to "IMDb 7+ Filmler",
        "$mainUrl/en-cok-izlenenler" to "En Cok Izlenenler",
        "$mainUrl/tum-diziler" to "Tum Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val slug = request.data.removePrefix("$mainUrl/").trimEnd('/')
        val url = if (page > 1 && slug !in singlePageRows) {
            "${request.data.trimEnd('/')}/page/$page/"
        } else {
            "${request.data.trimEnd('/')}/"
        }

        return try {
            val document = app.get(url, referer = "$mainUrl/").document
            val results = document.select("div.frag-k").mapNotNull { it.toSearchResult() }
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
        val anchor = selectFirst("a.resim[href], a[href]")
            ?: return null
        val hrefRaw = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!hrefRaw.startsWith("http")) return null
        if (!isContentUrl(hrefRaw)) return null

        var titleCandidate: String? = anchor.attr("title").trim()
        if (titleCandidate.isNullOrBlank()) {
            titleCandidate = anchor.selectFirst("img")?.attr("title")?.trim()
                ?: anchor.selectFirst("img")?.attr("alt")?.trim()
        }
        val title = titleCandidate.takeIf { !it.isNullOrBlank() } ?: return null

        val poster = anchor.selectFirst("img")
            ?.let { it.attr("src").trim().takeIf { s -> s.isNotBlank() } }
            ?.let { stripThumb(it) }

        return if (hrefRaw.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, hrefRaw, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, hrefRaw, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // Icerik URL'leri: /dizi/{slug}/ (seri), /bolum/{slug}/ (bolum - hariç),
    // kok seviyesi tek parcali URL'ler (film). Statik sayfalari ele.
    private fun isContentUrl(url: String): Boolean {
        if (!url.startsWith(mainUrl)) return false
        val path = url.removePrefix(mainUrl).trimStart('/').removeSuffix("/")
        if (path.isBlank()) return false
        if (path.contains("/") && !path.startsWith("dizi/")) return false
        if (path.startsWith("bolum/")) return false
        return path.split("/").last().let { slug ->
            slug !in STATIC_SLUGS
        }
    }

    // thumb_/150x243-1/wp-content/uploads/x.jpg -> wp-content/uploads/x.jpg
    private fun stripThumb(url: String): String? {
        val fixed = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }
        return Regex("""thumb_/[^/]+/(.*)""").find(fixed)?.let { match ->
            mainUrl + "/" + match.groupValues[1]
        } ?: fixed
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        return try {
            val document = app.get(
                "$mainUrl/",
                params = mapOf("s" to query.trim()),
                referer = "$mainUrl/"
            ).document
            val results = document.select("div.frag-k").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            newSearchResponseList(results, hasNext = false)
        } catch (error: Exception) {
            Log.e(name, "Arama basarisiz: ${error.message}")
            newSearchResponseList(emptyList(), hasNext = false)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/").document

        val rawTitle = document.selectFirst(".ssag h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ogMeta(document, "og:title")?.substringBefore("|")?.trim().orEmpty()
        val title = rawTitle.removeSuffix(" İzle").removeSuffix(" izle").trim().ifBlank {
            throw ErrorLoadingException("Baslik bulunamadi: $url")
        }

        val poster = document.selectFirst(".afis img")?.attr("src")
            ?.let { stripThumb(it) }
            ?: ogMeta(document, "og:image")

        val plot = document.selectFirst(".aciklama")?.text()?.trim()?.ifBlank { null }
            ?: ogMeta(document, "og:description")

        val year = document.selectFirst("p.bilgi a[href*=\"/yil/\"]")?.text()?.trim()
            ?.toIntOrNull()

        val imdbScore = document.select("p.bilgi span")
            .firstOrNull { it.selectFirst("i.fa-imdb") != null }
            ?.ownText()?.trim()?.ifBlank { null }
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value }
        val score = imdbScore?.let { value ->
            runCatching { Score.from10(value.replace(",", ".").toDouble().toInt()) }.getOrNull()
        }

        // Detay sekmesi: Tur / Orjinal Adi / Sure / Ulke
        val detayItems = document.select("ul.detay li")
        val tags = detayItems.firstOrNull { item ->
            item.selectFirst("strong")?.text()?.contains("Tür", ignoreCase = true) == true
        }?.select("a[href*=\"/izle/\"]")?.mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
            .orEmpty().distinct()

        val duration = detayItems.firstOrNull { li ->
            li.text().contains("Süre", ignoreCase = true)
        }?.text()?.let { Regex("""(\d+)\s*Dakika""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val isSeries = url.contains("/dizi/")

        return if (isSeries) {
            val episodes = fetchEpisodes(document)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
            }
        }
    }

    private fun fetchEpisodes(seriesDocument: Element): List<Episode> {
        // Sezon tablari: <a rel="s1">SEZON 1</a> -> <ol id="s1">
        val seasonIds = linkedMapOf<String, Int>()
        seriesDocument.select(".tab-baslik2 a[rel]").forEach { tabAnchor ->
            val rel = tabAnchor.attr("rel").trim()
            val number = Regex("""s(\d+)""").find(rel)?.groupValues?.get(1)?.toIntOrNull()
                ?: seasonIds.size + 1
            if (rel.isNotBlank()) seasonIds[rel] = number
        }

        val episodes = mutableListOf<Episode>()
        val lists = if (seasonIds.isNotEmpty()) {
            seriesDocument.select("ol[id]").filter { ol ->
                seasonIds.containsKey(ol.id())
            }
        } else {
            seriesDocument.select("ol[id^=s]")
        }

        for (ol in lists) {
            val seasonNumber = seasonIds[ol.id()] ?: 1
            ol.select("li").forEach { li ->
                val link = li.selectFirst("div.resim a[href*=\"/bolum/\"]") ?: return@forEach
                val href = link.attr("abs:href").ifBlank { link.attr("href") }
                if (!href.startsWith("http")) return@forEach

                val episodeNumber = li.selectFirst("div.num")?.text()?.trim()?.toIntOrNull()

                var episodeTitle: String? = link.attr("title").trim()
                if (episodeTitle.isNullOrBlank()) {
                    episodeTitle = li.selectFirst("div.baslik > a")?.ownText()?.trim()
                        ?: li.selectFirst("div.baslik > a")?.text()?.trim()
                }
                val subtitle = li.selectFirst("div.baslik > span")?.text()?.trim()
                episodeTitle = listOfNotNull(
                    episodeTitle?.takeIf { it.isNotBlank() },
                    subtitle?.takeIf { it.isNotBlank() }
                ).joinToString(" ").ifBlank { null } ?: return@forEach

                val poster = li.selectFirst("div.resim img")?.attr("src")
                    ?.let { stripThumb(it) }

                episodes.add(
                    newEpisode(href) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = poster
                    }
                )
            }
        }

        return episodes.distinctBy { it.data }.sortedWith(
            compareBy({ it.season ?: 1 }, { it.episode ?: 0 })
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) {
            Log.e(name, "Gecersiz yayin adresi: $data")
            return false
        }

        return try {
            val document = app.get(data, referer = "$mainUrl/").document

            // Oynaticilar span#plyg icindeki iframe'lerde; baska yerde yoksa tum iframe'lere bak
            val frames = LinkedHashSet<String>()
            document.select("span#plyg iframe[src]").forEach { frames.add(it.attr("src")) }
            if (frames.isEmpty()) {
                document.select("iframe[src]").forEach { frames.add(it.attr("src")) }
            }

            var found = false
            for (frameSrc in frames) {
                val embedUrl = fixEmbedUrl(frameSrc) ?: continue

                if (embedUrl.contains(FIREPLAYER_HOST_REGEX)) {
                    if (resolveFirePlayer(embedUrl, callback)) found = true
                } else {
                    if (runCatching {
                            loadExtractor(embedUrl, data, subtitleCallback, callback)
                        }.getOrDefault(false)
                    ) found = true
                }
            }

            found
        } catch (error: Exception) {
            Log.e(name, "Baglantilar cozulemedi ($data): ${error.message}")
            false
        }
    }

    private fun fixEmbedUrl(src: String): String? {
        val trimmed = src.trim()
        if (trimmed.isBlank() || trimmed.startsWith("data:")) return null
        return when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> null
        }
    }

    // FirePlayer akisi:
    //  1. POST /player/index.php?data={hash}&do=getVideo  (Referer + X-Requested-With)
    //  2. JSON.videoSource = master.txt (imzali HLS master playlist)
    //  master.m3u8 (securedLink) bazi durumlarda 403 verirken master.txt + XHR basligi calisir.
    private suspend fun resolveFirePlayer(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val hash = embedUrl.substringAfter("/video/").trimEnd('/').substringBefore('?')
            if (hash.isBlank()) return false
            val base = embedUrl.substringBefore("/video/")
            val apiUrl =
                "$base/player/index.php?data=$hash&do=getVideo"

            val response = app.post(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*"
                ),
                referer = embedUrl
            ).text

            val json = runCatching { org.json.JSONObject(response) }.getOrNull()
                ?: return false
            val sourceUrl = json.optString("videoSource").replace("\\/", "/")
                .takeIf { it.startsWith("http") }
                ?: json.optString("securedLink").replace("\\/", "/")
                    .takeIf { it.startsWith("http") }
                ?: return false

            // Ana playlist'i KENDIMIZ cekip dogrulariz; koruma 200+"security error"
            // dondugunde ExoPlayer "malformed manifest" verir. Varyant playlist'ler
            // genelde korumasizdir -> kalite etiketleriyle dogrudan onlari veririz.
            val masterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to embedUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*"
            )
            // NOT: /m3/ varyant blob'lari kisa omurlu oldugundan yayinlanMAZ; tek link
            // olarak dogrulanmis master.txt verilir, oynatici XHR basligiyle ceker.
            runCatching {
                val probe = app.get(sourceUrl, headers = masterHeaders, referer = embedUrl).text
                if (!probe.startsWith("#EXTM3U")) {
                    Log.w(name, "Master dogrulamasi basarisiz, yine de deneniyor")
                }
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = sourceUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = base
                    quality = getQualityFromName("")
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                }
            )
            true
        } catch (error: Exception) {
            Log.w(name, "FirePlayer cozulemedi ($embedUrl): ${error.message}")
            false
        }
    }

    private fun ogMeta(document: Element, property: String): String? {
        return document.selectFirst("meta[property=$property]")?.attr("content")
            ?.trim()?.ifBlank { null }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val FIREPLAYER_HOST_REGEX = Regex("""movietube\d*\.[a-z]+""", RegexOption.IGNORE_CASE)

        private val STATIC_SLUGS = setOf(
            "korku-testi", "icerik-istek", "vip-uyelik", "arama-robotu", "iletisim",
            "hukuksal-sayfasi", "tum-diziler", "en-cok-izlenenler",
            "turkce-dublaj-filmler", "altyazili-filmler", "imdb-7-ve-uzeri-filmler",
            "izleme", "ulke", "yil", "oyuncular"
        )
    }
}
