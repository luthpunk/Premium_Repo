package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID

class AdiTV : MainAPI() {
    override var name = "AdiTV"
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    // Data Class untuk parsing awal
    data class ChannelData(
        val name: String,
        val logo: String?,
        val group: String,
        val streamUrl: String,
        val userAgent: String?,
        val referer: String?,
        val drmKey: String?,
        val drmType: String?
    )

    // Data Class untuk dioper ke loadLinks (dalam bentuk JSON)
    data class StreamData(
        val url: String,
        val userAgent: String?,
        val referer: String?,
        val drmKey: String?,
        val drmType: String?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        try {
            val m3uText = app.get(mainUrl).text
            
            val channels = mutableListOf<ChannelData>()
            val lines = m3uText.lines()
            
            var currentName = ""
            var currentLogo = ""
            var currentGroup = "Lainnya"
            var currentUa = ""
            var currentReferer = ""
            var currentDrmKey = ""
            var currentDrmType = ""

            for (line in lines) {
                val cleanLine = line.trim()
                
                // Mengambil Header dan Kunci DRM
                if (cleanLine.startsWith("#EXTVLCOPT:http-user-agent=")) {
                    currentUa = cleanLine.substringAfter("=").trim()
                } else if (cleanLine.startsWith("#EXTVLCOPT:http-referrer=")) {
                    currentReferer = cleanLine.substringAfter("=").trim()
                } else if (cleanLine.startsWith("#KODIPROP:license_type=")) {
                    currentDrmType = cleanLine.substringAfter("=").trim()
                } else if (cleanLine.startsWith("#KODIPROP:license_key=")) {
                    currentDrmKey = cleanLine.substringAfter("=").trim()
                } else if (cleanLine.startsWith("#EXTINF")) {
                    val groupMatch = Regex("""group-title="([^"]+)"""").find(cleanLine)
                    currentGroup = groupMatch?.groupValues?.get(1) ?: currentGroup

                    val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(cleanLine)
                    currentLogo = logoMatch?.groupValues?.get(1) ?: ""

                    currentName = cleanLine.substringAfterLast(",").trim()
                } else if (cleanLine.startsWith("http")) {
                    channels.add(
                        ChannelData(
                            currentName, currentLogo, currentGroup, cleanLine, 
                            currentUa, currentReferer, currentDrmKey, currentDrmType
                        )
                    )
                    
                    // Reset atribut setelah channel masuk ke list (agar tidak bocor ke channel lain)
                    currentName = ""
                    currentLogo = ""
                    currentUa = ""
                    currentReferer = ""
                    currentDrmKey = ""
                    currentDrmType = ""
                }
            }

            val groupedChannels = channels.groupBy { it.group }

            val homePageLists = groupedChannels.toList().amap { (groupName, channelList) ->
                val searchResponses = channelList.amap { ch ->
                    // Bungkus semua data pelengkap ke dalam format JSON
                    val streamData = StreamData(
                        url = ch.streamUrl,
                        userAgent = ch.userAgent,
                        referer = ch.referer,
                        drmKey = ch.drmKey,
                        drmType = ch.drmType
                    )
                    
                    newLiveSearchResponse(
                        name = ch.name,
                        url = streamData.toJson(), // Kita mengirim String JSON alih-alih URL mentah
                        type = TvType.Live
                    ) {
                        this.posterUrl = ch.logo
                    }
                }
                HomePageList(name = groupName, list = searchResponses)
            }

            return newHomePageResponse(homePageLists)

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // parameter 'url' di sini sebenarnya adalah String JSON dari StreamData
        return newLiveStreamLoadResponse(
            name = "Live Stream",
            url = url,
            dataUrl = url 
        ) {
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Buka kembali bungkusan JSON yang dikirim dari halaman utama
        val streamData = parseJson<StreamData>(data)
        val targetUrl = streamData.url

        val linkType = when {
            targetUrl.contains(".mpd") -> ExtractorLinkType.DASH
            targetUrl.contains(".m3u") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        // Terapkan Headers (User-Agent & Referer) untuk membasmi Error 2004
        val mapHeaders = mutableMapOf<String, String>()
        if (!streamData.userAgent.isNullOrBlank()) mapHeaders["User-Agent"] = streamData.userAgent
        if (!streamData.referer.isNullOrBlank()) mapHeaders["Referer"] = streamData.referer

        // Jika ada Kunci DRM, gunakan newDrmExtractorLink untuk membasmi Blank Hitam
        if (!streamData.drmKey.isNullOrBlank()) {
            val isWidevine = streamData.drmType?.contains("widevine", ignoreCase = true) == true
            val drmUuid = if (isWidevine) WIDEVINE_UUID else CLEARKEY_UUID

            var extractedLicenseUrl: String? = null
            var extractedKid: String? = null
            var extractedKey: String? = null

            // Kunci bisa berupa URL, atau format KID:KEY
            if (streamData.drmKey.startsWith("http")) {
                extractedLicenseUrl = streamData.drmKey
            } else if (streamData.drmKey.contains(":")) {
                val parts = streamData.drmKey.split(":")
                extractedKid = parts.getOrNull(0)
                extractedKey = parts.getOrNull(1)
            } else {
                extractedKey = streamData.drmKey
            }

            callback.invoke(
                newDrmExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = targetUrl,
                    type = linkType,
                    uuid = drmUuid
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapHeaders
                    this.kid = extractedKid
                    this.key = extractedKey
                    this.licenseUrl = extractedLicenseUrl
                }
            )
        } else {
            // Jika tidak ada DRM, gunakan link standar
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = targetUrl,
                    type = linkType
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapHeaders
                }
            )
        }
        
        return true
    }
}
