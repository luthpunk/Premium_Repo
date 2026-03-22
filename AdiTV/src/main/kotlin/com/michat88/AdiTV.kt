package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AdiTV : MainAPI() {
    // Nama plugin yang akan muncul di aplikasi
    override var name = "AdiTV" 
    
    // Link RAW dari file M3U di GitHub-mu
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u" 
    
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // Langkah 1: Mengambil data M3U dan menampilkannya di Halaman Utama
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Mengunduh isi teks dari file M3U
        val m3uData = app.get(mainUrl).text
        
        val channels = mutableListOf<LiveSearchResponse>()
        val lines = m3uData.lines()
        
        var currentName = "Channel Tanpa Nama"
        var currentLogo = ""
        
        // Membaca file baris demi baris
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("#EXTINF")) {
                // Mengambil nama channel (biasanya setelah tanda koma terakhir)
                currentName = trimmedLine.substringAfterLast(",").trim()
                
                // Mengambil logo jika ada (mencari teks tvg-logo="...")
                val logoRegex = """tvg-logo="(.*?)"""".toRegex()
                val logoMatch = logoRegex.find(trimmedLine)
                currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                
            } else if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                // Jika bukan tag #EXTINF dan bukan baris kosong, berarti ini link videonya
                channels.add(
                    LiveSearchResponse(
                        name = currentName,
                        url = trimmedLine, // Link video disimpan di 'url'
                        apiName = this@AdiTV.name,
                        type = TvType.Live,
                        posterUrl = currentLogo,
                        lang = "id" // Bahasa Indonesia
                    )
                )
            }
        }

        // Menampilkan daftar channel di aplikasi
        return HomePageResponse(listOf(HomePageList("Daftar Channel TV", channels)))
    }

    // Langkah 2: Mengatur halaman saat channel diklik
    override suspend fun load(url: String): LoadResponse {
        return LiveStreamLoadResponse(
            name = "Live TV",
            url = url,
            apiName = this.name,
            dataUrl = url
        )
    }

    // Langkah 3: Menarik link video untuk diputar di Video Player
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' berisi link video dari channel yang dipilih
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = "",
                quality = Qualities.Unknown.value,
                isM3u8 = data.contains(".m3u") // Deteksi apakah ini file M3U8
            )
        )
        return true
    }
}
