package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class XHamsterProvider : MainAPI() {
    // 1. Konfigurasi Dasar Plugin
    override var mainUrl = "https://xhamster.com"
    override var name = "xHamster"
    override val supportedTypes = setOf(TvType.NSFW) // Menandakan ini adalah konten dewasa
    override var lang = "en"
    override val hasMainPage = true

    // 2. Mendefinisikan tab yang muncul di halaman beranda CloudStream
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending"
    )

    // 3. Fungsi untuk mengambil dan memproses halaman utama
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Mengunduh HTML dari website
        val document = app.get(request.data).document
        val homeItems = mutableListOf<SearchResponse>()

        // Mengambil semua elemen tag <a> yang membungkus thumbnail video
        document.select("a.mobile-thumb-player-container").forEach { element ->
            // Mengambil judul dari atribut 'aria-label' dan link dari atribut 'href'
            val title = element.attr("aria-label")
            val url = element.attr("href")
            
            // Mencari gambar thumbnail (poster). 
            val imgElement = element.selectFirst("img.thumb-image-container__no-lazy-thumb, img.thumb-image-container__lazy-thumb")
            val posterUrl = imgElement?.attr("src")

            // Jika judul dan url tersedia, kita masukkan ke dalam daftar hasil
            if (title.isNotBlank() && url.isNotBlank()) {
                
                // MENGGUNAKAN CARA BARU: Builder function yang rapi!
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

        // Mengembalikan daftar video ke CloudStream untuk ditampilkan
        return newHomePageResponse(
            name = request.name,
            list = homeItems
        )
    }

    // Fungsi load() untuk memuat detail video (Akan kita kerjakan selanjutnya)
    override suspend fun load(url: String): LoadResponse? {
        // TODO: Kita butuh HTML dari halaman detail video untuk mengerjakan bagian ini
        return super.load(url)
    }
}
