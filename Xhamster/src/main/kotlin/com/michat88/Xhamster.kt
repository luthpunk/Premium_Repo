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

    // 4. Memuat detail video ketika diklik dari beranda
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Mengambil data dari tag Meta Open Graph untuk hasil yang akurat
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        // Mengambil teks dari semua elemen <a> yang mengarah ke kategori
        val tags = document.select("a[href^=https://xhamster.com/categories/] span").map { it.text() }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url // url ini akan dilempar ke fungsi loadLinks
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // 5. Mengambil link video dan subtitle untuk diputar
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Mengunduh ulang HTML dari halaman video
        val document = app.get(data).document
        val html = document.html()

        // MENCARI LINK VIDEO
        // Prioritas Utama: Mengambil link M3U8 Master Playlist dari tag preload
        val m3u8Url = document.selectFirst("link[rel=preload][as=fetch][href*=.m3u8]")?.attr("href")
        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = m3u8Url,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value, // HLS akan otomatis menyesuaikan resolusi
                    isM3u8 = true
                )
            )
        } else {
            // Rencana Cadangan: Mengambil link MP4 langsung jika M3U8 gagal dimuat
            val mp4Url = document.selectFirst("video.video_container__no-script-video")?.attr("src")
            if (mp4Url != null) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name Fallback",
                        url = mp4Url,
                        referer = mainUrl,
                        quality = Qualities.P480.value,
                        isM3u8 = false
                    )
                )
            }
        }

        // MENCARI LINK SUBTITLE
        // JSON format asli: "label":"English (auto-generated)","urls":{"vtt":"https:\/\/..."}
        val subtitleRegex = """"label":"([^"]+)","urls":\{"vtt":"([^"]+)"""".toRegex()
        
        subtitleRegex.findAll(html).forEach { matchResult ->
            val langLabel = matchResult.groupValues[1]
            // Website memakai \/ untuk slash di JSON, jadi kita bersihkan dulu
            val vttUrl = matchResult.groupValues[2].replace("\\/", "/")

            subtitleCallback.invoke(
                SubtitleFile(
                    lang = langLabel,
                    url = vttUrl
                )
            )
        }

        return true
    }
}
