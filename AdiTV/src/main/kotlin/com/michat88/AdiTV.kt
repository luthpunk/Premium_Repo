package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AdiTVProvider : MainAPI() {
    // Kita gunakan link raw dari GitHub agar mendapatkan format teks asli
    override var mainUrl = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/id.m3u"
    override var name = "AdiTV"
    override val hasMainPage = true
    override val hasQuickSearch = true
    
    // Wajib diatur ke Live karena ini siaran langsung
    override val supportedTypes = setOf(TvType.Live)

    // Cache untuk menyimpan hasil parsing agar tidak mendownload berulang kali
    private var cachedChannels = mutableListOf<LiveSearchResponse>()

    // Fungsi untuk mengunduh dan membaca isi file M3U
    private suspend fun fetchChannels(): List<LiveSearchResponse> {
        if (cachedChannels.isNotEmpty()) return cachedChannels

        try {
            // Mengunduh isi teks dari file id.m3u
            val response = app.get(mainUrl).text
            val lines = response.split("\n")
            
            var currentName = ""
            var currentLogo = ""

            for (line in lines) {
                val trimmed = line.trim()
                
                if (trimmed.startsWith("#EXTINF")) {
                    // Mengambil URL logo dari atribut tvg-logo="..."
                    val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(trimmed)
                    currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                    
                    // Mengambil nama channel yang letaknya setelah koma
                    currentName = trimmed.substringAfterLast(",").trim()
                } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    // Jika bukan #EXTINF dan bukan komentar, berarti ini link tayangan (m3u8)
                    // Menggunakan BUILDER yang benar dari MainAPI.kt
                    val channel = newLiveSearchResponse(
                        name = currentName,
                        url = trimmed,      // Simpan link m3u8 sebagai url
                        type = TvType.Live,
                        fix = false         // Jangan di-fix karena URL ini menuju luar mainUrl kita
                    ) {
                        this.posterUrl = currentLogo
                    }
                    cachedChannels.add(channel)
                    
                    // Reset untuk channel selanjutnya
                    currentName = ""
                    currentLogo = ""
                }
            }
        } catch (e: Exception) {
            // Error handling bisa ditambahkan di sini
            e.printStackTrace()
        }
        return cachedChannels
    }

    // Menampilkan daftar channel di beranda (Homepage)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val channels = fetchChannels()
        if (channels.isEmpty()) return null

        return newHomePageResponse(
            list = HomePageList(
                name = "Indonesia TV",
                list = channels,
                isHorizontalImages = true // Kartu akan ditampilkan secara horizontal
            ),
            hasNext = false
        )
    }

    // Fitur pencarian channel
    override suspend fun search(query: String): List<SearchResponse>? {
        val channels = fetchChannels()
        return channels.filter { 
            it.name.contains(query, ignoreCase = true) 
        }
    }

    // Memuat halaman detail ketika channel diklik
    override suspend fun load(url: String): LoadResponse? {
        val channels = fetchChannels()
        // Mencari channel yang URL-nya sesuai dengan yang diklik
        val channel = channels.find { it.url == url } ?: return null

        // Menggunakan BUILDER yang benar dari MainAPI.kt
        return newLiveStreamLoadResponse(
            name = channel.name,
            url = channel.url,
            dataUrl = channel.url // Kita passing URL tayangan ke dataUrl untuk dipakai di loadLinks
        ) {
            this.posterUrl = channel.posterUrl
            this.plot = "Menonton siaran langsung ${channel.name} gratis."
        }
    }

    // Mengeksekusi link video agar bisa diputar di player
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' berisi link M3U8 yang kita berikan di dataUrl pada fungsi load()
        
        // Menggunakan BUILDER yang benar dari ExtractorApi.kt
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Live Stream",
                url = data,
                type = ExtractorLinkType.M3U8 // Pastikan tipe M3U8 karena ini format IPTV
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
