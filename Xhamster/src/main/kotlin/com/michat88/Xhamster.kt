package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        // Menambahkan header standar agar situs tidak memblokir koneksi kita
        val document = app.get(
            request.data,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        ).document
        val homeItems = mutableListOf<SearchResponse>()

        // Memperluas selektor untuk menangkap versi Mobile maupun Desktop
        document.select("a.video-thumb, a.thumb-image-container, a.mobile-thumb-player-container").forEach { element ->
            val url = element.attr("href")
            
            // Mencari tag img secara umum tanpa mempedulikan nama class
            val img = element.selectFirst("img")
            
            // Mencari judul dari atribut aria-label, jika kosong ambil dari atribut alt pada gambar
            val title = element.attr("aria-label").ifBlank { img?.attr("alt") ?: "" }
            
            // Mengakali sistem "Lazy Load" yang sering menyembunyikan link gambar asli
            var posterUrl = img?.attr("src")
            if (posterUrl.isNullOrBlank() || posterUrl.contains("data:image")) {
                posterUrl = img?.attr("data-src") ?: img?.attr("srcset")?.substringBefore(" ") ?: ""
            }

            // Pastikan judul, url, dan poster ada, serta itu adalah halaman /videos/
            if (title.isNotBlank() && url.isNotBlank() && url.contains("/videos/")) {
                homeItems.add(
                    newMovieSearchResponse(
                        name = title,
                        url = url,
                        type = TvType.NSFW
                    ) {
                        this.posterUrl = posterUrl
                        // INI KUNCINYA: Mengirim header referer agar poster tidak di-block (Error 403)
                        this.posterHeaders = mapOf("referer" to mainUrl)
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

        // Mengambil data dari tag Meta Open Graph (Sangat akurat)
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
            // Tambahkan juga pada poster detail
            this.posterHeaders = mapOf("referer" to mainUrl)
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
