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
            // 1. Ekstrak HASH dari URL baru (/video/HASH)
            val hash = url.split("/").last().substringAfter("data=")
            
            // 2. Ambil HTML mentah untuk Subtitle
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

            // 3. Tembak API Jeniusplay
            val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
            val apiResponse = app.post(
                url = apiUrl,
                data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                referer = url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseSource>()

            val rawVideoSource = apiResponse?.videoSource

            if (!rawVideoSource.isNullOrEmpty()) {
                // Bongkar penyamaran .woff/.txt ke .m3u8
                val m3u8Url = rawVideoSource.replace(".woff", ".m3u8").replace(".txt", ".m3u8")

                // Ekstraktor Utama
                generateM3u8(name, m3u8Url, mainUrl).forEach(callback)
                
                // Ekstraktor Cadangan menggunakan newExtractorLink yang benar (Sesuai Source 78)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8 // Sesuai Source 67
                    ) {
                        // Parameter tambahan diatur di sini (Blok Initializer)
                        this.quality = Qualities.Unknown.value // Sesuai Source 126
                        this.referer = referer ?: mainUrl
                    }
                )
            }
        } catch (e: Exception) {
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
