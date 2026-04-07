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
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?sort=createdAt&limit=36&page=" to "Movie Terbaru",
        "$mainUrl/api/trending/top?contentType=movie&limit=36&period=7d&page=" to "Trending Movies",
        "$mainUrl/api/trending/top?contentType=series&limit=36&period=7d&page=" to "Trending Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
        } catch (e: Exception) { emptyList() }
        
        val home = items.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val type = if (item.contentType?.contains("series", true) == true) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(title, "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/${item.slug}", type) {
                this.posterUrl = if (item.posterPath.isNullOrEmpty()) "" else "https://image.tmdb.org/t/p/w342${item.posterPath}"
                this.quality = getQualityFromString(item.quality ?: "")
            }
        }
        return newHomePageResponse(request.name, home, hasNext = hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=${java.net.URLEncoder.encode(query, "utf-8")}&page=1&limit=36"
        val responseText = app.get(url).text
        val items = try {
            if (responseText.trim().startsWith("[")) AppUtils.parseJson<List<IdlixItem>>(responseText)
            else AppUtils.parseJson<IdlixApiResponse>(responseText).let { it.data ?: it.results ?: it.items ?: emptyList() }
        } catch (e: Exception) { emptyList() }

        return items.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val type = if (item.contentType?.contains("series", true) == true) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(title, "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/${item.slug}", type) {
                this.posterUrl = if (item.posterPath.isNullOrEmpty()) "" else "https://image.tmdb.org/t/p/w342${item.posterPath}"
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        val response = app.get("$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug").parsedSafe<IdlixDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal load API Detail")
            
        val title = response.title ?: response.name ?: ""
        val poster = "https://image.tmdb.org/t/p/w500${response.posterPath}"
        val actors = response.cast?.mapNotNull { Actor(it.name ?: "", if (it.profilePath.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/w185${it.profilePath}") }

        return if (isSeries) {
            val episodes = arrayListOf<Episode>()
            response.seasons?.forEach { season ->
                val sNum = season.seasonNumber
                app.get("$mainUrl/api/seasons/${season.id}").parsedSafe<Season>()?.episodes?.forEach { ep ->
                    episodes.add(newEpisode(ep.id ?: "") {
                        this.name = ep.name; this.season = sNum; this.episode = ep.episodeNumber
                        this.posterUrl = "https://image.tmdb.org/t/p/w500${ep.stillPath}"
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = response.overview; addActors(actors); addTrailer(response.trailerUrl)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, response.id ?: url) {
                this.posterUrl = poster; this.plot = response.overview; addActors(actors); addTrailer(response.trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val clearanceToken = app.post("$mainUrl/api/adblock/clearance").parsedSafe<TokenResponse>()?.token 
                                ?: app.post("$mainUrl/api/adblock/clearance").text.trim().replace("\"", "")

            val challengeRes = app.post(
                url = "$mainUrl/api/watch/challenge",
                data = mapOf("contentType" to "movie", "contentId" to data, "clearance" to clearanceToken),
                headers = mapOf("Content-Type" to "application/json", "Referer" to "$mainUrl/")
            ).parsedSafe<ChallengeResponse>() ?: return false

            val challenge = challengeRes.challenge ?: return false
            val signature = challengeRes.signature ?: ""
            val diff = challengeRes.difficulty ?: 4

            var nonce = 0
            val target = "0".repeat(diff)
            val md = MessageDigest.getInstance("SHA-256")
            while (nonce < 2000000) {
                val input = "$challenge$nonce"
                val hash = md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
                if (hash.startsWith(target)) break
                nonce++
            }

            val solveRes = app.post(
                url = "$mainUrl/api/watch/solve",
                data = mapOf("challenge" to challenge, "signature" to signature, "nonce" to nonce.toString()),
                headers = mapOf("Content-Type" to "application/json", "Referer" to "$mainUrl/")
            ).parsedSafe<SolveResponse>()

            val embedPath = solveRes?.embedUrl ?: solveRes?.url ?: return false
            val fullEmbedUrl = if (embedPath.startsWith("http")) embedPath else "$mainUrl$embedPath"

            val embedHtml = app.get(fullEmbedUrl, referer = "$mainUrl/").text
            val jeniusLink = """https://jeniusplay\.com/video/[a-zA-Z0-9]+""".toRegex().find(embedHtml)?.value
            
            if (jeniusLink != null) {
                loadExtractor(jeniusLink, fullEmbedUrl, subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) { }
        return false
    }
}

data class TokenResponse(@JsonProperty("token") val token: String? = null)
data class ChallengeResponse(
    @JsonProperty("challenge") val challenge: String? = null,
    @JsonProperty("signature") val signature: String? = null,
    @JsonProperty("difficulty") val difficulty: Int? = null
)
data class SolveResponse(
    @JsonProperty("embedUrl") val embedUrl: String? = null,
    @JsonProperty("url") val url: String? = null
)
data class IdlixApiResponse(
    @JsonProperty("data") val data: List<IdlixItem>? = null,
    @JsonProperty("results") val results: List<IdlixItem>? = null,
    @JsonProperty("items") val items: List<IdlixItem>? = null,
    @JsonProperty("pagination") val pagination: Pagination? = null
)
data class Pagination(@JsonProperty("page") val page: Int? = null, @JsonProperty("totalPages") val totalPages: Int? = null)
data class IdlixItem(
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
    @JsonProperty("trailerUrl") val trailerUrl: String? = null,
    @JsonProperty("cast") val cast: List<Cast>? = null,
    @JsonProperty("seasons") val seasons: List<Season>? = null
)
data class Cast(@JsonProperty("name") val name: String? = null, @JsonProperty("profilePath") val profilePath: String? = null)
data class Season(@JsonProperty("id") val id: String? = null, @JsonProperty("seasonNumber") val seasonNumber: Int? = null, @JsonProperty("episodes") val episodes: List<EpisodeData>? = null)
data class EpisodeData(@JsonProperty("id") val id: String? = null, @JsonProperty("episodeNumber") val episodeNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("stillPath") val stillPath: String? = null)
data class ResponseSource(@JsonProperty("videoSource") val videoSource: String = "")
data class Tracks(@JsonProperty("file") val file: String = "", @JsonProperty("label") val label: String? = null)
