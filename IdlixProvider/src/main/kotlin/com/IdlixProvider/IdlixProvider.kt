package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.security.MessageDigest

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z1.idlixku.com"
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

    // --- BERANDA ---
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val responseText = app.get(url).text
        
        var hasNextPage = false
        val items = try {
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
                        
                        // KITA PACKING DATA UNTUK LOADLINKS: tipe|id|url
                        val loadData = "episode|$epId|$url"
                        
                        episodes.add(newEpisode(loadData) {
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
                            val loadData = "episode|$epId|$url"
                            
                            episodes.add(newEpisode(loadData) {
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
            // KITA PACKING DATA UNTUK LOADLINKS: tipe|id|url
            val loadData = "movie|$movieId|$url"
            
            newMovieLoadResponse(title, url, TvType.Movie, loadData) {
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

    // --- MENDAPATKAN LINK VIDEO (SISTEM BARU) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d("adixtream", "Mulai loadLinks dengan data: $data")
            
            // 1. Ekstrak data yang di-packing dari fungsi load()
            val parts = data.split("|")
            val rawContentType = parts.getOrNull(0) ?: "movie"
            
            // --- PERBAIKAN: Memotong URL jika tersisip secara otomatis dari Cloudstream ---
            val contentType = rawContentType.substringAfterLast("/")
            
            val contentId = parts.getOrNull(1) ?: data // Fallback jika data cuma UUID
            val refererUrl = parts.getOrNull(2) ?: "$mainUrl/"
            
            Log.d("adixtream", "Tipe: $contentType, ID: $contentId, Referer: $refererUrl")

            // 2. Tahap 0: Meminta Clearance Token
            val clearanceText = app.post(
                url = "$mainUrl/api/adblock/clearance",
                headers = mapOf("Referer" to refererUrl, "Origin" to mainUrl, "Accept" to "application/json, text/plain, */*")
            ).text.trim()
            
            // Parsing pintar: Kadang respons API adalah string teks mentah, kadang JSON
            val tokenClear = if (clearanceText.startsWith("{")) {
                AppUtils.parseJson<ClearanceResponse>(clearanceText).token
            } else {
                clearanceText.replace("\"", "")
            }
            
            Log.d("adixtream", "Clearance Token: $tokenClear")
            if (tokenClear.isNullOrEmpty()) return false

            // 3. Tahap 1: Meminta Challenge & Signature
            val challengeRes = app.post(
                url = "$mainUrl/api/watch/challenge",
                json = mapOf(
                    "contentType" to contentType,
                    "contentId" to contentId,
                    "clearance" to tokenClear
                ),
                headers = mapOf("Referer" to refererUrl, "Origin" to mainUrl, "Accept" to "application/json, text/plain, */*")
            ).parsedSafe<ChallengeResponse>()

            val challenge = challengeRes?.challenge ?: return false
            val signature = challengeRes.signature ?: return false
            val difficulty = challengeRes.difficulty ?: 3
            
            Log.d("adixtream", "Challenge: $challenge, Diff: $difficulty")

            // 4. Tahap 2: Menambang Nonce (Sangat Cepat via Byte Level)
            val nonce = mineNonce(challenge, difficulty)
            Log.d("adixtream", "Nonce ketemu: $nonce")
            if (nonce == null) return false

            // 5. Tahap 3: Kirim Solusi (Solve)
            val solveRes = app.post(
                url = "$mainUrl/api/watch/solve",
                json = mapOf(
                    "challenge" to challenge,
                    "signature" to signature,
                    "nonce" to nonce
                ),
                headers = mapOf("Referer" to refererUrl, "Origin" to mainUrl, "Accept" to "application/json, text/plain, */*")
            ).parsedSafe<SolveResponse>()

            val embedPath = solveRes?.embedUrl ?: return false
            val fullEmbedUrl = if (embedPath.startsWith("/")) "$mainUrl$embedPath" else embedPath
            Log.d("adixtream", "Eksekusi Embed URL: $fullEmbedUrl")
            
            // 6. Tahap 4: Eksekusi Embed URL untuk mendapatkan Iframe
            val embedHtml = app.get(fullEmbedUrl, headers = mapOf("Referer" to refererUrl)).document
            
            // Cari link di dalam tag iframe (Mengarah ke Jeniusplay)
            var iframeSrc = embedHtml.selectFirst("iframe")?.attr("src") 
            
            if (!iframeSrc.isNullOrEmpty()) {
                if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"
                Log.d("adixtream", "Melempar ke Extractor: $iframeSrc")
                // Lempar link iframe ke Extractor
                loadExtractor(iframeSrc, refererUrl, subtitleCallback, callback)
            } else {
                Log.d("adixtream", "Iframe tidak ditemukan. HTML: ${embedHtml.html()}")
            }

            return true
        } catch (e: Exception) {
            Log.e("adixtream", "Error di loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // --- FUNGSI BANTUAN MINING (SHA-256) SUPER CEPAT ---
    private fun mineNonce(challenge: String, difficulty: Int): Int? {
        val md = MessageDigest.getInstance("SHA-256")
        
        for (nonce in 0..2000000) {
            val text = challenge + nonce
            val bytes = md.digest(text.toByteArray())
            
            // Cek byte secara langsung (jauh lebih ringan di memori daripada konversi string!)
            var isValid = true
            for (i in 0 until difficulty) {
                val byteIndex = i / 2
                val isHighNibble = (i % 2 == 0)
                val nibble = if (isHighNibble) {
                    (bytes[byteIndex].toInt() ushr 4) and 0x0F
                } else {
                    bytes[byteIndex].toInt() and 0x0F
                }
                
                if (nibble != 0) {
                    isValid = false
                    break
                }
            }
            if (isValid) return nonce
        }
        return null
    }
}

// ============================================================================
// DATA CLASSES 
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

data class ClearanceResponse(
    @JsonProperty("token") val token: String? = null
)

data class ChallengeResponse(
    @JsonProperty("challenge") val challenge: String? = null,
    @JsonProperty("signature") val signature: String? = null,
    @JsonProperty("difficulty") val difficulty: Int? = 3
)

data class SolveResponse(
    @JsonProperty("embedUrl") val embedUrl: String? = null
)
