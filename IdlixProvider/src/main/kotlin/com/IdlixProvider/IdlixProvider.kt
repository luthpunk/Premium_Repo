package com.michat88

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
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

    // Menggunakan API baru
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

    // --- BERANDA: Menggunakan algoritma deteksi otomatis Json yang anti error ---
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val responseText = app.get(url).text
        
        var hasNextPage = false
        val items = arrayListOf<IdlixItem>()
        
        try {
            if (responseText.trim().startsWith("[")) {
                AppUtils.tryParseJson<List<IdlixItem>>(responseText)?.let { items.addAll(it) }
            } else {
                AppUtils.tryParseJson<IdlixApiResponse>(responseText)?.let { parsed ->
                    hasNextPage = (parsed.pagination?.page ?: 1) < (parsed.pagination?.totalPages ?: 1)
                    parsed.data?.let { items.addAll(it) }
                    parsed.results?.let { items.addAll(it) }
                    parsed.items?.let { items.addAll(it) }
                }
            }
        } catch (e: Exception) {
            // Abaikan error parsing agar aplikasi tidak crash
        }
        
        val home = items.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val type = if (item.contentType?.contains("series", true) == true) TvType.TvSeries else TvType.Movie
            val href = "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/$slug"
            
            // Pasang poster dari TMDB dan cegah link null
            val posterUrl = if (item.posterPath.isNullOrEmpty() || item.posterPath == "null") "" 
                            else "https://image.tmdb.org/t/p/w342${item.posterPath}"
            
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(item.quality ?: "")
            }
        }

        return newHomePageResponse(request.name, home, hasNext = hasNextPage)
    }

    // --- PENCARIAN ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "utf-8")
        val url = "$mainUrl/api/search?q=$encodedQuery&page=1&limit=36"
        
        val responseText = app.get(url).text
        val items = arrayListOf<IdlixItem>()
        
        try {
            if (responseText.trim().startsWith("[")) {
                AppUtils.tryParseJson<List<IdlixItem>>(responseText)?.let { items.addAll(it) }
            } else {
                AppUtils.tryParseJson<IdlixApiResponse>(responseText)?.let { parsed ->
                    parsed.data?.let { items.addAll(it) }
                    parsed.results?.let { items.addAll(it) }
                    parsed.items?.let { items.addAll(it) }
                }
            }
        } catch (e: Exception) {}

        return items.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val type = if (item.contentType?.contains("series", true) == true) TvType.TvSeries else TvType.Movie
            val href = "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/$slug"
            
            val posterUrl = if (item.posterPath.isNullOrEmpty() || item.posterPath == "null") "" 
                            else "https://image.tmdb.org/t/p/w342${item.posterPath}"
            
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    // --- DETAIL FILM / SERIES ---
    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        
        val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
        val response = app.get(apiUrl).parsedSafe<IdlixDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil data detail dari API")
            
        val title = response.title ?: response.name ?: ""
        val poster = if (response.posterPath.isNullOrEmpty() || response.posterPath == "null") "" else "https://image.tmdb.org/t/p/w500${response.posterPath}"
        val background = if (response.backdropPath.isNullOrEmpty() || response.backdropPath == "null") "" else "https://image.tmdb.org/t/p/w1280${response.backdropPath}"
        val plot = response.overview
        val year = (response.releaseDate ?: response.firstAirDate)?.split("-")?.firstOrNull()?.toIntOrNull()
        
        val ratingStr = response.voteAverage
        val trailer = response.trailerUrl
        val tags = response.genres?.mapNotNull { it.name }
        
        val actors = response.cast?.mapNotNull {
            val actorName = it.name ?: return@mapNotNull null
            val pPath = it.profilePath
            val profile = if (pPath.isNullOrEmpty() || pPath == "null") null else "https://image.tmdb.org/t/p/w185$pPath"
            Actor(actorName, profile)
        }

        if (isSeries) {
            val episodes = arrayListOf<Episode>()
            val firstSeasonId = response.firstSeason?.id
            
            response.seasons?.forEach { season ->
                val seasonId = season.id ?: return@forEach
                val seasonNumber = season.seasonNumber
                
                if (seasonId == firstSeasonId) {
                    response.firstSeason?.episodes?.forEach { ep ->
                        val epId = ep.id ?: ""
                        val still = ep.stillPath
                        val epPoster = if (still.isNullOrEmpty() || still == "null") null else "https://image.tmdb.org/t/p/w500$still"
                        
                        episodes.add(newEpisode(epId) {
                            this.name = ep.name
                            this.season = seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = epPoster
                        })
                    }
                } else {
                    val seasonUrl = "$mainUrl/api/seasons/$seasonId"
                    try {
                        val seasonResponse = app.get(seasonUrl).parsedSafe<Season>()
                        seasonResponse?.episodes?.forEach { ep ->
                            val epId = ep.id ?: ""
                            val still = ep.stillPath
                            val epPoster = if (still.isNullOrEmpty() || still == "null") null else "https://image.tmdb.org/t/p/w500$still"
                            
                            episodes.add(newEpisode(epId) {
                                this.name = ep.name
                                this.season = seasonNumber
                                this.episode = ep.episodeNumber
                                this.posterUrl = epPoster
                            })
                        }
                    } catch (e: Exception) {}
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingStr)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val movieId = response.id ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, movieId) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingStr)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // --- PEMUTAR VIDEO (Perlu diuji setelah build sukses) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val script = document.select("script:containsData(window.idlix)").toString()
        val match = scriptRegex.find(script)
        val idlixNonce = match?.groups?.get(1)?.value ?: ""
        val idlixTime = match?.groups?.get(2)?.value ?: ""

        document.select("ul#playeroptionsul > li").map {
            Triple(it.attr("data-post"), it.attr("data-nume"), it.attr("data-type"))
        }.amap { (id, nume, type) ->
            val responseText = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type, "_n" to idlixNonce, "_p" to id, "_t" to idlixTime),
                referer = data,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
            ).text
            
            try {
                val json = AppUtils.tryParseJson<ResponseHash>(responseText) ?: return@amap
                val embedUrl = json.embed_url
                val key = json.key
                
                val metrixJson = app.get(embedUrl).text
                val metrix = AppUtils.tryParseJson<AesData>(metrixJson)?.m ?: return@amap
                
                val password = createKey(key, metrix)
                val decrypted = AesHelper.cryptoAESHandler(embedUrl, password.toByteArray(), false)?.fixBloat() ?: return@amap
                
                // ERROR .toJson() SUDAH DIHAPUS
                Log.d("adixtream", decrypted)

                when {
                    !decrypted.contains("youtube") -> loadExtractor(decrypted, directUrl, subtitleCallback, callback)
                    else -> return@amap
                }
            } catch (e: Exception) {}
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

    // ============================================================================
    // DATA CLASSES DITARUH KEMBALI DI DALAM CLASS UTAMA 
    // Agar import di Extractor.kt ("com.michat88.IdlixProvider.ResponseSource") berjalan normal!
    // ============================================================================

    data class IdlixApiResponse(
        @JsonProperty("data") val data: List<IdlixItem>? = null,
        @JsonProperty("results") val results: List<IdlixItem>? = null,
        @JsonProperty("items") val items: List<IdlixItem>? = null,
        @JsonProperty("pagination") val pagination: Pagination? = null
    )

    data class Pagination(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null
    )

    data class IdlixItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("contentType") val contentType: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    data class IdlixDetailResponse(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("voteAverage") val voteAverage: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("trailerUrl") val trailerUrl: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("cast") val cast: List<Cast>? = null,
        @JsonProperty("seasons") val seasons: List<Season>? = null,
        @JsonProperty("firstSeason") val firstSeason: Season? = null
    )

    data class Genre(@JsonProperty("name") val name: String? = null)

    data class Cast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("profilePath") val profilePath: String? = null
    )

    data class Season(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeData>? = null
    )

    data class EpisodeData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("stillPath") val stillPath: String? = null
    )

    // Data Class yang dipanggil oleh Extractor.kt (JANGAN DIHAPUS / DIPINDAH)
    data class ResponseSource(
        @JsonProperty("hls") val hls: Boolean = false,
        @JsonProperty("videoSource") val videoSource: String = "",
        @JsonProperty("securedLink") val securedLink: String? = null
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("file") val file: String = "",
        @JsonProperty("label") val label: String? = null
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String = "",
        @JsonProperty("key") val key: String = ""
    )

    data class AesData(
        @JsonProperty("m") val m: String = ""
    )
}
