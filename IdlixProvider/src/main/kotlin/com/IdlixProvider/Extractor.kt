package com.IdlixProvider

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
        url: String, // Ini akan berisi https://jeniusplay.com/video/HASH
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Ambil halaman HTML dari Iframe Jeniusplay
        val response = app.get(url, referer = referer)
        val document = response.document
        val htmlContent = response.text

        // 2. Ambil Subtitle (Tipe baru: var playerjsSubtitle = "[Bahasa]https://...jpg";)
        val subtitleRegex = """var\s+playerjsSubtitle\s*=\s*["'](.*?)["']""".toRegex()
        subtitleRegex.find(htmlContent)?.groupValues?.get(1)?.let { subStr ->
            // Formatnya bisa beberapa bahasa dipisah koma, contoh: "[Eng]http..,[Indo]http.."
            val tracks = subStr.split(",")
            for (track in tracks) {
                // Regex untuk memisahkan nama bahasa di dalam [ ] dengan URL-nya
                val langMatch = """\[(.*?)\](.*)""".toRegex().find(track)
                if (langMatch != null) {
                    val lang = getLanguage(langMatch.groupValues[1])
                    val subUrl = langMatch.groupValues[2]
                    subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                }
            }
        }

        // 3. Ambil Video (Membongkar skrip eval)
        document.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    // Membongkar skrip yang di-obfuscate
                    val unpacked = getAndUnpack(data)
                    
                    // Mencari teks seperti file:"https://...txt" atau file:"https://...m3u8"
                    val fileRegex = """["']?file["']?\s*:\s*["']([^"']+)["']""".toRegex()
                    val match = fileRegex.find(unpacked)
                    
                    if (match != null) {
                        val rawUrl = match.groupValues[1]
                        
                        // Biasanya jeniusplay menyamarkan ekstensi m3u8 menjadi txt
                        val m3u8Url = rawUrl.replace(".txt", ".m3u8")
                        
                        // Generate link extractor
                        generateM3u8(name, m3u8Url, mainUrl).forEach(callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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
