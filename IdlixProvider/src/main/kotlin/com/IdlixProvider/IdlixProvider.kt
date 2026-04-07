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

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "b030404650f279792a8d3287232358e3"
    }

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

        // --- AMBIL REKOMENDASI (SARAN FILM/SERIES) ---
        val recommendationsUrl = "$apiUrl/related"
        val recommendationsList = try {
            val recText = app.get(recommendationsUrl).text
            val recItems = if (recText.trim().startsWith("[")) {
                AppUtils.parseJson<List<IdlixItem>>(recText)
            } else {
                val parsed = AppUtils.parseJson<IdlixApiResponse>(recText)
                parsed.data ?: parsed.results ?: parsed.items ?: emptyList()
            }
            
            recItems.mapNotNull { item ->
                val recTitle = item.title ?: item.name ?: return@mapNotNull null
                val recSlug = item.slug ?: return@mapNotNull null
                val recType = if (item.contentType?.contains("series", true) == true) TvType.TvSeries else TvType.Movie
                val recHref = "$mainUrl/${if (recType == TvType.TvSeries) "series" else "movie"}/$recSlug"
                val recPoster = if (item.posterPath.isNullOrEmpty() || item.posterPath == "null") "" 
                                else "https://image.tmdb.org/t/p/w342${item.posterPath}"
                
                newMovieSearchResponse(recTitle, recHref, recType) {
                    this.posterUrl = recPoster
                }
            }
        } catch (e: Exception) {
            emptyList()
        }

        return if (isSeries) {
            val episodes = arrayListOf<Episode>()
            val seasonNamesList = mutableListOf<SeasonData>()
            
            // --- KEKUATAN TMDB (Untuk memunculkan semua tab season) ---
            try {
                val searchUrl = "$tmdbAPI/search/tv?api_key=$apiKey&query=${java.net.URLEncoder.encode(title, "utf-8")}&first_air_date_year=$year&language=id-ID"
                val searchRes = app.get(searchUrl).parsedSafe<TmdbSearch>()
                val tmdbId = searchRes?.results?.firstOrNull()?.id
                
                if (tmdbId != null) {
                    val tvDetail = app.get("$tmdbAPI/tv/$tmdbId?api_key=$apiKey&language=id-ID").parsedSafe<TmdbTvDetail>()
                    
                    tvDetail?.seasons?.forEach { season ->
                        val sNum = season.season_number ?: return@forEach
                        if (sNum == 0) return@forEach 
                        
                        seasonNamesList.add(SeasonData(sNum, "Season $sNum"))
                        val seasonUrl = "$tmdbAPI/tv/$tmdbId/season/$sNum?api_key=$apiKey&language=id-ID"
                        val seasonData = app.get(seasonUrl).parsedSafe<TmdbSeasonDetail>()
                        
                        seasonData?.episodes?.forEach { eps ->
                            val eNum = eps.episode_number ?: return@forEach
                            val epPoster = eps.still_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                            
                            val loadData = LinkData(
                                type = "episode",
                                slug = slug,
                                season = sNum,
                                episode = eNum,
                                url = url
                            )
                            
                            episodes.add(newEpisode(data = loadData) {
                                this.name = eps.name
                                this.season = sNum
                                this.episode = eNum
                                this.posterUrl = epPoster
                                this.description = eps.overview
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("adixtream", "Gagal fetch TMDB: ${e.message}")
            }
            
            // --- FALLBACK KE IDLIX API ---
            if (episodes.isEmpty()) {
                response.seasons?.forEach { season ->
                    val seasonNumber = season.seasonNumber ?: return@forEach
                    seasonNamesList.add(SeasonData(seasonNumber, "Season $seasonNumber"))
                    
                    var epList = season.episodes
                    if (epList.isNullOrEmpty() && season.id == response.firstSeason?.id) {
                        epList = response.firstSeason?.episodes
                    }
                    
                    epList?.forEach { ep ->
                        val epId = ep.id ?: return@forEach
                        val still = ep.stillPath
                        val epPoster = if (still.isNullOrEmpty() || still == "null") null else "https://image.tmdb.org/t/p/w500$still"
                        
                        val loadData = LinkData(
                            type = "episode",
                            id = epId,
                            slug = slug,
                            season = seasonNumber,
                            episode = ep.episodeNumber,
                            url = url
                        )
                        
                        episodes.add(newEpisode(data = loadData) {
                            this.name = ep.name
                            this.season = seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = epPoster
                        })
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingStr)
                this.recommendations = recommendationsList
                addSeasonNames(seasonNamesList)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val movieId = response.id ?: url
            val loadData = LinkData(
                type = "movie",
                id = movieId,
                url = url
            )
            
            newMovieLoadResponse(title, url, TvType.Movie, data = loadData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingStr)
                this.recommendations = recommendationsList
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // --- MENDAPATKAN LINK VIDEO ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d("adixtream", "Mulai loadLinks dengan data: $data")
            
            // Unpack data JSON
            val linkData = AppUtils.parseJson<LinkData>(data)
            val contentType = linkData.type
            var contentId = linkData.id
            val refererUrl = linkData.url
            
            // Jika ini Episode (TMDB) dan kita belum punya UUID-nya, kita Scrape HTML-nya (DIPERKUAT!)
            if (contentType == "episode" && contentId == null && linkData.slug != null) {
                val epUrl = "$mainUrl/series/${linkData.slug}/season/${linkData.season}/episode/${linkData.episode}"
                try {
                    val html = app.get(epUrl).text
                    
                    // REGEX SUPER KUAT: Menggunakan (?s) agar bisa membaca melewati enter/baris baru
                    // Dan menyapu area 300 karakter di sekitar nomor episode untuk mencari id (UUID)
                    val regexes = listOf(
                        """(?s)"id"\s*:\s*"([a-f0-9\-]{36})".{0,300}?"seasonNumber"\s*:\s*${linkData.season}.{0,300}?"episodeNumber"\s*:\s*${linkData.episode}\b""".toRegex(),
                        """(?s)"seasonNumber"\s*:\s*${linkData.season}.{0,300}?"episodeNumber"\s*:\s*${linkData.episode}\b.{0,300}?"id"\s*:\s*"([a-f0-9\-]{36})"""".toRegex(),
                        """(?s)"id"\s*:\s*"([a-f0-9\-]{36})".{0,300}?"episodeNumber"\s*:\s*${linkData.episode}\b""".toRegex(),
                        """(?s)"episodeNumber"\s*:\s*${linkData.episode}\b.{0,300}?"id"\s*:\s*"([a-f0-9\-]{36})"""".toRegex()
                    )
                    
                    for (regex in regexes) {
                        contentId = regex.find(html)?.groupValues?.get(1)
                        if (contentId != null) break
                    }
                    
                    // Fallback Terakhir: Jika masih kosong, cari di object episode utama
                    if (contentId.isNullOrEmpty()) {
                        val fallbackRegex = """(?s)"episode"\s*:\s*\{[^{}]*?"id"\s*:\s*"([a-f0-9\-]{36})"""".toRegex()
                        contentId = fallbackRegex.find(html)?.groupValues?.get(1)
                    }
                } catch (e: Exception) {
                    Log.e("adixtream", "Gagal scrape UUID dari HTML: ${e.message}")
                }
            }
            
            if (contentId.isNullOrEmpty()) {
                Log.d("adixtream", "contentId tidak ditemukan!")
                return false
            }

            Log.d("adixtream", "Tipe: $contentType, ID: $contentId, Referer: $refererUrl")

            // --- TAHAP BYPASS KEAMANAN ---
            val clearanceText = app.post(
                url = "$mainUrl/api/adblock/clearance",
                headers = mapOf("Referer" to refererUrl, "Origin" to mainUrl, "Accept" to "application/json, text/plain, */*")
            ).text.trim()
            
            val tokenClear = if (clearanceText.startsWith("{")) {
                AppUtils.parseJson<ClearanceResponse>(clearanceText).token
            } else {
                clearanceText.replace("\"", "")
            }
            
            Log.d("adixtream", "Clearance Token: $tokenClear")
            if (tokenClear.isNullOrEmpty()) return false

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

            val nonce = mineNonce(challenge, difficulty)
            Log.d("adixtream", "Nonce ketemu: $nonce")
            if (nonce == null) return false

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
            
            val embedHtml = app.get(fullEmbedUrl, headers = mapOf("Referer" to refererUrl)).document
            
            var iframeSrc = embedHtml.selectFirst("iframe")?.attr("src") 
            
            if (!iframeSrc.isNullOrEmpty()) {
                if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"
                Log.d("adixtream", "Melempar ke Extractor: $iframeSrc")
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

    private fun mineNonce(challenge: String, difficulty: Int): Int? {
        val md = MessageDigest.getInstance("SHA-256")
        
        for (nonce in 0..2000000) {
            val text = challenge + nonce
            val bytes = md.digest(text.toByteArray())
            
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

data class LinkData(
    @JsonProperty("type") val type: String,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("url") val url: String
)

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
    @JsonProperty("firstSeason") val firstSeason: Season? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeData>? = null
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

// --- TMDB DATA CLASSES ---
data class TmdbSearch(@JsonProperty("results") val results: List<TmdbResult>? = null)
data class TmdbResult(@JsonProperty("id") val id: Int? = null)
data class TmdbTvDetail(@JsonProperty("seasons") val seasons: List<TmdbSeason>? = null)
data class TmdbSeason(@JsonProperty("season_number") val season_number: Int? = null)
data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)
data class TmdbEpisode(
    @JsonProperty("episode_number") val episode_number: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val still_path: String? = null
)
