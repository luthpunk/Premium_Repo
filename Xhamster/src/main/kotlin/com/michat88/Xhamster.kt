package com.michat88

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document

data class XhVideo(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("pageURL") val pageURL: String? = null,
    @JsonProperty("thumbURL") val thumbURL: String? = null
)

data class XhSuggest(
    @JsonProperty("plainText") val plainText: String? = null,
    @JsonProperty("link") val link: String? = null
)

class Xhamster : MainAPI() {
    override var mainUrl = "https://xhamster.com"
    override var name = "xHamster"
    override val supportedTypes = setOf(TvType.NSFW) 
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true 

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending"
    )

    private fun extractVideos(document: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        document.select("a.video-thumb, a.thumb-image-container, a.mobile-thumb-player-container").forEach { element ->
            val url = element.attr("href")
            val img = element.selectFirst("img")
            
            // 1. MENGATASI LAZY-LOAD: Mencari link asli dari dalam tag <noscript>
            var posterUrl = element.selectFirst("noscript")?.html()?.let {
                Regex("""src="([^"]+)"""").find(it)?.groupValues?.get(1)
            }
            
            // 2. Jika tidak ada di noscript, cari di atribut alternatif
            if (posterUrl.isNullOrBlank()) {
                posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src")
                    ?: ""
            }

            // Hindari gambar transparan (base64)
            if (posterUrl != null && posterUrl.contains("data:image")) {
                posterUrl = ""
            }

            val title = element.attr("aria-label").ifBlank { img?.attr("alt") ?: "" }

            if (title.isNotBlank() && url.isNotBlank() && url.contains("/videos/")) {
                items.add(
                    newMovieSearchResponse(
                        name = title,
                        url = url,
                        type = TvType.NSFW
                    ) {
                        this.posterUrl = posterUrl
                        this.posterHeaders = mapOf("referer" to mainUrl)
                    }
                )
            }
        }
        return items
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // PERBAIKAN PAGINASI: Agar scroll ke bawah memunculkan halaman 2, 3, dst.
        val pageUrl = if (page == 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/$page"
        }

        val document = app.get(pageUrl, headers = headers).document
        val items = extractVideos(document)

        return newHomePageResponse(
            name = request.name,
            list = items,
            hasNext = items.isNotEmpty() // Beri tahu aplikasi untuk terus lanjut selama masih ada video
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("$mainUrl/search/$query?page=$page", headers = headers).document
        val searchItems = extractVideos(document)
        return newSearchResponseList(
            list = searchItems,
            hasNext = searchItems.isNotEmpty() 
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/api/front/search/suggest?searchValue=$query&searchScope=video&orientation=straight"
        val response = app.get(url, headers = headers).text
        
        val suggestions = tryParseJson<Array<XhSuggest>>(response) ?: return emptyList()

        return suggestions.mapNotNull { suggest ->
            val title = suggest.plainText ?: return@mapNotNull null
            val link = suggest.link ?: return@mapNotNull null
            
            newMovieSearchResponse(
                name = title,
                url = link,
                type = TvType.NSFW
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val html = document.html()

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = document.select("a[href^=https://xhamster.com/categories/] span").map { it.text() }

        val recommendations = mutableListOf<SearchResponse>()
        val videoPropsRaw = html.substringAfter("\"videoThumbProps\":[", "")
        if (videoPropsRaw.isNotBlank()) {
            val cleanJson = "[" + videoPropsRaw.substringBefore("],\"dropdownType\"") + "]"
            
            val videoList = tryParseJson<Array<XhVideo>>(cleanJson)
            
            videoList?.forEach { video: XhVideo ->
                val vTitle = video.title
                val vUrl = video.pageURL
                val vThumb = video.thumbURL
                
                if (!vTitle.isNullOrBlank() && !vUrl.isNullOrBlank()) {
                    recommendations.add(
                        newMovieSearchResponse(
                            name = vTitle,
                            url = vUrl,
                            type = TvType.NSFW
                        ) {
                            this.posterUrl = vThumb
                            this.posterHeaders = mapOf("referer" to mainUrl)
                        }
                    )
                }
            }
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url 
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.posterHeaders = mapOf("referer" to mainUrl)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val html = document.html()

        val m3u8Url = document.selectFirst("link[rel=preload][as=fetch][href*=.m3u8]")?.attr("href")
        if (m3u8Url != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            val mp4Url = document.selectFirst("video.video_container__no-script-video")?.attr("src")
            if (mp4Url != null) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Fallback",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P480.value
                    }
                )
            }
        }

        val subtitleRegex = """"label":"([^"]+)","urls":\{"vtt":"([^"]+)"""".toRegex()
        subtitleRegex.findAll(html).forEach { matchResult ->
            val langLabel = matchResult.groupValues[1]
            val vttUrl = matchResult.groupValues[2].replace("\\/", "/")
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = langLabel,
                    url = vttUrl
                ) {}
            )
        }

        return true
    }
}
