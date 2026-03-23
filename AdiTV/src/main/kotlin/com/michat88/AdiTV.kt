package com.michat88

// Import semua kebutuhan utama dari MainAPI
import com.lagradost.cloudstream3.*
// Import semua alat dari ExtractorApi dan M3u8Helper
import com.lagradost.cloudstream3.utils.*

class AdiTVProvider : MainAPI() {
    override var name = "AdiTV"
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    /**
     * Langkah 1: Memuat Halaman Utama (sesuai aturan MainPageRequest baru di MainAPI)
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 'app' sudah otomatis dikenali oleh sistem Cloudstream
        val m3uText = app.get(mainUrl).text
        
        val channelsByGroup = mutableMapOf<String, MutableList<SearchResponse>>()
        
        var currentName = ""
        var currentLogo = ""
        var currentGroup = "Lainnya"

        val lines = m3uText.split("\n")
        for (line in lines) {
            val trimmedLine = line.trim()
            
            if (trimmedLine.startsWith("#EXTINF")) {
                val logoRegex = """tvg-logo="([^"]+)"""".toRegex()
                val groupRegex = """group-title="([^"]+)"""".toRegex()
                
                currentLogo = logoRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""
                
                val foundGroup = groupRegex.find(trimmedLine)?.groupValues?.get(1)
                if (!foundGroup.isNullOrBlank()) {
                    currentGroup = foundGroup
                }

                currentName = trimmedLine.substringAfterLast(",").trim()
            } 
            else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                // Menggunakan builder 'newLiveSearchResponse' yang disarankan
                val channel = newLiveSearchResponse(currentName, trimmedLine, TvType.Live) {
                    this.posterUrl = currentLogo
                }

                if (!channelsByGroup.containsKey(currentGroup)) {
                    channelsByGroup[currentGroup] = mutableListOf()
                }
                channelsByGroup[currentGroup]?.add(channel)

                currentName = ""
                currentLogo = ""
                currentGroup = "Lainnya" 
            }
        }

        val homeLists = channelsByGroup.map { (groupName, list) ->
            HomePageList(groupName, list)
        }

        // Menggunakan builder 'newHomePageResponse'
        return newHomePageResponse(homeLists)
    }

    /**
     * Langkah 2: Memuat detail stream
     */
    override suspend fun load(url: String): LoadResponse {
        // Menggunakan builder 'newLiveStreamLoadResponse'
        return newLiveStreamLoadResponse("Live Stream", url, url)
    }

    /**
     * Langkah 3: Load Links (Diperbaiki dengan M3u8Helper dan ExtractorLink yang benar)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        if (data.contains(".m3u8")) {
            // MANTAP! Kita menggunakan M3u8Helper dari referensi filemu
            // Ini akan otomatis mengekstrak semua resolusi (720p, 1080p, dll) dari M3U8
            M3u8Helper.generateM3u8(
                source = this.name,
                streamUrl = data,
                referer = ""
            ).forEach(callback)
            
        } else if (data.contains(".mpd")) {
            // Untuk format DASH (dari file ExtractorApi.kt)
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = data,
                    referer = "",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.DASH
                )
            )
        } else {
            // Untuk format video langsung lainnya
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = data,
                    referer = "",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        
        return true
    }
}
