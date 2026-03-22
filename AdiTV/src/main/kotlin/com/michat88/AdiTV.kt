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

    // Langkah 1: Mengambil data M3U dan mengelompokkannya
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val m3uData = app.get(mainUrl).text
        val lines = m3uData.lines()
        
        // Membuat wadah untuk mengelompokkan channel berdasarkan kategori (group-title)
        val groupedChannels = mutableMapOf<String, MutableList<LiveSearchResponse>>()
        
        var currentName = "Channel Tanpa Nama"
        var currentLogo = ""
        var currentGroup = "Lain-lain" // Kategori default jika tidak ada group-title
        
        // Membaca file baris demi baris
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("#EXTINF")) {
                // 1. Mengambil nama channel (setelah koma terakhir)
                currentName = trimmedLine.substringAfterLast(",").trim()
                
                // 2. Mengambil logo (tvg-logo)
                val logoRegex = """tvg-logo="(.*?)"""".toRegex()
                currentLogo = logoRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""
                
                // 3. Mengambil kategori (group-title)
                val groupRegex = """group-title="(.*?)"""".toRegex()
                currentGroup = groupRegex.find(trimmedLine)?.groupValues?.get(1) ?: "Lain-lain"
                
            } else if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                // Menyiapkan grup jika belum ada
                if (!groupedChannels.containsKey(currentGroup)) {
                    groupedChannels[currentGroup] = mutableListOf()
                }
                
                // Memasukkan channel ke dalam grup yang sesuai
                groupedChannels[currentGroup]?.add(
                    LiveSearchResponse(
                        name = currentName,
                        url = trimmedLine,
                        apiName = this@AdiTV.name,
                        type = TvType.Live,
                        posterUrl = currentLogo,
                        lang = "id"
                    )
                )
            }
        }

        // Mengubah Map/Grup tadi menjadi daftar HomePageList yang diminta Cloudstream
        val homePageLists = groupedChannels.map { (groupName, channels) ->
            HomePageList(groupName, channels)
        }

        // Menampilkan hasil di Halaman Utama
        return HomePageResponse(homePageLists)
    }

    // Langkah 2: Mengatur data saat channel diklik
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
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = "",
                quality = Qualities.Unknown.value,
                // Ini bagian yang diperbarui: Mendeteksi M3U8 dan MPD (DASH)
                isM3u8 = data.contains(".m3u", ignoreCase = true),
                isDash = data.contains(".mpd", ignoreCase = true) 
            )
        )
        return true
    }
}
