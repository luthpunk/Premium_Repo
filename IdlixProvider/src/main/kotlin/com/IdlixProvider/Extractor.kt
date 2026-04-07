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
        [span_1](start_span)// 1. Kunjungi halaman video untuk mendapatkan cookies session[span_1](end_span)
        val document = app.get(url, referer = referer).document
        val hash = url.split("/").last().substringAfter("data=")

        [span_2](start_span)// 2. Ambil link video melalui API getVideo[span_2](end_span)
        // SANGAT PENTING: Header Origin dan Referer harus tepat agar akses tidak ditolak
        val response = app.post(
            url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
            data = mapOf(
                "hash" to hash, 
                [span_3](start_span)"r" to (referer ?: "") // Mengirimkan URL embed/iframe sebagai referer[span_3](end_span)
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to url
            )
        ).parsedSafe<ResponseSource>()

        val videoSource = response?.videoSource
        if (!videoSource.isNullOrEmpty()) {
            [span_4](start_span)// Ubah format .txt menjadi .m3u8 agar bisa diputar oleh player[span_4](end_span)
            val m3uLink = videoSource.replace(".txt", ".m3u8")
            [span_5](start_span)generateM3u8(name, m3uLink, mainUrl).forEach(callback)[span_5](end_span)
        }

        [span_6](start_span)// 3. Ekstrak Subtitle dari script yang di-obfuscate (eval/unpack)[span_6](end_span)
        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    [span_7](start_span)val unpacked = getAndUnpack(script.data())[span_7](end_span)
                    val subData = unpacked.substringAfter("\"tracks\":[").substringBefore("],")
                    AppUtils.tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                getLanguage(subtitle.label ?: ""),
                                subtitle.file
                            [span_8](start_span))
                        )
                    }
                } catch (e: Exception) {
                    // Abaikan jika gagal memproses satu subtitle
                }
            }
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || 
            str.contains("bahasa", true) -> "Indonesian"[span_8](end_span)
            else -> str
        }
    }
}
