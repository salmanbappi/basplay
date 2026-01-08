package eu.kanade.tachiyomi.animeextension.all.basplay

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import extensions.utils.get
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.URLEncoder

class BasPlay : Source(), ConfigurableAnimeSource {

    override val name = "Bas play"
    override val baseUrl = "http://103.87.212.46"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419847L

    override val client: OkHttpClient = network.client

    private val cursorCache = mutableMapOf<Int, String>()

    override suspend fun getPopularAnime(page: Int): AnimesPage = getLatestUpdates(page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page == 1) {
            cursorCache.clear()
            val response = client.newCall(GET(baseUrl)).execute()
            val doc = response.asJsoup()
            val cursor = doc.selectFirst("#feedState")?.attr("data-cursor")
            if (cursor != null) cursorCache[2] = cursor
            val items = doc.select("div#dateFeed a.cp-card")
            return parseBasAnimeListItems(items, hasNextPage = cursor != null)
        } else {
            val cursor = cursorCache[page] ?: return AnimesPage(emptyList(), false)
            val url = "$baseUrl/fetch_more.php?cursor=$cursor"
            val response = client.newCall(GET(url).newBuilder().addHeader("X-Requested-With", "fetch").build()).execute()
            try {
                val jsonObject = json.decodeFromString<JsonObject>(response.body.string())
                val html = jsonObject["html"]?.jsonPrimitive?.content ?: ""
                val nextCursor = jsonObject["next_cursor"]?.jsonPrimitive?.contentOrNull
                if (!nextCursor.isNullOrBlank()) cursorCache[page + 1] = nextCursor
                val doc = Jsoup.parseBodyFragment(html)
                val items = doc.select("a.cp-card")
                return parseBasAnimeListItems(items, hasNextPage = !nextCursor.isNullOrBlank())
            } catch (e: Exception) {
                return AnimesPage(emptyList(), false)
            }
        }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        var url: String = if (query.isNotBlank()) {
            "$baseUrl/search.php?q=$query"
        } else {
            var category = ""
            var isTv = false
            filters.forEach { filter ->
                when (filter) {
                    is MovieCategoryFilter -> if (filter.toValue().isNotEmpty()) category = filter.toValue()
                    is TvCategoryFilter -> if (filter.toValue().isNotEmpty()) { category = filter.toValue(); isTv = true }
                    else -> {}
                }
            }
            if (isTv) "$baseUrl/tv.php?category=$category" else if (category.isNotEmpty()) "$baseUrl/category.php?category=$category" else baseUrl
        }
        val separator = if (url.contains("?")) "&" else "?"
        url += "${separator}page=$page"
        val response = client.newCall(GET(url)).execute()
        val doc = response.asJsoup()
        val items = doc.select("div.grid a.cp-card, div.grid a[href^='view.php'], div.grid a[href^='tview.php'], a.cp-card")
        val hasNextPage = doc.selectFirst("nav a:contains(Next), nav a[href*='page=${page + 1}']") != null
        return parseBasAnimeListItems(items, hasNextPage)
    }

    private fun parseBasAnimeListItems(items: org.jsoup.select.Elements, hasNextPage: Boolean): AnimesPage {
        val seenUrls = mutableSetOf<String>()
        val animeList = mutableListOf<SAnime>()
        val episodeRegex = Regex("""^(.*?) S(\d+)E(\d+)""", RegexOption.IGNORE_CASE)
        for (item in items) {
            var url = item.attr("href")
            var title = item.selectFirst("div.cp-title, h2, div.cap")?.text() ?: item.attr("title") ?: ""
            if (url.isBlank() || title.isBlank()) continue
            val img = item.selectFirst("img")
            val imgSrc = img?.attr("src") ?: img?.attr("data-src")
            val match = episodeRegex.find(title)
            if (match != null) {
                val seriesName = match.groupValues[1].trim()
                try {
                    val encodedName = URLEncoder.encode(seriesName, "UTF-8")
                    url = "tview.php?series=$encodedName"
                    title = seriesName
                } catch (e: Exception) {}
            }
            if (seenUrls.contains(url)) continue
            seenUrls.add(url)
            animeList.add(SAnime.create().apply {
                this.url = url
                this.title = title
                this.thumbnail_url = if (imgSrc != null) fixUrl(imgSrc) else null
            })
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl/${anime.url}")).execute()
        val doc = response.asJsoup()
        return anime.apply {
            description = doc.selectFirst("p.leading-relaxed, p.text-slate-800")?.text()
            genre = doc.select("span.chip").joinToString { it.text() }
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl/${anime.url}")).execute()
        val doc = response.asJsoup()
        val isMovie = anime.url.contains("view.php") && !anime.url.contains("tview.php")
        if (isMovie) {
            val videoLink = doc.selectFirst("a#dlBtn")?.attr("href")
                ?: doc.selectFirst("a.cta, a[href^='player.php']")?.attr("href")
                ?: doc.selectFirst("video source")?.attr("src")
                ?: anime.url
            return listOf(SEpisode.create().apply {
                name = "Play Movie"
                this.url = videoLink ?: anime.url
                episode_number = 1F
            })
        } else {
            val episodes = mutableListOf<SEpisode>()
            parseEpisodesFromDoc(doc, episodes)
            val seasonOptions = doc.select("select#seasonSelect option")
            if (seasonOptions.size > 1) {
                val currentSeason = doc.selectFirst("select#seasonSelect option[selected]")?.attr("value") ?: "1"
                val seriesName = doc.selectFirst("h1.sec-title")?.text() ?: "Series"
                val encodedSeries = URLEncoder.encode(seriesName, "UTF-8")
                seasonOptions.forEach { opt ->
                    val seasonVal = opt.attr("value")
                    if (seasonVal != currentSeason) {
                        try {
                            val seasonUrl = "$baseUrl/tview.php?series=$encodedSeries&season=$seasonVal"
                            val seasonDoc = client.newCall(GET(seasonUrl)).execute().asJsoup()
                            parseEpisodesFromDoc(seasonDoc, episodes)
                        } catch (e: Exception) {}
                    }
                }
            }
            return episodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
        }
    }

    private fun parseEpisodesFromDoc(doc: Document, targetList: MutableList<SEpisode>) {
        val epItems = doc.select("a.ep-item")
        epItems.forEach { element ->
            val epUrl = element.attr("data-src")
            val epNameRaw = element.selectFirst("div.text-sm")?.text() ?: element.text()
            val epNum = element.attr("data-epnum").toFloatOrNull()
            val match = Regex("""S(\d+)E(\d+)""", RegexOption.IGNORE_CASE).find(epNameRaw)
            val finalEpNum = if (match != null) {
                val season = match.groupValues[1].toInt()
                val episode = match.groupValues[2].toInt()
                if (epNum != null) epNum else (season * 1000 + episode).toFloat()
            } else { epNum ?: 0F }
            if (epUrl.isNotBlank()) {
                targetList.add(SEpisode.create().apply {
                    this.name = epNameRaw
                    this.url = if (epUrl.startsWith("http") || epUrl.startsWith("/")) epUrl else "/$epUrl"
                    this.episode_number = finalEpNum
                })
            }
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        var url = if (episode.url.startsWith("http")) episode.url else fixUrl(episode.url)
        if (url.contains("player.php")) {
            val response = client.newCall(GET(url)).execute()
            val doc = response.asJsoup()
            val videoSrc = doc.selectFirst("video source")?.attr("src")
            if (videoSrc != null) url = fixUrl(videoSrc)
        }
        val encodedUrl = url.replace(" ", "%20").replace("&", "%26")
        return listOf(Video(encodedUrl, "Direct", encodedUrl))
    }

    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Use only one filter at a time"),
        MovieCategoryFilter(),
        TvCategoryFilter()
    )

    private open class SelectFilter(name: String, val items: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, items.map { it.first }.toTypedArray()) {
        fun toValue() = items[state].second
    }

    private class MovieCategoryFilter : SelectFilter("Movies", arrayOf(
        "None" to "", "Animation" to "Animation", "Bangla" to "Bangla", "Bollywood" to "Bollywood", 
        "Hollywood" to "Hollywood", "Chinese" to "Chinese", "Korean" to "Korean", 
        "South Indian" to "South+Indian", "Dubbed Movie" to "Dubbed+Movie"
    ))

    private class TvCategoryFilter : SelectFilter("TV Shows", arrayOf(
        "None" to "", "ANIMATED TV SERIES" to "ANIMATED+TV+SERIES", "ENGLISH TV SERIES" to "ENGLISH+TV+SERIES", 
        "HINDI TV SERIES" to "HINDI+TV+SERIES", "BANGLA TV SERIES" to "BANGLA+TV+SERIES", 
        "CHINESE TV SERIES" to "CHINESE+TV+SERIES", "JAPANES TV SERIES" to "JAPANES+TV+SERIES", 
        "KOREAN TV SERIES" to "KOREAN+TV+SERIES", "TURKISH TV SERIES" to "TURKISH+TV+SERIES", "UNDEFINE" to "UNDEFINE"
    ))

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}