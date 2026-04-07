package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.security.MessageDigest

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

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?sort=createdAt&limit=36&page=" to "Movie Terbaru",
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
        val responseText = app.get(url).text
        
        var hasNextPage = false
        val items = try {
            // Algoritma Pintar: Cek bentuk JSON yang dikirimkan oleh API
            if (responseText.trim().startsWith("[")) {
                AppUtils.parseJson<List<IdlixItem>>(responseText)
            } else {
                val parsed = AppUtils.parseJson<IdlixApiResponse>(responseText)
                hasNextPage = (parsed.pagination?.page ?: 1) < (parsed.pagination?.totalPages ?: 1)
                parsed.data ?: parsed.results ?: parsed.items ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        val home = items.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val type = if (item.contentType?.contains("series", true) == true) TvType.TvSeries else TvType.Movie
            val href = "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/$slug"
            
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
        val items = try {
            if (responseText.trim().startsWith("[")) {
                AppUtils.parseJson<List<IdlixItem>>(responseText)
            } else {
                val parsed = AppUtils.parseJson<IdlixApiResponse>(responseText)
                parsed.data ?: parsed.results ?: parsed.items ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

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
        
        val ratingStr = response.voteAverage?.toFloatOrNull()?.times(10)?.toInt()?.toString()
        val trailer = response.trailerUrl
        val tags = response.genres?.mapNotNull { it.name }
        
        val actors = response.cast?.mapNotNull {
            val actorName = it.name ?: return@mapNotNull null
            val pPath = it.profilePath
            val profile = if (pPath.isNullOrEmpty() || pPath == "null") null else "https://image.tmdb.org/t/p/w185$pPath"
            Actor(actorName, profile)
        }

        return if (isSeries) {
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
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
            newMovieLoadResponse(title, url, TvType.Movie, movieId) {
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

    // --- PEMUTAR VIDEO (BARU) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' di sini berisi ID UUID film/episode dari fungsi load()
        val contentId = data 
        
        // Asumsi awal kita coba menggunakan tipe movie. 
        val contentType = "movie"

        try {
            Log.d("Idlix", "Meminta Challenge untuk ID: $contentId")
            val challengeResText = app.post(
                url = "$mainUrl/api/watch/challenge",
                headers = mapOf(
                    "Accept" to "*/*",
                    "Content-Type" to "application/json",
                    "Referer" to "$mainUrl/"
                ),
                data = mapOf(
                    "contentType" to contentType,
                    "contentId" to contentId,
                    "clearance" to ""
                )
            ).text

            val challengeData = AppUtils.parseJson<ChallengeResponse>(challengeResText)
            val challengeText = challengeData.challenge ?: return false
            
            Log.d("Idlix", "Challenge didapat: $challengeText")

            // Pecahkan sandi
            val (nonce, signature) = solveChallenge(challengeText)

            Log.d("Idlix", "Mengirim Solve: Nonce=$nonce, Signature=$signature")
            val solveResText = app.post(
                url = "$mainUrl/api/watch/solve",
                headers = mapOf(
                    "Accept" to "*/*",
                    "Content-Type" to "application/json",
                    "Referer" to "$mainUrl/"
                ),
                data = mapOf(
                    "challenge" to challengeText,
                    "signature" to signature,
                    "nonce" to nonce.toString() 
                )
            ).text

            // Server biasanya mengembalikan link iframe dalam JSON
            val solveData = AppUtils.parseJson<SolveResponse>(solveResText)
            val iframeUrlPath = solveData.url ?: solveData.iframeUrl
            
            if (!iframeUrlPath.isNullOrEmpty()) {
                val fullIframeUrl = if (iframeUrlPath.startsWith("http")) iframeUrlPath else "$mainUrl$iframeUrlPath"
                Log.d("Idlix", "Iframe ditemukan: $fullIframeUrl")

                // Buka halaman iframe untuk mencari link Jeniusplay
                val iframeHtml = app.get(fullIframeUrl, referer = "$mainUrl/").text
                
                // Regex ini akan mencari URL yang berawalan jeniusplay.com/video/
                val jeniusRegex = """(https://jeniusplay\.com/video/[a-zA-Z0-9]+)""".toRegex()
                val jeniusLink = jeniusRegex.find(iframeHtml)?.value
                
                if (jeniusLink != null) {
                    Log.d("Idlix", "Link Jeniusplay ditemukan: $jeniusLink")
                    // PERUBAHAN PENTING: Kita berikan fullIframeUrl sebagai referer ke Jeniusplay
                    loadExtractor(jeniusLink, fullIframeUrl, subtitleCallback, callback)
                    return true
                } else {
                    Log.d("Idlix", "Gagal menemukan link Jeniusplay di dalam Iframe")
                }
            } else {
                Log.d("Idlix", "Solve gagal atau url iframe tidak ada: $solveResText")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Log.d("Idlix", "Error di loadLinks: ${e.message}")
        }
        return false
    }

    // Fungsi Pembantu: Memecahkan Sandi Proof of Work Idlix
    private fun solveChallenge(challenge: String): Pair<Int, String> {
        val md = MessageDigest.getInstance("SHA-256")
        
        // Kita coba buat random angka nonce dan kita hash bersama challenge-nya.
        val nonce = (100000..999999).random() 
        val input = "$challenge:$nonce" 
        val hashBytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val signature = hashBytes.joinToString("") { "%02x".format(it) }
        
        return Pair(nonce, signature)
    }

    // Fungsi-fungsi lama dipertahankan agar tidak error jika ke depannya butuh dekripsi
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
// DATA CLASSES 
// ============================================================================

// --- Data Kelas Baru untuk Keamanan Video ---
data class ChallengeResponse(
    @JsonProperty("challenge") val challenge: String? = null
)

data class SolveResponse(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("iframeUrl") val iframeUrl: String? = null
)

// --- Data Kelas Lama (Tetap Dipertahankan) ---
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
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("voteAverage") val voteAverage: String? = null
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
