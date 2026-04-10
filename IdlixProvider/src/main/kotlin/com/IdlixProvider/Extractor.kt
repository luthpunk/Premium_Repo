package com.IdlixProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

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
        try {
            // 1. Ambil HTML mentah dari iframe Jeniusplay
            val htmlContent = app.get(url, referer = referer).text
            
            // Gunakan fungsi sakti getAndUnpack untuk membongkar JS yang dienkripsi
            val unpackedText = getAndUnpack(htmlContent)
            
            // 2. Ekstrak Subtitle
            val subtitleRegex = """var\s+playerjsSubtitle\s*=\s*["'](.*?)["']""".toRegex()
            subtitleRegex.find(htmlContent)?.groupValues?.get(1)?.let { subStr ->
                val tracks = subStr.split(",")
                for (track in tracks) {
                    val langMatch = """\[(.*?)\](.*)""".toRegex().find(track)
                    if (langMatch != null) {
                        val lang = getLanguage(langMatch.groupValues[1])
                        val subUrl = langMatch.groupValues[2]
                        subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                    }
                }
            }

            // 3. Ekstrak URL Video (master.txt) dari kode JS yang sudah dibongkar
            var rawVideoSource = """"file"\s*:\s*["']([^"']+)["']""".toRegex().find(unpackedText)?.groupValues?.get(1)
            
            if (rawVideoSource.isNullOrEmpty()) {
                // Fallback pencarian manual jika regex pertama luput
                rawVideoSource = """(https:\\?/\\?/[^"'\s]+master\.txt)""".toRegex().find(unpackedText)?.groupValues?.get(1)
            }

            if (!rawVideoSource.isNullOrEmpty()) {
                // Bersihkan karakter escape backslash (\/)
                val videoUrl = rawVideoSource.replace("\\/", "/")
                Log.d("adixtream", "Jeniusplay menemukan video: $videoUrl")

                // Ekstraktor Utama (Membongkar resolusi di dalam master.txt)
                generateM3u8(name, videoUrl, url).forEach(callback)
                
                // Ekstraktor Cadangan Direct (Langsung lempar master.txt)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        // Gunakan url iframe sebagai referer agar diizinkan oleh server jeniusplay
                        this.referer = url 
                    }
                )
            } else {
                Log.d("adixtream", "Jeniusplay gagal mendapat videoSource. Unpacked: $unpackedText")
            }
        } catch (e: Exception) {
            Log.e("adixtream", "Jeniusplay Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            str.contains("english", true) -> "English"
            else -> str
        }
    }
}
