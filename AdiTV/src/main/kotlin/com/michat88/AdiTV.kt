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
        
        val groupedChannels = mutableMapOf<String, MutableList<SearchResponse>>()
        
        var currentName = "Channel Tanpa Nama"
        var currentLogo = ""
        var currentGroup = "Lain-lain"
        
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("#EXTINF")) {
                currentName = trimmedLine.substringAfterLast(",").trim()
                
                val logoRegex = """tvg-logo="(.*?)"""".toRegex()
                currentLogo = logoRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""
                
                val groupRegex = """group-title="(.*?)"""".toRegex()
                currentGroup = groupRegex.find(trimmedLine)?.groupValues?.get(1) ?: "Lain-lain"
                
            } else if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                if (!groupedChannels.containsKey(currentGroup)) {
                    groupedChannels[currentGroup] = mutableListOf()
                }
                
                // MENGGUNAKAN ATURAN BARU: newLiveSearchResponse
                groupedChannels[currentGroup]?.add(
                    newLiveSearchResponse(
                        name = currentName,
                        url = trimmedLine,
                        type = TvType.Live
                    ) {
                        this.posterUrl = currentLogo
                        this.lang = "id"
                    }
                )
            }
        }

        val homePageLists = groupedChannels.map { (groupName, channels) ->
            HomePageList(groupName, channels)
        }

        // MENGGUNAKAN ATURAN BARU: newHomePageResponse
        return newHomePageResponse(homePageLists)
    }

    // Langkah 2: Mengatur data saat channel diklik
    override suspend fun load(url: String): LoadResponse {
        // MENGGUNAKAN ATURAN BARU: newLiveStreamLoadResponse
        return newLiveStreamLoadResponse(
            name = "Live TV",
            url = url,
            dataUrl = url
        ) {
            // Kosongkan atau tambahkan properties lain jika perlu
        }
    }

    // Langkah 3: Menarik link video untuk diputar di Video Player
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Deteksi tipe ekstensi video
        val linkType = when {
            data.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            data.contains(".m3u", ignoreCase = true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        // MENGGUNAKAN ATURAN BARU: newExtractorLink
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                type = linkType
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
