package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Data Kapsul JSON untuk mempertahankan info Channel
data class ChannelData(
    val name: String,
    val url: String,
    val logo: String,
    val group: String
)

class AdiTVProvider : MainAPI() {
    override var name = "AdiTV"
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    /**
     * Langkah 1: Memuat dan mengelompokkan daftar channel
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
                
                val channelDataJSON = ChannelData(currentName, trimmedLine, currentLogo, currentGroup).toJson()

                val channel = newLiveSearchResponse(currentName, channelDataJSON, TvType.Live) {
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

        return newHomePageResponse(homeLists)
    }

    /**
     * Langkah 2: Mengatur UI Halaman Pemutar
     */
    override suspend fun load(url: String): LoadResponse {
        val data = tryParseJson<ChannelData>(url)
        val streamUrl = data?.url ?: url
        val channelName = data?.name ?: "Live Stream"
        val channelLogo = data?.logo
        val channelGroup = data?.group ?: "Siaran Langsung"

        return newLiveStreamLoadResponse(channelName, url, streamUrl) {
            this.posterUrl = channelLogo 
            this.plot = "📺 Menyiarkan: $channelName\n📂 Kategori: $channelGroup"
        }
    }

    /**
     * Langkah 3: HYBRID INJECTOR (Menyuntikkan Multi-Metode ke Player)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val streamUrl = tryParseJson<ChannelData>(data)?.url ?: data

        // Headers Penyamaran mutlak untuk menembus server
        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        if (streamUrl.contains(".m3u8")) {
            
            // ==========================================
            // JALUR 1: M3U8 HELPER (Membasmi Trick-Play 32x18)
            // ==========================================
            try {
                M3u8Helper.generateM3u8(
                    source = "Filter", // Nama server
                    streamUrl = streamUrl,
                    referer = "",
                    headers = customHeaders
                ).forEach { link ->
                    // Menyaring track sampah (Trick-play 32x18 dll)
                    if (!link.name.contains("32x18") && !link.name.contains("Trick")) {
                        callback.invoke(link)
                    }
                }
            } catch (e: Exception) {
                // Abaikan jika M3u8Helper gagal membedah link (sering terjadi di server ber-token)
            }

            // ==========================================
            // JALUR 2: NATIVE EXOPLAYER (Membasmi Error 2004)
            // ==========================================
            // Ini akan jadi server cadangan (atau server utama jika Jalur 1 di-lock)
            callback.invoke(
                newExtractorLink(
                    source = "Native", // Nama Server di Player
                    name = "Auto (Native Player)",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = customHeaders
                    this.quality = Qualities.Unknown.value
                }
            )

        } else if (streamUrl.contains(".mpd")) {
            // ==========================================
            // JALUR 3: FORMAT DASH (.MPD)
            // ==========================================
            callback.invoke(
                newExtractorLink(
                    source = "Native",
                    name = "DASH Stream",
                    url = streamUrl,
                    type = ExtractorLinkType.DASH
                ) {
                    this.headers = customHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            // JALUR 4: Format Video Lainnya (mp4, mkv, dll)
            callback.invoke(
                newExtractorLink(
                    source = "Native",
                    name = "Direct Video",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = customHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        
        return true
    }
}
