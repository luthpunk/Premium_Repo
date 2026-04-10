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
            // Cari string panjang yang merupakan kombinasi huruf dan angka (hex/base64)
            val hashRegex = """([a-zA-Z0-9]{30,})""".toRegex()
            var hash = url.substringAfter("data=", "").substringBefore("&")
            
            // Kalau kosong, cari pakai Regex di keseluruhan URL
            if (hash.isBlank()) {
                hash = hashRegex.find(url)?.groupValues?.get(1) ?: url.split("/").last()
            }
            
            Log.d("adixtream", "Jeniusplay mengekstrak Hash: $hash")
            
            // Mengambil Subtitle
            val htmlContent = app.get(url, referer = referer).text
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

            // Tembak API Jeniusplay
            val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
            val apiResponse = app.post(
                url = apiUrl,
                data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                referer = url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseSource>()

            val rawVideoSource = apiResponse?.videoSource

            if (!rawVideoSource.isNullOrEmpty()) {
                // JANGAN di-replace .txt ke .m3u8 karena server memang menyediakannya dalam bentuk .txt
                val videoUrl = rawVideoSource

                // Ekstraktor Utama
                generateM3u8(name, videoUrl, mainUrl).forEach(callback)
                
                // Ekstraktor Cadangan menggunakan gaya PENULISAN LAMA yang kebal error
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        // Pastikan referer mengarah ke mainUrl (jeniusplay) agar segmen video bisa dimuat
                        this.referer = mainUrl 
                    }
                )
            } else {
                Log.d("adixtream", "Jeniusplay gagal mendapat videoSource. Respons API: $apiResponse")
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
