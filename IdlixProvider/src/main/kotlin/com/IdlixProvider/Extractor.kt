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
            val hash = url.split("/").last().substringAfter("data=")
            val response = app.get(url, referer = referer)
            val htmlContent = response.text

            // 1. Subtitle Filter
            val subtitleRegex = """var\s+playerjsSubtitle\s*=\s*["'](.*?)["']""".toRegex()
            subtitleRegex.find(htmlContent)?.groupValues?.get(1)?.let { subStr ->
                subStr.split(",").forEach { track ->
                    """\[(.*?)\](.*)""".toRegex().find(track)?.let {
                        val lang = if (it.groupValues[1].contains("indonesia", true) || it.groupValues[1].contains("bahasa", true)) "Indonesian" else it.groupValues[1]
                        subtitleCallback.invoke(SubtitleFile(lang, it.groupValues[2]))
                    }
                }
            }

            // 2. API Request
            val apiRes = app.post(
                url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
                data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                referer = url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseSource>()

            val rawUrl = apiRes?.videoSource
            if (!rawUrl.isNullOrEmpty()) {
                // Bongkar .woff/.txt ke .m3u8
                val m3u8Url = rawUrl.replace(".woff", ".m3u8").replace(".txt", ".m3u8")

                // Ekstraktor Utama
                generateM3u8(name, m3u8Url, mainUrl).forEach(callback)
                
                // Ekstraktor Cadangan - SESUAI EXTRACTORAPI.KT BARIS 78
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // Parameter tambahan diatur di dalam initializer block
                        this.quality = Qualities.Unknown.value
                        this.referer = referer ?: mainUrl
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
