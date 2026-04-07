package com.IdlixProvider

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val responseText = app.get(url).text
        
        val jsonNode = AppUtils.mapper.readTree(responseText)
        val dataArray = jsonNode.get("data") ?: jsonNode.get("results") ?: jsonNode.get("items") ?: jsonNode
        
        if (dataArray == null || !dataArray.isArray) return newHomePageResponse(request.name, emptyList())

        val home = arrayListOf<SearchResponse>()
        for (item in dataArray) {
            val title = item.get("title")?.asText() ?: item.get("name")?.asText() ?: continue
            val slug = item.get("slug")?.asText() ?: continue
            val contentType = item.get("contentType")?.asText() ?: ""
            val type = if (contentType.contains("series")) TvType.TvSeries else TvType.Movie
            
            val href = "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/$slug"
            val posterPath = item.get("posterPath")?.asText()
            val posterUrl = if (posterPath.isNullOrEmpty() || posterPath == "null") "" else "https://image.tmdb.org/t/p/w342$posterPath"
            val quality = item.get("quality")?.asText() ?: ""
            
            home.add(newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            })
        }

        val hasNextPage = (jsonNode.get("pagination")?.get("page")?.asInt() ?: 1) < 
                          (jsonNode.get("pagination")?.get("totalPages")?.asInt() ?: 1)
                          
        return newHomePageResponse(request.name, home, hasNext = hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "utf-8")
        val url = "$mainUrl/api/search?q=$encodedQuery&page=1&limit=36"
        
        val responseText = app.get(url).text
        val jsonNode = AppUtils.mapper.readTree(responseText)
        val dataArray = jsonNode.get("data") ?: jsonNode.get("results") ?: jsonNode.get("items") ?: jsonNode
        
        if (dataArray == null || !dataArray.isArray) return emptyList()

        val searchResults = arrayListOf<SearchResponse>()
        for (item in dataArray) {
            val title = item.get("title")?.asText() ?: item.get("name")?.asText() ?: continue
            val slug = item.get("slug")?.asText() ?: continue
            val contentType = item.get("contentType")?.asText() ?: ""
            val type = if (contentType.contains("series")) TvType.TvSeries else TvType.Movie
            
            val href = "$mainUrl/${if (type == TvType.TvSeries) "series" else "movie"}/$slug"
            val posterPath = item.get("posterPath")?.asText()
            val posterUrl = if (posterPath.isNullOrEmpty() || posterPath == "null") "" else "https://image.tmdb.org/t/p/w342$posterPath"
            
            searchResults.add(newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            })
        }
        
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        
        val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
        val responseText = app.get(apiUrl).text
        
        val json = AppUtils.mapper.readTree(responseText)
            
        val title = json.get("title")?.asText() ?: json.get("name")?.asText() ?: ""
        
        val posterPath = json.get("posterPath")?.asText()
        val poster = if (posterPath.isNullOrEmpty() || posterPath == "null") "" else "https://image.tmdb.org/t/p/w500$posterPath"
        
        val backdropPath = json.get("backdropPath")?.asText()
        val background = if (backdropPath.isNullOrEmpty() || backdropPath == "null") "" else "https://image.tmdb.org/t/p/w1280$backdropPath"
        
        val plot = json.get("overview")?.asText() ?: ""
        
        val dateStr = json.get("releaseDate")?.asText() ?: json.get("firstAirDate")?.asText() ?: ""
        val year = dateStr.split("-").firstOrNull()?.toIntOrNull()
        
        val ratingStr = json.get("voteAverage")?.asText()?.toFloatOrNull()?.times(10)?.toInt()?.toString()
        val trailer = json.get("trailerUrl")?.asText()
        
        val tags = arrayListOf<String>()
        json.get("genres")?.forEach { genre ->
            genre.get("name")?.asText()?.let { tags.add(it) }
        }
        
        val actors = arrayListOf<Actor>()
        json.get("cast")?.forEach { cast ->
            val actorName = cast.get("name")?.asText() ?: return@forEach
            val pPath = cast.get("profilePath")?.asText()
            val profile = if (pPath.isNullOrEmpty() || pPath == "null") null else "https://image.tmdb.org/t/p/w185$pPath"
            actors.add(Actor(actorName, profile))
        }

        if (isSeries) {
            val episodes = arrayListOf<Episode>()
            val firstSeasonId = json.get("firstSeason")?.get("id")?.asText()
            
            json.get("seasons")?.forEach { season ->
                val seasonId = season.get("id")?.asText() ?: return@forEach
                val seasonNumber = season.get("seasonNumber")?.asInt()
                
                if (seasonId == firstSeasonId) {
                    json.get("firstSeason")?.get("episodes")?.forEach { ep ->
                        val epId = ep.get("id")?.asText() ?: ""
                        val still = ep.get("stillPath")?.asText()
                        val epPoster = if (still.isNullOrEmpty() || still == "null") null else "https://image.tmdb.org/t/p/w500$still"
                        
                        episodes.add(newEpisode(epId) {
                            this.name = ep.get("name")?.asText()
                            this.season = seasonNumber
                            this.episode = ep.get("episodeNumber")?.asInt()
                            this.posterUrl = epPoster
                        })
                    }
                } else {
                    val seasonUrl = "$mainUrl/api/seasons/$seasonId"
                    try {
                        val sRes = app.get(seasonUrl).text
                        val sJson = AppUtils.mapper.readTree(sRes)
                        sJson.get("episodes")?.forEach { ep ->
                            val epId = ep.get("id")?.asText() ?: ""
                            val still = ep.get("stillPath")?.asText()
                            val epPoster = if (still.isNullOrEmpty() || still == "null") null else "https://image.tmdb.org/t/p/w500$still"
                            
                            episodes.add(newEpisode(epId) {
                                this.name = ep.get("name")?.asText()
                                this.season = seasonNumber
                                this.episode = ep.get("episodeNumber")?.asInt()
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
                this.score = Score.from100(ratingStr)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val movieId = json.get("id")?.asText() ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, movieId) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from100(ratingStr)
                addActors(actors)
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
        // PERHATIAN: Kode video lama dipertahankan. Ini akan dirombak 
        // kalau kamu sudah mendapatkan log API Play Video dari Idlix yang baru.
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
                val json = AppUtils.mapper.readTree(responseText)
                val embedUrl = json.get("embed_url")?.asText() ?: return@amap
                val key = json.get("key")?.asText() ?: return@amap
                
                val metrixJson = AppUtils.mapper.readTree(embedUrl)
                val metrix = metrixJson.get("m")?.asText() ?: return@amap
                
                val password = createKey(key, metrix)
                val decrypted = AesHelper.cryptoAESHandler(embedUrl, password.toByteArray(), false)?.fixBloat() ?: return@amap
                
                // ERROR .toJson() SUDAH DIHAPUS DI SINI!
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
}

// ============================================================================
// DATA CLASSES TETAP DI LUAR AGAR BISA DIBACA OLEH EXTRACTOR.KT
// ============================================================================

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
