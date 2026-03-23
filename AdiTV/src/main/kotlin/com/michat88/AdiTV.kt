package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI

// Kapsul JSON Super Lengkap untuk membawa semua Rahasia Channel
data class ChannelData(
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val userAgent: String,
    val referer: String,
    val licenseKey: String
)

class AdiTVProvider : MainAPI() {
    override var name = "AdiTV"
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    override val hasMainPage = true
    
    // Kita aktifkan dukungan untuk Film dan Live TV
    override val supportedTypes = setOf(TvType.Live, TvType.Movie)

    /**
     * LANGKAH 1: SUPER PARSER (Membongkar M3U Premium)
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val m3uText = app.get(mainUrl).text
        val channelsByGroup = mutableMapOf<String, MutableList<SearchResponse>>()
        
        // Variabel penampung data per channel
        var currentName = ""
        var currentLogo = ""
        var currentGroup = "Lainnya"
        var currentUserAgent = ""
        var currentReferer = ""
        var currentLicense = ""

        val lines = m3uText.split("\n")
        for (line in lines) {
            val trimmedLine = line.trim()
            
            // 1. Ekstrak Info Dasar
            if (trimmedLine.startsWith("#EXTINF")) {
                val logoRegex = """tvg-logo="([^"]+)"""".toRegex()
                val groupRegex = """group-title="([^"]+)"""".toRegex()
                
                currentLogo = logoRegex.find(trimmedLine)?.groupValues?.get(1) ?: currentLogo
                currentGroup = groupRegex.find(trimmedLine)?.groupValues?.get(1) ?: currentGroup
                currentName = trimmedLine.substringAfterLast(",").trim()
            } 
            // 2. Ekstrak User-Agent Khusus
            else if (trimmedLine.startsWith("#EXTVLCOPT:http-user-agent=")) {
                currentUserAgent = trimmedLine.substringAfter("=").trim()
            } 
            // 3. Ekstrak Referer Khusus
            else if (trimmedLine.startsWith("#EXTVLCOPT:http-referrer=")) {
                currentReferer = trimmedLine.substringAfter("=").trim()
            } 
            // 4. Ekstrak Kunci DRM
            else if (trimmedLine.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
                currentLicense = trimmedLine.substringAfter("=").trim()
            } 
            // 5. Eksekusi URL
            else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                
                // Bungkus semua data ke JSON
                val channelDataJSON = ChannelData(
                    name = currentName,
                    url = trimmedLine,
                    logo = currentLogo,
                    group = currentGroup,
                    userAgent = currentUserAgent,
                    referer = currentReferer,
                    licenseKey = currentLicense
                ).toJson()

                // Pisahkan VOD (Film) dan Live TV
                val isVod = currentGroup.equals("VOD-Movie", true)
                val channel = if (isVod) {
                    newMovieSearchResponse(currentName, channelDataJSON, TvType.Movie) {
                        this.posterUrl = currentLogo
                    }
                } else {
                    newLiveSearchResponse(currentName, channelDataJSON, TvType.Live) {
                        this.posterUrl = currentLogo
                    }
                }

                if (!channelsByGroup.containsKey(currentGroup)) {
                    channelsByGroup[currentGroup] = mutableListOf()
                }
                channelsByGroup[currentGroup]?.add(channel)

                // Reset semua variabel untuk channel selanjutnya agar tidak bocor/tertukar!
                currentName = ""
                currentLogo = ""
                currentGroup = "Lainnya" 
                currentUserAgent = ""
                currentReferer = ""
                currentLicense = ""
            }
        }

        val homeLists = channelsByGroup.map { (groupName, list) ->
            HomePageList(groupName, list)
        }

        return newHomePageResponse(homeLists)
    }

    /**
     * LANGKAH 2: Mengatur UI Halaman Pemutar (Live TV / Movie)
     */
    override suspend fun load(url: String): LoadResponse {
        val data = tryParseJson<ChannelData>(url)
        val streamUrl = data?.url ?: url
        val channelName = data?.name ?: "Stream"
        val channelLogo = data?.logo
        val channelGroup = data?.group ?: "Lainnya"
        
        val isVod = channelGroup.equals("VOD-Movie", true)

        if (isVod) {
            return newMovieLoadResponse(channelName, url, TvType.Movie, streamUrl) {
                this.posterUrl = channelLogo 
                this.plot = "🎬 Film: $channelName\n📂 Kategori: $channelGroup\n\nSelamat menonton!"
            }
        } else {
            return newLiveStreamLoadResponse(channelName, url, streamUrl) {
                this.posterUrl = channelLogo 
                this.plot = "📺 Menyiarkan: $channelName\n📂 Kategori: $channelGroup\n\nSuper Parser Mode Aktif"
            }
        }
    }

    /**
     * LANGKAH 3: DYNAMIC INJECTOR (Penyamaran Sempurna)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val parsedData = tryParseJson<ChannelData>(data)
        val streamUrl = parsedData?.url ?: data

        // 1. Tentukan User-Agent (Gunakan bawaan playlist, jika kosong pakai Chrome)
        val userAgent = if (parsedData?.userAgent.isNullOrEmpty()) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        } else {
            parsedData!!.userAgent
        }

        // 2. Tentukan Header Dasar
        val customHeaders = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        // 3. Suntikkan Referer & Origin (Sangat penting agar tidak Error 2004 / 403)
        if (!parsedData?.referer.isNullOrEmpty()) {
            customHeaders["Referer"] = parsedData!!.referer
            customHeaders["Origin"] = parsedData.referer.trimEnd('/')
        } else {
            // Jika kosong, kita buat referer otomatis dari URL siarannya
            val domain = try {
                val uri = URI(streamUrl)
                "${uri.scheme}://${uri.host}/"
            } catch (e: Exception) { "" }
            
            if (domain.isNotEmpty()) {
                customHeaders["Referer"] = domain
                customHeaders["Origin"] = domain.trimEnd('/')
            }
        }

        // 4. Deteksi Tipe Video
        val streamType = when {
            streamUrl.contains(".mpd") -> ExtractorLinkType.DASH
            streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        // ==========================================
        // HYBRID INJECTOR
        // ==========================================
        
        // Jalur 1: Khusus Live TV M3U8 (Suntik Native ExoPlayer & Filter M3u8Helper)
        if (streamType == ExtractorLinkType.M3U8 && parsedData?.group != "VOD-Movie") {
            
            // Server Utama: Native ExoPlayer (Anti Error 2004, sangat cocok untuk siaran yang diproteksi token!)
            callback.invoke(
                newExtractorLink(
                    source = "Native",
                    name = "Auto HD (Native Player)",
                    url = streamUrl,
                    type = streamType
                ) {
                    this.headers = customHeaders
                    this.quality = Qualities.P1080.value // Paksa 1080p agar tidak nyasar ke Trick-play 32x18!
                }
            )

            // Server Cadangan: M3u8Helper (Untuk siaran yang murni HLS, anti 32x18)
            try {
                M3u8Helper.generateM3u8(
                    source = "Filter",
                    streamUrl = streamUrl,
                    referer = "",
                    headers = customHeaders
                ).forEach { link ->
                    if (!link.name.contains("32x18") && !link.name.contains("Trick")) {
                        callback.invoke(link)
                    }
                }
            } catch (e: Exception) {}
        } 
        
        // Jalur 2: Khusus DASH (.mpd) atau Film (VOD)
        else {
            callback.invoke(
                newExtractorLink(
                    source = "Native",
                    name = if (streamType == ExtractorLinkType.DASH) "DASH Stream" else "Direct Video",
                    url = streamUrl,
                    type = streamType
                ) {
                    this.headers = customHeaders
                    this.quality = Qualities.P1080.value
                }
            )
        }
        
        return true
    }
}
