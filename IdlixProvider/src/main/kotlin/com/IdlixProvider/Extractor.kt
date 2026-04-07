package com.IdlixProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
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
            // 1. Ekstrak HASH dengan benar dari format URL baru (/video/HASH)
            val hash = url.split("/").last().substringAfter("data=")
            Log.d("adixtream", "Hash Jeniusplay: $hash")

            // 2. Ambil HTML mentah untuk menyedot Subtitle
            val htmlContent = app.get(url, referer = referer).text
            
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

            // 3. Tembak API Asli Jeniusplay (Yang kita sangka sudah mati)
            val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
            Log.d("adixtream", "Menembak API: $apiUrl")
            
            val apiResponse = app.post(
                url = apiUrl,
                data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                referer = url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseSource>()

            val rawVideoSource = apiResponse?.videoSource
            Log.d("adixtream", "Respons API Jeniusplay: $rawVideoSource")

            if (!rawVideoSource.isNullOrEmpty()) {
                // 4. BONGKAR PENYAMARAN: Ubah .woff atau .txt menjadi .m3u8
                val m3u8Url = rawVideoSource.replace(".woff", ".m3u8").replace(".txt", ".m3u8")
                Log.d("adixtream", "Link M3U8 Final: $m3u8Url")

                // Ekstraktor Utama
                generateM3u8(name, m3u8Url, mainUrl).forEach(callback)
                
                // Ekstraktor Cadangan (Direct Player) jika Cloudstream menolak URL aneh
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = m3u8Url,
                        referer = referer ?: mainUrl,
                        quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            } else {
                Log.e("adixtream", "Gagal! API Jeniusplay tidak memberikan link video.")
            }
        } catch (e: Exception) {
            Log.e("adixtream", "Error di Extractor Jeniusplay: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }
}
