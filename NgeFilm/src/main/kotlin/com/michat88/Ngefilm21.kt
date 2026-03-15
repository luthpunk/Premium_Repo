package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.coroutineScope

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new32.ngefilm.site" 
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val UA_BROWSER = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private fun Element.getImageAttr(): String? {
        var url = this.attr("data-src").ifEmpty { this.attr("src") }
        if (url.isEmpty()) {
            val srcset = this.attr("srcset")
            if (srcset.isNotEmpty()) {
                url = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: ""
            }
        }
        return if (url.isNotEmpty()) {
            httpsify(url).replace(Regex("-\\d+x\\d+"), "")
        } else null
    }

    private val categories = listOf(
        Pair("Upload Terbaru", ""), 
        Pair("Indonesia Movie", "/country/indonesia"),
        Pair("Indonesia Series", "/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=indonesia&quality="),
        Pair("Drakor", "/?s=&search=advanced&post_type=tv&index=&orderby=&genre=drama&movieyear=&country=korea&quality="),
        Pair("VivaMax", "/country/philippines"),
        Pair("Movies", "/country/canada"),
        Pair("Ahok Movie", "/country/china")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homeItems = mutableListOf<HomePageList>()
        for (cat in categories) {
            val (title, urlPath) = cat
            val finalUrl = if (urlPath.isEmpty()) {
                "$mainUrl/page/$page/"
            } else if (urlPath.contains("?")) {
                val split = urlPath.split("?")
                "$mainUrl/page/$page/?${split[1]}"
            } else {
                "$mainUrl$urlPath/page/$page/"
            }

            try {
                val document = app.get(finalUrl).document
                val items = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) homeItems.add(HomePageList(title, items))
            } catch (e: Exception) { }
        }
        return newHomePageResponse(homeItems, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: ""
        val qualityText = this.selectFirst(".gmr-quality-item")?.text()?.trim() ?: "HD"
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = this@toSearchResult.selectFirst(".content-thumbnail img")?.getImageAttr()
            addQuality(qualityText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
            .select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        val plotText = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim() 
            ?: document.selectFirst("div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
        val yearText = document.selectFirst(".gmr-moviedata a[href*='year']")?.text()?.toIntOrNull()
        val ratingText = document.selectFirst("[itemprop='ratingValue']")?.text()?.trim()
        val tagsList = document.select(".gmr-moviedata a[href*='genre']").map { it.text() }
        val actorsList = document.select("[itemprop='actors'] a").map { it.text() }
        val trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")

        val epElements = document.select(".gmr-listseries a").filter { it.attr("href").contains("/eps/") }
        val isSeries = epElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        if (isSeries) {
            val episodes = epElements.mapNotNull { 
                newEpisode(it.attr("href")) { 
                    this.name = it.attr("title").removePrefix("Permalink ke ")
                    this.episode = Regex("(\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) { 
                this.posterUrl = poster
                this.plot = plotText
                this.year = yearText
                this.score = Score.from10(ratingText)
                this.tags = tagsList
                this.actors = actorsList.map { ActorData(Actor(it)) }
                if (!trailerUrl.isNullOrEmpty()) this.trailers.add(TrailerData(trailerUrl, null, false))
            }
        } else {
            return newMovieLoadResponse(title, url, type, url) { 
                this.posterUrl = poster
                this.plot = plotText
                this.year = yearText
                this.score = Score.from10(ratingText)
                this.tags = tagsList
                this.actors = actorsList.map { ActorData(Actor(it)) }
                if (!trailerUrl.isNullOrEmpty()) this.trailers.add(TrailerData(trailerUrl, null, false))
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val playerLinks = document.select(".muvipro-player-tabs a").mapNotNull { it.attr("href") }.toMutableList()
        if (playerLinks.isEmpty()) playerLinks.add(data)

        // PROSES BERURUTAN (Sequential)
        for (playerUrl in playerLinks.distinct()) {
            try {
                val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                val pageContent = app.get(fixedUrl, headers = mapOf("User-Agent" to UA_BROWSER)).text 
                
                // Cari semua link HTTPS di halaman
                val allLinks = Regex("""(?i)(?:src|href)=["'](https?://[^"']+)["']""").findAll(pageContent).map { it.groupValues[1] }.toList()
                
                for (targetUrl in allLinks) {
                    // JIKA MENEMUKAN KELUARGA HANERIX / HGCLOUD
                    if (targetUrl.contains(Regex("""(?i)hglink|vibuxer|masukestin|cybervynx|niramirus|smoothpre|hgcloud|hanerix"""))) {
                        val isEmbed = targetUrl.contains("/embed/")
                        val videoId = targetUrl.split("/e/", "/embed/").last().substringBefore("?").trim('/')
                        
                        // --- TRIK JALAN PINTAS: PAKSA GANTI KE HANERIX.COM ---
                        val realDomain = "hanerix.com"
                        val directUrl = "https://$realDomain/${if (isEmbed) "embed" else "e"}/$videoId"
                        
                        extractHanerix(directUrl, realDomain, callback)
                    }
                }
            } catch (e: Exception) { }
        }
        return true
    }

    private suspend fun extractHanerix(url: String, domain: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Langsung ambil konten dari hanerix.com
            val response = app.get(url, headers = mapOf(
                "User-Agent" to UA_BROWSER,
                "Referer" to mainUrl, 
                "Origin" to "https://$domain",
                "Upgrade-Insecure-Requests" to "1"
            ))
            
            val unpackedJs = multiUnpack(response.text)
            
            // PANEN M3U8 (Cari hls4, hls3, atau hls2)
            var linkM3u8 = Regex("""["'](?:hls[234])["']\s*:\s*["']([^"']+)["']""").find(unpackedJs)?.groupValues?.get(1)
            
            // Fallback cari .m3u8 manual
            if (linkM3u8 == null) {
                linkM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)
            }

            if (linkM3u8 != null) {
                var finalM3u8 = linkM3u8.replace("\\/", "/")
                if (finalM3u8.startsWith("/")) finalM3u8 = "https://$domain$finalM3u8"
                 
                callback.invoke(
                    newExtractorLink(
                        "Hanerix Server",
                        "Hanerix Server",
                        finalM3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "User-Agent" to UA_BROWSER,
                            "Referer" to "https://$domain/",
                            "Origin" to "https://$domain"
                        )
                    }
                )
            }
        } catch (e: Exception) { }
    }

    private fun multiUnpack(html: String): String {
        var unpacked = html
        try {
            val packRegex = Regex("""\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            packRegex.findAll(html).forEach { match ->
                var p = match.groupValues[1]
                val a = match.groupValues[2].toInt()
                val c = match.groupValues[3].toInt()
                val k = match.groupValues[4].split("|")
                fun toBase(num: Int, base: Int): String {
                    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    var res = ""; var n = num; if (n == 0) return "0"
                    while (n > 0) { res = chars[n % base] + res; n /= base }
                    return res
                }
                for (i in c - 1 downTo 0) {
                    if (k.getOrNull(i)?.isNotEmpty() == true) {
                        p = p.replace(Regex("""\b${toBase(i, a)}\b"""), k[i])
                    }
                }
                unpacked += "\n" + p
            }
        } catch (e: Exception) {}
        return unpacked
    }
}
