package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.api.Log

// ==============================
// UPDATED JENIUSPLAY EXTRACTOR (FROM NEW IDLIX)
// ==============================

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
            [span_0](start_span)// 1. Ambil HTML dari Jeniusplay[span_0](end_span)
            val response = app.get(url, referer = referer)
            val htmlContent = response.text
            val document = response.document

            [span_1](start_span)// 2. Ekstrak Subtitle menggunakan Regex[span_1](end_span)
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

            [span_2](start_span)// 3. Bongkar JavaScript Packer untuk mencari link video[span_2](end_span)
            val unpackedText = getAndUnpack(htmlContent).replace("\\/", "/")
            val videoRegex = """(https?://[^"'\s]+(?:master\.txt|\.m3u8))""".toRegex()
            var videoUrl = videoRegex.find(unpackedText)?.groupValues?.get(1)

            [span_3](start_span)[span_4](start_span)// 4. Fallback ke API internal jika unpack gagal[span_3](end_span)[span_4](end_span)
            if (videoUrl.isNullOrEmpty()) {
                val hash = url.substringAfter("data=", "").substringBefore("&")
                    .ifBlank { url.split("/").last() }

                val apiResponse = app.post(
                    url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
                    data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<ResponseSource>()

                [span_5](start_span)// Bypass proteksi ekstensi Cloudflare (.woff atau .txt diubah ke .m3u8)[span_5](end_span)
                videoUrl = apiResponse?.videoSource?.replace(".woff", ".m3u8")?.replace(".txt", ".m3u8")
            }

            [span_6](start_span)// 5. Kirim link video yang ditemukan ke aplikasi[span_6](end_span)
            if (!videoUrl.isNullOrEmpty()) {
                // Menggunakan M3u8Helper untuk mendapatkan semua resolusi
                generateM3u8(name, videoUrl, referer ?: mainUrl).forEach(callback)
                
                // Tambahkan link direct sebagai cadangan
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name (Direct)",
                        url = videoUrl,
                        ExtractorLinkType.M3U8
                    ) {
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
            [span_7](start_span)str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"[span_7](end_span)
            [span_8](start_span)str.contains("english", true) -> "English"[span_8](end_span)
            else -> str
        }
    }

    data class ResponseSource(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("hls") val hls: Boolean? = null
    )
}
