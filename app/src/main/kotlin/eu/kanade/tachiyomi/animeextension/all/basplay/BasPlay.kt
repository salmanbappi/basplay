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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.serialization.json.Json
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

    // Popular = Trending Now section (Horizontal scroll)
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val response = client.newCall(GET(baseUrl)).execute()
        val doc = response.asJsoup()
        // Select items from the "Trending Now" section
        val items = doc.select("div.trend-wrap a.trend-card")
        val animeList = items.mapNotNull { item ->
            val url = item.attr("href")
            val title = item.selectFirst("div.cp-title")?.text() ?: item.attr("title") ?: ""
            val imgSrc = item.selectFirst("img")?.attr("src") ?: item.selectFirst("img")?.attr("data-src")

            if (url.isNotEmpty() && title.isNotEmpty()) {
                SAnime.create().apply {
                    this.url = url
                    this.title = title
                    this.thumbnail_url = if (imgSrc != null) fixUrl(imgSrc) else null
                }
            } else null
        }
        return AnimesPage(animeList, false)
    }

    // Latest = Latest Uploads section (Grid)
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET(baseUrl)).execute()
        val doc = response.asJsoup()
        // Select items from the "Latest Uploads" grid
        val items = doc.select("div#dateFeed a.cp-card")
        return parseBasAnimeListItems(items)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search.php?q=$query"
            val response = client.newCall(GET(url)).execute()
            val doc = response.asJsoup()
            // Search results are simple links in a grid
            val items = doc.select("div.grid a[href^='view.php'], div.grid a[href^='tview.php']")
            return parseBasAnimeListItems(items, isSearch = true)
        }

        var category = ""
        var isTv = false
        filters.forEach { filter ->
            when (filter) {
                is MovieCategoryFilter -> {
                    if (filter.toValue().isNotEmpty()) category = filter.toValue()
                }
                is TvCategoryFilter -> {
                    if (filter.toValue().isNotEmpty()) {
                        category = filter.toValue()
                        isTv = true
                    }
                }
                else -> {}
            }
        }

        val url = if (isTv) {
            "$baseUrl/tv.php?category=$category"
        } else if (category.isNotEmpty()) {
            "$baseUrl/category.php?category=$category"
        } else {
            baseUrl
        }

        val response = client.newCall(GET(url)).execute()
        // Browse results are in a grid similar to Latest
        val doc = response.asJsoup()
        val items = doc.select("div.grid a.cp-card, div.grid a[href^='view.php']")
        return parseBasAnimeListItems(items)
    }

    private fun parseBasAnimeListItems(items: org.jsoup.select.Elements, isSearch: Boolean = false): AnimesPage {
        val seenUrls = mutableSetOf<String>()
        val animeList = mutableListOf<SAnime>()
        val episodeRegex = Regex("""^(.*?) S(\d+)E(\d+)""", RegexOption.IGNORE_CASE)

        for (item in items) {
            var url = item.attr("href")
            var title = item.selectFirst("div.cp-title")?.text() 
                ?: item.selectFirst("h2")?.text()
                ?: item.attr("title")
                ?: ""
            
            val img = item.selectFirst("img")
            val imgSrc = img?.attr("src") ?: img?.attr("data-src")

            if (url.isBlank() || title.isBlank()) continue

            // Fix for search results showing episodes: Convert to Series entry
            if (isSearch) {
                val match = episodeRegex.find(title)
                if (match != null) {
                    val seriesName = match.groupValues[1].trim()
                    // If we found an episode, redirect to the Series page
                    try {
                        val encodedName = URLEncoder.encode(seriesName, "UTF-8")
                        url = "tview.php?series=$encodedName"
                        title = seriesName // Use clean series name
                    } catch (e: Exception) {
                        // Fallback to original if encoding fails
                    }
                }
            }

            if (seenUrls.contains(url)) continue
            seenUrls.add(url)

            animeList.add(SAnime.create().apply {
                this.url = url
                this.title = title
                this.thumbnail_url = if (imgSrc != null) fixUrl(imgSrc) else null
            })
        }
        return AnimesPage(animeList, false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl/${anime.url}")).execute()
        val doc = response.asJsoup()
        
        return anime.apply {
            description = doc.selectFirst("p.leading-relaxed, p.text-slate-800")?.text()
            genre = doc.select("span.chip").joinToString { it.text() }
            status = SAnime.COMPLETED // Default
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl/${anime.url}")).execute()
        val doc = response.asJsoup()
        
        if (anime.url.contains("view.php")) {
            // Movie - Single Episode
            val videoLink = doc.selectFirst("a#dlBtn")?.attr("href")
                ?: doc.selectFirst("a[href^='player.php']")?.attr("href")
                ?: doc.selectFirst("video source")?.attr("src")
                ?: anime.url
            
            return listOf(SEpisode.create().apply {
                name = "Play Movie"
                this.url = videoLink ?: anime.url
                episode_number = 1F
            })
        } else {
            // TV Series - Handle multiple seasons
            val episodes = mutableListOf<SEpisode>()
            
            // 1. Parse current page (usually Season 1)
            parseEpisodesFromDoc(doc, episodes)
            
            // 2. Check for Season Selector
            val seasonOptions = doc.select("select#seasonSelect option")
            if (seasonOptions.size > 1) {
                // If there are other seasons, we need to fetch them
                // The current page is already parsed (usually the first option or selected option)
                val currentSeason = doc.selectFirst("select#seasonSelect option[selected]")?.attr("value") ?: "1"
                
                // We need the series name to construct URLs
                // It's usually in the URL: tview.php?series=Name
                val seriesName = doc.selectFirst("h1.sec-title")?.text() ?: "Series"
                val encodedSeries = URLEncoder.encode(seriesName, "UTF-8")

                seasonOptions.forEach { opt ->
                    val seasonVal = opt.attr("value")
                    if (seasonVal != currentSeason) {
                        try {
                            // Construct URL: tview.php?series=Name&season=Val
                            val seasonUrl = "$baseUrl/tview.php?series=$encodedSeries&season=$seasonVal"
                            val seasonDoc = client.newCall(GET(seasonUrl)).execute().asJsoup()
                            parseEpisodesFromDoc(seasonDoc, episodes)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            
            // Deduplicate and sort
            return episodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
        }
    }

    private fun parseEpisodesFromDoc(doc: Document, targetList: MutableList<SEpisode>) {
        val epItems = doc.select("a.ep-item")
        epItems.forEach { element ->
            val epUrl = element.attr("data-src")
            val epNameRaw = element.selectFirst("div.text-sm")?.text() ?: element.text()
            // Try to extract strict episode number from data-epnum, else use index or regex
            val epNum = element.attr("data-epnum").toFloatOrNull()
            // Sometimes epNum is just relative index (1, 2, 3), we might want S*E* logic
            // But usually the name contains "S01E04"
            
            // Extract S and E for better ordering if possible
            val match = Regex("""S(\d+)E(\d+)""", RegexOption.IGNORE_CASE).find(epNameRaw)
            val finalEpNum = if (match != null) {
                // Encode as Season.Episode (e.g. S1E4 -> 1.04) or just absolute count if you prefer
                // Standard approach: use absolute or just keep what we found.
                // Let's stick to the data-epnum if valid, else fallback
                epNum ?: 0F
            } else {
                epNum ?: 0F
            }

            if (epUrl.isNotBlank()) {
                targetList.add(SEpisode.create().apply {
                    this.name = epNameRaw
                    this.url = if (epUrl.startsWith("http") || epUrl.startsWith("/")) epUrl else "/$epUrl"
                    this.episode_number = finalEpNum
                    this.date_upload = 0L // No date in list usually
                })
            }
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = if (episode.url.startsWith("http")) {
            episode.url
        } else {
            "$baseUrl${if (episode.url.startsWith("/")) "" else "/"}${episode.url}"
        }
        
        // If it's a direct file link (MP4/MKV), just play it
        // The new site uses data-src pointing to .mkv/.mp4 files directly
        return listOf(Video(url, "Direct", url))
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"
    }

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
