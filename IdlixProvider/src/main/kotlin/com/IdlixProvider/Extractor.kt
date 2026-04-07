package com.IdlixProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack

class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val document = response.document
        val htmlContent = response.text

        // 1. Ambil Subtitle (Trik [Bahasa]https://...jpg)
        val subtitleRegex = """var\s+playerjsSubtitle\s*=\s*["'](.*?)["']""".toRegex()
        subtitleRegex.find(htmlContent)?.groupValues?.get(1)?.let { subStr ->
            val tracks = subStr.split(",")
            for (track in tracks) {
                val langMatch = """\[(.*?)\](.*)""".toRegex().find(track)
                if (langMatch != null) {
                    val lang = getLanguage(langMatch.groupValues[1])
                    val subUrl = langMatch.groupValues[2]
                    Log.d("adixtream", "Subtitle ketemu: $lang -> $subUrl")
                    subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                }
            }
        }

        // 2. Ambil Video dengan REGEX SAPU JAGAT
        document.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    // Membongkar sandi packer
                    val unpacked = getAndUnpack(data)
                    
                    // SAPU JAGAT: Ambil SEMUA teks yang berbentuk URL https:// di dalam tanda kutip
                    val urlRegex = """["'](https?://[^"']+)["']""".toRegex()
                    val matches = urlRegex.findAll(unpacked)
                    
                    for (match in matches) {
                        val rawUrl = match.groupValues[1]
                        
                        // FILTER: Buang URL gambar, subtitle, dan script player itu sendiri
                        if (rawUrl.endsWith(".jpg", true) || 
                            rawUrl.endsWith(".png", true) || 
                            rawUrl.contains("jwplayer", true)) {
                            continue
                        }
                        
                        Log.d("adixtream", "JACKPOT! URL Video Potensial: $rawUrl")
                        
                        // Kembalikan ekstensi samaran .txt menjadi .m3u8
                        val finalUrl = if (rawUrl.endsWith(".txt")) rawUrl.replace(".txt", ".m3u8") else rawUrl
                        
                        // Senjata 1: Biarkan Cloudstream yang mengekstrak resolusinya
                        generateM3u8(name, finalUrl, mainUrl).forEach(callback)
                        
                        // Senjata 2 (Bypass): Jika generateM3u8 gagal karena ekstensi disamarkan jadi .woff
                        // Kita paksa lempar URL mentahnya langsung ke ExoPlayer!
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name (Direct Player)",
                                url = finalUrl,
                                referer = referer ?: mainUrl,
                                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                isM3u8 = true // Paksa player menganggap ini adalah HLS/M3U8
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("adixtream", "Error Unpacking: ${e.message}")
                }
            }
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }
}
