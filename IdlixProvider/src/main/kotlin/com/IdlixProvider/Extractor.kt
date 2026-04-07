package com.IdlixProvider

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
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
        val document = app.get(url, referer = referer).document
        val hash = url.split("/").last().substringAfter("data=")

        val response = app.post(
            url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
            data = mapOf(
                "hash" to hash, 
                "r" to (referer ?: "")
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to url
            )
        ).parsedSafe<ResponseSource>()

        val videoSource = response?.videoSource
        if (!videoSource.isNullOrEmpty()) {
            val m3uLink = videoSource.replace(".txt", ".m3u8")
            generateM3u8(name, m3uLink, mainUrl).forEach(callback)
        }

        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    val unpacked = getAndUnpack(script.data())
                    val subData = unpacked.substringAfter("\"tracks\":[").substringBefore("],")
                    AppUtils.tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                getLanguage(subtitle.label ?: ""),
                                subtitle.file
                            )
                        )
                    }
                } catch (e: Exception) { }
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
