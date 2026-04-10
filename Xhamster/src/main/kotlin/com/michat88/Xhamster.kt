package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class Xhamster : MainAPI() {
    override var mainUrl = "https://xhamster.com"
    override var name = "xHamster"
    override val supportedTypes = setOf(TvType.NSFW) 
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val homeItems = mutableListOf<SearchResponse>()

        document.select("a.mobile-thumb-player-container").forEach { element ->
            val title = element.attr("aria-label")
            val url = element.attr("href")
            val imgElement = element.selectFirst("img.thumb-image-container__no-lazy-thumb, img.thumb-image-container__lazy-thumb")
            val posterUrl = imgElement?.attr("src")

            if (title.isNotBlank() && url.isNotBlank()) {
                homeItems.add(
                    newMovieSearchResponse(
                        name = title,
                        url = url,
                        type = TvType.NSFW
                    ) {
                        this.posterUrl = posterUrl
                    }
                )
            }
        }

        return newHomePageResponse(
            name = request.name,
            list = homeItems
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select("a[href^=https://xhamster.com/categories/] span").map { it.text() }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url 
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()

        // MENCARI LINK VIDEO
        val m3u8Url = document.selectFirst("link[rel=preload][as=fetch][href*=.m3u8]")?.attr("href")
        if (m3u8Url != null) {
            // MENGGUNAKAN newExtractorLink
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
                // MENGGUNAKAN newExtractorLink
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

        // MENCARI LINK SUBTITLE
        val subtitleRegex = """"label":"([^"]+)","urls":\{"vtt":"([^"]+)"""".toRegex()
        
        subtitleRegex.findAll(html).forEach { matchResult ->
            val langLabel = matchResult.groupValues[1]
            val vttUrl = matchResult.groupValues[2].replace("\\/", "/")

            // MENGGUNAKAN newSubtitleFile
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
