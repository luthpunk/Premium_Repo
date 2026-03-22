package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.io.File

class AdiTV : MainAPI() {
    override var mainUrl = "AdiTV"
    override var name = "AdiTV"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val channels = mutableListOf<LiveTvSearchResponse>()
        
        try {
            // Jalur absolut ke folder Music di memori internal Android
            val file = File("/storage/emulated/0/Music/playlist_aktif.m3u")
            
            if (file.exists()) {
                val response = file.readText()
                
                // Regex super presisi khusus untuk format M3U milikmu
                val regex = Regex("""#EXTINF:.*?(?:tvg-logo="(.*?)")?.*?,(.*?)\n(http[^\n]+)""")
                val matches = regex.findAll(response)

                for (match in matches) {
                    val posterUrl = match.groupValues[1].ifEmpty { null }
                    val channelName = match.groupValues[2].trim()
                    val streamUrl = match.groupValues[3].trim()

                    channels.add(
                        LiveTvSearchResponse(
                            name = channelName,
                            url = streamUrl,
                            apiName = this.name,
                            type = TvType.Live,
                            posterUrl = posterUrl,
                            lang = "id"
                        )
                    )
                }
            } else {
                throw ErrorLoadingException("Bro, file playlist_aktif.m3u tidak ditemukan di folder Music!")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse("Live TV", channels)
    }

    override suspend fun load(url: String): LoadResponse {
        return LiveStreamLoadResponse(
            name = "Live Stream",
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = null
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Menggunakan M3u8Helper dari CloudStream untuk memutar link HLS dengan aman
        M3u8Helper.generateM3u8(
            this.name,
            data,
            ""
        ).forEach(callback)

        return true
    }
}
