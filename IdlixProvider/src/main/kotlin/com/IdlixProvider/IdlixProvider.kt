package com.michat88

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URI

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z1.idlixku.com"
    private var directUrl = mainUrl
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // Menggunakan jalur API baru dari Idlix!
    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?sort=createdAt&limit=36&page=" to "Movie Terbaru",
        "$mainUrl/api/series?sort=createdAt&limit=36&page=" to "Series Terbaru",
        "$mainUrl/api/trending/top?contentType=movie&limit=36&period=7d&page=" to "Trending Movies",
        "$mainUrl/api/trending/top?contentType=series&limit=36&period=7d&page=" to "Trending Series"
    )

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    // FUNGSI BARU: Mengambil data langsung dari JSON API Idlix
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Menyambungkan URL API dengan nomor halaman (1, 2, 3, dst)
        val url = request.data + page
        
        // Mem-parsing data JSON menjadi Object
        val response = app.get(url).parsedSafe<IdlixApiResponse>()
        
        val home = response?.data?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            
            // Menentukan tipe konten
            val type = if (item.contentType?.contains("series") == true) TvType.TvSeries else TvType.Movie
            
            // Membentuk URL halaman detail
            val href = "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/$slug"
            
            // Menyusun gambar dari TMDB langsung! (w342 adalah resolusi standar poster)
            val posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(item.quality ?: "")
            }
        } ?: emptyList()

        // Mengecek apakah masih ada halaman selanjutnya (untuk infinite scroll)
        val hasNextPage = (response?.pagination?.page ?: 1) < (response?.pagination?.totalPages ?: 1)
        
        return newHomePageResponse(request.name, home, hasNext = hasNextPage)
    }

    // -----------------------------------------------------------
    // CATATAN PENTING:
    // Fungsi search() dan load() di bawah ini mungkin masih menggunakan 
    // sistem lama (HTML Scraping). Jika pencarian atau halaman detail film 
    // masih bermasalah, kita akan ganti juga ke versi API.
    // -----------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$mainUrl/search/$query")
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        
        return document.select("div.result-item").map {
            val title = it.selectFirst("div.title > a")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")?.trim() ?: "Unknown"
            val href = it.selectFirst("div.title > a")?.attr("href") ?: ""
            val posterUrl = it.selectFirst("img")?.attr("src") ?: ""
            
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        
        val title = document.selectFirst("div.data > h1")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")?.trim().toString()
        val poster = document.select("div.poster > img").attr("src")
            
        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = Regex(",\\s?(\\d+)").find(document.select("span.date").text().trim())?.groupValues?.get(1)?.toIntOrNull()
        
        val tvType = if (document.select("ul#section > li:nth-child(1)").text().contains("Episodes")) TvType.TvSeries else TvType.Movie
        
        val description = document.select("p:nth-child(3)").text().trim()
        val trailer = document.selectFirst("div.embed iframe")?.attr("src")
        val rating = document.selectFirst("span.dt_rating_vgs")?.text()
        
        val actors = document.select("div.persons > div[itemprop=actor]").map {
            Actor(it.select("meta[itemprop=name]").attr("content"), it.select("img").attr("src"))
        }

        val recommendations = document.select("div.owl-item").map {
            val recName = it.selectFirst("a")?.attr("href")?.removeSuffix("/")?.split("/")?.last() ?: ""
            val recHref = it.selectFirst("a")?.attr("href") ?: ""
            val recPosterUrl = it.selectFirst("img")?.attr("src").toString()
            
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li").map {
                val href = it.select("a").attr("href") ?: ""
                val name = fixTitle(it.select("div.episodiotitle > a").text().trim())
                val image = it.select("div.imagen > img").attr("src") ?: ""
                val episode = it.select("div.numerando").text().replace(" ", "").split("-").last().toIntOrNull()
                val season = it.select("div.numerando").text().replace(" ", "").split("-").first().toIntOrNull()
                
                newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = episode
                    this.posterUrl = image
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from100(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from100(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ... Kode loadLinks (dekripsi AES) dibiarkan utuh
        val document = app.get(data).document
        val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val script = document.select("script:containsData(window.idlix)").toString()
        val match = scriptRegex.find(script)
        val idlixNonce = match?.groups?.get(1)?.value ?: ""
        val idlixTime = match?.groups?.get(2)?.value ?: ""

        document.select("ul#playeroptionsul > li").map {
            Triple(it.attr("data-post"), it.attr("data-nume"), it.attr("data-type"))
        }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type, "_n" to idlixNonce, "_p" to id, "_t" to idlixTime),
                referer = data,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseHash>() ?: return@amap
            
            val metrix = AppUtils.parseJson<AesData>(json.embed_url).m
            val password = createKey(json.key, metrix)
            val decrypted = AesHelper.cryptoAESHandler(json.embed_url, password.toByteArray(), false)?.fixBloat() ?: return@amap
            
            Log.d("adixtream", decrypted.toJson())

            when {
                !decrypted.contains("youtube") -> loadExtractor(decrypted, directUrl, subtitleCallback, callback)
                else -> return@amap
            }
        }
        return true
    }

    private fun createKey(r: String, m: String): String {
        val rList = r.split("\\x").filter { it.isNotEmpty() }.toTypedArray()
        var n = ""
        var reversedM = m.split("").reversed().joinToString("")
        while (reversedM.length % 4 != 0) reversedM += "="
        val decodedBytes = try { base64Decode(reversedM) } catch (_: Exception) { return "" }
        val decodedM = String(decodedBytes.toCharArray())
        for (s in decodedM.split("|")) {
            try {
                val index = Integer.parseInt(s)
                if (index in rList.indices) n += "\\x" + rList[index]
            } catch (_: Exception) {}
        }
        return n
    }

    private fun String.fixBloat(): String {
        return this.replace("\"", "").replace("\\", "")
    }

    // --- DATA KELAS BARU UNTUK API ---
    data class IdlixApiResponse(
        @JsonProperty("data") val data: List<IdlixItem>? = null,
        @JsonProperty("pagination") val pagination: Pagination? = null
    )

    data class Pagination(
        @JsonProperty("page") val page: Int?,
        @JsonProperty("totalPages") val totalPages: Int?
    )

    data class IdlixItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("contentType") val contentType: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("voteAverage") val voteAverage: String? = null
    )

    // --- DATA KELAS LAMA ---
    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String,
    )

    data class AesData(
        @JsonProperty("m") val m: String,
    )
}
