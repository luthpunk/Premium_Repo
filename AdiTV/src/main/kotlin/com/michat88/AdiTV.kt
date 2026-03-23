package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class AdiTV : MainAPI() {
    // Info dasar Provider
    override var name = "AdiTV"
    // Kita gunakan link RAW dari github agar teks m3u bisa dibaca langsung
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    // Data class bantuan untuk menyimpan info setiap channel
    data class ChannelData(
        val name: String,
        val logo: String,
        val group: String,
        val streamUrl: String
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        try {
            // 1. Mengambil teks M3U dari Github
            val m3uText = app.get(mainUrl).text
            
            // 2. Memproses teks M3U
            val channels = mutableListOf<ChannelData>()
            val lines = m3uText.lines()
            
            var currentName = ""
            var currentLogo = ""
            var currentGroup = "Lainnya"

            for (line in lines) {
                val cleanLine = line.trim()
                if (cleanLine.startsWith("#EXTINF")) {
                    // Ekstrak nama grup (group-title="Nama Grup")
                    val groupMatch = Regex("""group-title="([^"]+)"""").find(cleanLine)
                    currentGroup = groupMatch?.groupValues?.get(1) ?: "Lainnya"

                    // Ekstrak logo (tvg-logo="Link Logo")
                    val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(cleanLine)
                    currentLogo = logoMatch?.groupValues?.get(1) ?: ""

                    // Ekstrak nama channel (ada di akhir baris setelah koma)
                    currentName = cleanLine.substringAfterLast(",").trim()
                } else if (cleanLine.startsWith("http")) {
                    // Jika baris dimulai dengan http, itu adalah link stream-nya
                    channels.add(ChannelData(currentName, currentLogo, currentGroup, cleanLine))
                }
            }

            // 3. Mengelompokkan channel berdasarkan Group
            val groupedChannels = channels.groupBy { it.group }

            // 4. Memasukkan data ke tampilan Homepage Cloudstream
            val homePageLists = groupedChannels.map { (groupName, channelList) ->
                val searchResponses = channelList.map { ch ->
                    LiveSearchResponse(
                        name = ch.name,
                        url = ch.streamUrl, // Kita jadikan stream URL sebagai URL utama
                        apiName = this.name,
                        type = TvType.Live,
                        posterUrl = ch.logo
                    )
                }
                HomePageList(name = groupName, list = searchResponses)
            }

            return HomePageResponse(homePageLists)

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Dipanggil saat user mengklik salah satu channel di halaman utama
    override suspend fun load(url: String): LoadResponse {
        // Cloudstream akan membuat halaman detail, kita langsung oper URL stream-nya
        return LiveStreamLoadResponse(
            name = "Live Stream",
            url = url,
            apiName = this.name,
            dataUrl = url
        )
    }

    // Dipanggil saat user menekan tombol "Play" untuk mengekstrak link video asli
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cek apakah link berakhiran .m3u8
        val isM3u8 = data.contains(".m3u8") || data.contains(".m3u")
        val isDash = data.contains(".mpd")

        // Mempersiapkan link agar bisa diputar di player
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = "",
                quality = Qualities.Unknown.value,
                type = if (isDash) com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH 
                       else if (isM3u8) com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 
                       else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
