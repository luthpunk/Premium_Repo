package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.get
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class AdiTVProvider : MainAPI() {
    // Nama plugin yang akan muncul di aplikasi
    override var name = "AdiTV"
    
    // URL mentah (raw) file M3U kamu di Github
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    
    // Memberitahu Cloudstream bahwa plugin ini punya halaman utama (Home)
    override val hasMainPage = true
    
    // Fokus pada tipe Live TV
    override val supportedTypes = setOf(TvType.Live)

    /**
     * Langkah 1: Memuat dan mengelompokkan daftar channel di Beranda
     */
    override suspend fun getMainPage(page: Int, requestPath: String?): HomePageResponse? {
        // Mengunduh isi teks dari file M3U di Github
        val m3uText = app.get(mainUrl).text
        
        // Tempat penyimpanan sementara channel berdasarkan Grup-nya (group-title)
        val channelsByGroup = mutableMapOf<String, MutableList<LiveSearchResponse>>()
        
        var currentName = ""
        var currentLogo = ""
        var currentGroup = "Lainnya" // Kategori default jika tidak ada nama grup

        // Membaca file baris demi baris
        val lines = m3uText.split("\n")
        for (line in lines) {
            val trimmedLine = line.trim()
            
            if (trimmedLine.startsWith("#EXTINF")) {
                // Regex untuk mengambil Logo dan Grup dari teks M3U
                val logoRegex = """tvg-logo="([^"]+)"""".toRegex()
                val groupRegex = """group-title="([^"]+)"""".toRegex()
                
                currentLogo = logoRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""
                
                // Cek jika ada group-title, kalau tidak ada biarkan tetap "Lainnya"
                val foundGroup = groupRegex.find(trimmedLine)?.groupValues?.get(1)
                if (!foundGroup.isNullOrBlank()) {
                    currentGroup = foundGroup
                }

                // Mengambil nama channel (teks setelah koma terakhir)
                currentName = trimmedLine.substringAfterLast(",").trim()
            } 
            else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                // Membuat data channel
                val channel = LiveSearchResponse(
                    name = currentName,
                    url = trimmedLine,
                    apiName = this@AdiTVProvider.name,
                    type = TvType.Live,
                    posterUrl = currentLogo
                )

                // Memasukkan channel ke dalam map sesuai dengan nama grupnya
                if (!channelsByGroup.containsKey(currentGroup)) {
                    channelsByGroup[currentGroup] = mutableListOf()
                }
                channelsByGroup[currentGroup]?.add(channel)

                // Bersihkan variabel untuk baris berikutnya
                currentName = ""
                currentLogo = ""
                currentGroup = "Lainnya" 
            }
        }

        // Mengubah Map (grup) menjadi daftar baris (HomePageList) untuk ditampilkan di Cloudstream
        val homeLists = channelsByGroup.map { (groupName, list) ->
            HomePageList(
                name = groupName,
                list = list
            )
        }

        return HomePageResponse(homeLists)
    }

    /**
     * Langkah 2: Mengatur halaman saat sebuah channel diklik
     */
    override suspend fun load(url: String): LoadResponse {
        return LiveStreamLoadResponse(
            name = "Live Stream",
            url = url,
            apiName = this.name,
            dataUrl = url
        )
    }

    /**
     * Langkah 3: Memberikan link ke Player dan mendeteksi format video (m3u8 / mpd)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Deteksi format video secara otomatis berdasarkan URL-nya
        val streamType = when {
            data.contains(".m3u8") -> ExtractorLinkType.M3U8
            data.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        // Mengirimkan link video ke pemutar Cloudstream
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = "",
                quality = Qualities.Unknown.value,
                type = streamType // Menerapkan format video hasil deteksi
            )
        )
        return true
    }
}
