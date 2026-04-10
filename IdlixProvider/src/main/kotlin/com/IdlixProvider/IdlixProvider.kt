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
        "$mainUrl/api/homepage" to "Beranda"
    )

    // --- BERANDA ---
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Idlix menggunakan API tunggal untuk homepage, jadi hanya diload di page 1
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val url = request.data
        val responseText = app.get(url).text
        val homeItems = mutableListOf<SearchResponse>()

        try {
            val parsed = AppUtils.parseJson<IdlixHomepageResponse>(responseText)
            val allSections = mutableListOf<HomepageSection>()
            
            parsed.above?.let { allSections.addAll(it) }
            parsed.below?.let { allSections.addAll(it) }

            for (section in allSections) {
                val sectionData = section.data ?: continue
                
                // Lewati bagian "latest_episodes" agar beranda tidak dipenuhi episode lepas
                if (section.type == "latest_episodes") continue 

                for (item in sectionData) {
                    val content = item.getActualContent()
                    val title = content.title ?: continue
                    val slug = content.slug ?: continue
                    
                    val typeRaw = item.contentType ?: content.contentType ?: ""
                    val isSeries = typeRaw.contains("series", true) || typeRaw.contains("episode", true)
                    val type = if (isSeries) TvType.TvSeries else TvType.Movie
                    
                    val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
                    val posterPath = content.posterPath
                    val posterUrl = if (posterPath.isNullOrEmpty() || posterPath == "null") "" 
                                    else "https://image.tmdb.org/t/p/w342$posterPath"

                    homeItems.add(
                        newMovieSearchResponse(title, href, type) {
                            this.posterUrl = posterUrl
                            this.quality = getQualityFromString(content.quality ?: "")
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("adixtream", "Gagal parse halaman utama: ${e.message}")
            e.printStackTrace()
        }

        // Tampilkan hasil yang unik (tidak ada duplikat)
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, hasNext = false)
    }

    // --- PENCARIAN ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "utf-8")
        val url = "$mainUrl/api/search?q=$encodedQuery"
        
        val responseText = app.get(url).text
        val searchItems = mutableListOf<SearchResponse>()
        
        try {
            val parsed = AppUtils.parseJson<IdlixSearchResponse>(responseText)
            val items = parsed.data ?: parsed.results ?: emptyList()
            
            for (item in items) {
                val title = item.title ?: item.originalTitle ?: continue
                val slug = item.slug ?: continue
                
                val typeRaw = item.contentType ?: ""
                val isSeries = typeRaw.contains("series", true)
                val type = if (isSeries) TvType.TvSeries else TvType.Movie
                
                val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
                val posterPath = item.posterPath
                val posterUrl = if (posterPath.isNullOrEmpty() || posterPath == "null") "" 
                                else "https://image.tmdb.org/t/p/w342$posterPath"

                searchItems.add(
                    newMovieSearchResponse(title, href, type) {
                        this.posterUrl = posterUrl
                        this.quality = getQualityFromString(item.quality ?: "")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("adixtream", "Gagal parse search: ${e.message}")
        }

        return searchItems
    }

    // --- DETAIL FILM / SERIES ---
    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        
        // --- PERBAIKAN: API untuk Movie menggunakan "movies" (ada huruf 's') ---
        val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
        
        val responseText = app.get(apiUrl).text
        val response = AppUtils.parseJson<IdlixDetailResponse>(responseText)
        
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

        if (isSeries) {
            val episodes = arrayListOf<Episode>()
            val seasonNamesList = mutableListOf<SeasonData>()
            
            val totalSeasons = response.numberOfSeasons ?: 1 
            
            // Loop untuk mendapatkan daftar episode dari setiap season
            for (seasonNum in 1..totalSeasons) {
                val seasonApiUrl = "$mainUrl/api/series/$slug/season/$seasonNum"
                try {
                    val seasonResText = app.get(seasonApiUrl).text
                    val parsedSeason = AppUtils.parseJson<IdlixSeasonApiResponse>(seasonResText)
                    
                    val epList = parsedSeason.season?.episodes
                    
                    if (!epList.isNullOrEmpty()) {
                        seasonNamesList.add(SeasonData(seasonNum, "Season $seasonNum"))
                        
                        epList.forEach { ep ->
                            if (ep.hasVideo == true) {
                                val epId = ep.id ?: return@forEach
                                val still = ep.stillPath
                                val epPoster = if (still.isNullOrEmpty() || still == "null") null else "https://image.tmdb.org/t/p/w500$still"
                                
                                // FORMAT LAMA: episode|ID|url
                                val loadData = "episode|$epId|$url"
                                
                                episodes.add(newEpisode(loadData) {
                                    this.name = ep.name
                                    this.season = seasonNum
                                    this.episode = ep.episodeNumber
                                    this.posterUrl = epPoster
                                    this.description = ep.overview
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("adixtream", "Gagal memuat Season $seasonNum: ${e.message}")
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingStr)
                addSeasonNames(seasonNamesList) 
                if (actors != null) addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val movieId = response.id ?: slug
            // FORMAT LAMA: movie|ID|url
            val loadData = "movie|$movieId|$url"
            
            return newMovieLoadResponse(title, url, TvType.Movie, loadData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingStr)
                if (actors != null) addActors(actors)
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
            
            val parts = data.split("|")
            val rawContentType = parts.getOrNull(0) ?: "movie"
            val contentType = rawContentType.substringAfterLast("/")
            val contentId = parts.getOrNull(1) ?: data 
            val refererUrl = parts.getOrNull(2) ?: "$mainUrl/"
            
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
// DATA CLASSES (Diperbarui untuk Next.js API Idlix)
// ============================================================================

data class IdlixHomepageResponse(
    @JsonProperty("above") val above: List<HomepageSection>? = null,
    @JsonProperty("below") val below: List<HomepageSection>? = null
)

data class HomepageSection(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("data") val data: List<HomepageItem>? = null
)

data class HomepageItem(
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("content") val content: ContentData? = null,
    
    // Fallback jika datanya langsung berada di dalam root
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("originalTitle") val originalTitle: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("voteAverage") val voteAverage: String? = null
) {
    fun getActualContent(): ContentData {
        return content ?: ContentData(
            id = id,
            title = title ?: originalTitle,
            slug = slug,
            posterPath = posterPath,
            contentType = contentType,
            quality = quality,
            voteAverage = voteAverage
        )
    }
}

data class ContentData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("originalTitle") val originalTitle: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("voteAverage") val voteAverage: String? = null
)

data class IdlixSearchResponse(
    @JsonProperty("data") val data: List<ContentData>? = null,
    @JsonProperty("results") val results: List<ContentData>? = null
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
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null,
    @JsonProperty("genres") val genres: List<Genre>? = null,
    @JsonProperty("cast") val cast: List<Cast>? = null
)

data class IdlixSeasonApiResponse(
    @JsonProperty("season") val season: SeasonDetail? = null
)

data class SeasonDetail(
    @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeDetail>? = null
)

data class EpisodeDetail(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("stillPath") val stillPath: String? = null,
    @JsonProperty("hasVideo") val hasVideo: Boolean? = null
)

data class Genre(@JsonProperty("name") val name: String? = null)

data class Cast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profilePath") val profilePath: String? = null
)

data class ResponseSource(
    @JsonProperty("videoSource") val videoSource: String = ""
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
