package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
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
            val htmlContent = app.get(url, referer = referer).text
            
            val subtitleRegex = """var\s+playerjsSubtitle\s*=\s*["'](.*?)["']""".toRegex()
            subtitleRegex.find(htmlContent)?.groupValues?.get(1)?.let { subStr ->
                val tracks = subStr.split(",")
                for (track in tracks) {
                    val langMatch = """\[(.*?)\](.*)""".toRegex().find(track)
                    if (langMatch != null) {
                        val lang = getLanguage(langMatch.groupValues[1])
                        val subUrl = langMatch.groupValues[2]
                        subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                    }
                }
            }

            val unpackedText = getAndUnpack(htmlContent).replace("\\/", "/")
            val videoRegex = """(https?://[^"'\s]+(?:master\.txt|\.m3u8))""".toRegex()
            var videoUrl = videoRegex.find(unpackedText)?.groupValues?.get(1)

            if (videoUrl.isNullOrEmpty()) {
                val hashRegex = """([a-zA-Z0-9]{30,})""".toRegex()
                var hash = url.substringAfter("data=", "").substringBefore("&")
                
                if (hash.isBlank()) {
                    hash = hashRegex.find(url)?.groupValues?.get(1) ?: url.split("/").last()
                }
                
                val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
                val apiResponse = app.post(
                    url = apiUrl,
                    data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<ResponseSource>()
                
                val rawVideoSource = apiResponse?.videoSource
                if (!rawVideoSource.isNullOrEmpty()) {
                    videoUrl = rawVideoSource.replace(".woff", ".m3u8").replace(".txt", ".m3u8")
                }
            }

            if (!videoUrl.isNullOrEmpty()) {
                generateM3u8(name, videoUrl, mainUrl).forEach(callback)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = url 
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Adicinemax21", "Jeniusplay Error: ${e.message}")
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

data class ResponseSource(
    @JsonProperty("videoSource") val videoSource: String = ""
)
