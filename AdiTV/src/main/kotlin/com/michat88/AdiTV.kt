package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI

// Kapsul JSON untuk mempertahankan info Channel di Player
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
            this.plot = "📺 Menyiarkan: $channelName\n📂 Kategori: $channelGroup\n\nEkstensi AdiTV - Native Player"
        }
    }

    /**
     * Langkah 3: NATIVE PLAYER + FORCED 720p + DYNAMIC HEADERS
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val streamUrl = tryParseJson<ChannelData>(data)?.url ?: data

        // Mengambil domain dasar dari URL untuk dijadikan surat pengantar (Referer)
        val domain = try {
            val uri = URI(streamUrl)
            "${uri.scheme}://${uri.host}/"
        } catch (e: Exception) {
            ""
        }

        // Identitas Penyamaran Super Lengkap!
        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Origin" to if (domain.isNotEmpty()) domain.dropLast(1) else "",
            "Referer" to domain
        )

        // Deteksi Tipe Video
        val streamType = when {
            streamUrl.contains(".mpd") -> ExtractorLinkType.DASH
            streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        // NATIVE INJECTION! Kita menyerahkan segalanya ke ExoPlayer Bawaan Android
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Live TV (Native HD)",
                url = streamUrl,
                type = streamType
            ) {
                this.headers = customHeaders
                
                // MAGIC FIX: Memaksa Cloudstream langsung meminta resolusi 720p. 
                // Ini akan mencegah ExoPlayer memutar layar Blank/Hitam 32x18!
                this.quality = Qualities.P720.value 
            }
        )
        
        return true
    }
}
