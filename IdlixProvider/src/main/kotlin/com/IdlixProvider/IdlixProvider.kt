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

    // Menggunakan jalur API baru dari Idlix
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

    // --- BERANDA ---
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val response = app.get(url).parsedSafe<IdlixApiResponse>()
        
        val home = response?.data?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val type = if (item.contentType?.contains("series") == true) TvType.TvSeries else TvType.Movie
            val href = "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/$slug"
            
            // Cegah error Coil jika poster kosong
            val posterUrl = if (item.posterPath.isNullOrEmpty() || item.posterPath == "null") "" 
                            else "https://image.tmdb.org/t/p/w342${item.posterPath}"
            
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(item.quality ?: "")
            }
        } ?: emptyList()

        val hasNextPage = (response?.pagination?.page ?: 1) < (response?.pagination?.totalPages ?: 1)
        return newHomePageResponse(request.name, home, hasNext = hasNextPage)
    }

    // --- PENCARIAN ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "utf-8")
        val url = "$mainUrl/api/search?q=$encodedQuery&page=1&limit=36"
        
        // Memakai struktur JSON yang sama dengan Homepage
        val response = app.get(url).parsedSafe<IdlixApiResponse>()
        
        return response?.data?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val type = if (item.contentType?.contains("series") == true) TvType.TvSeries else TvType.Movie
            
            val href = "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/$slug"
            
            // Cegah error Coil
            val posterUrl = if (item.posterPath.isNullOrEmpty() || item.posterPath == "null") "" 
                            else "https://image.tmdb.org/t/p/w342${item.posterPath}"
            
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        } ?: emptyList()
    }

    // --- DETAIL FILM / SERIES ---
    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        
        val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
        val response = app.get(apiUrl).parsedSafe<IdlixDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil data detail dari API")
            
        val title = response.title ?: response.name ?: ""
        val poster = response.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val background = response.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val plot = response.overview
        val year = (response.releaseDate ?: response.firstAirDate)?.split("-")?.firstOrNull()?.toIntOrNull()
        val trailer = response.trailerUrl
        val tags = response.genres?.mapNotNull { it.name }
        
        val actors = response.cast?.mapNotNull {
            val actorName = it.name ?: return@mapNotNull null
            val profile = it.profilePath?.let { path -> "https://image.tmdb.org/t/p/w185$path" }
            Actor(actorName, profile)
        }

        return if (isSeries) {
            val episodes = arrayListOf<Episode>()
            
            response.seasons?.forEach { season ->
                if (season.id == response.firstSeason?.id) {
                    response.firstSeason?.episodes?.forEach { ep ->
                        episodes.add(
                            newEpisode(ep.id ?: "") {
                                this.name = ep.name
                                this.season = season.seasonNumber
                                this.episode = ep.episodeNumber
                                this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                            }
                        )
                    }
                } else {
                    val seasonUrl = "$mainUrl/api/seasons/${season.id}"
                    val seasonResponse = app.get(seasonUrl).parsedSafe<Season>()
                    seasonResponse?.episodes?.forEach { ep ->
                        episodes.add(
                            newEpisode(ep.id ?: "") {
                                this.name = ep.name
                                this.season = season.seasonNumber
                                this.episode = ep.episodeNumber
                                this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                            }
                        )
                    }
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(response.voteAverage)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, response.id ?: url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(response.voteAverage)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // --- PEMUTAR VIDEO (Masih dipertahankan, uji ini setelah build berhasil) ---
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
}

// ============================================================================
// DATA CLASSES DI LUAR CLASS UTAMA (INI KUNCI AGAR TIDAK ERROR & BISA DIBACA EXTRACTOR)
// ============================================================================

data class IdlixApiResponse(
    @JsonProperty("data") val data: List<IdlixItem>? = null,
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

// Data Kelas yang diwajibkan oleh Extractor.kt
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
