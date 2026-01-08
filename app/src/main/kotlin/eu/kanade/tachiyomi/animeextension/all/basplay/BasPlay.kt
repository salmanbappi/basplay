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

class BasPlay : Source(), ConfigurableAnimeSource {

    override val name = "Bas play"
    override val baseUrl = "http://103.87.212.46"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 20824849062123456L

    override val client: OkHttpClient = network.client

    // Popular = Hero Slider items
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val response = client.newCall(GET(baseUrl)).execute()
        val doc = response.asJsoup()
        val items = doc.select("div.vs-card")
        val animeList = items.mapNotNull { item ->
            val link = item.closest("a") ?: return@mapNotNull null
            val url = link.attr("href")
            val title = item.selectFirst("div.cap")?.text() ?: ""
            val imgSrc = item.selectFirst("img")?.attr("src")

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

    // Latest = Latest Uploads section
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        // The site uses fetch_more.php?cursor=... for pagination, but for now let's just get page 1 from index
        val response = client.newCall(GET(baseUrl)).execute()
        return parseBasAnimeList(response.asJsoup())
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search.php?q=$query"
            val response = client.newCall(GET(url)).execute()
            return parseBasAnimeList(response.asJsoup())
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
        return parseBasAnimeList(response.asJsoup())
    }

    private fun parseBasAnimeList(document: Document): AnimesPage {
        val items = document.select("div.cp-card, div.grid a[href^='view.php']")
        val seenUrls = mutableSetOf<String>()
        val animeList = items.mapNotNull { item ->
            val url = if (item.tagName() == "a") item.attr("href") else item.closest("a")?.attr("href") ?: item.selectFirst("a")?.attr("href") ?: ""
            if (url.isBlank() || seenUrls.contains(url)) return@mapNotNull null
            
            // Skip slider items if we're parsing from home
            if (item.parents().any { it.hasClass("vs-track") }) return@mapNotNull null

            val title = item.selectFirst("div.cp-title")?.text() 
                ?: item.selectFirst("h2")?.text()
                ?: item.attr("title")
                ?: ""
            
            val img = item.selectFirst("img")
            val imgSrc = img?.attr("src") ?: img?.attr("data-src")

            if (title.isNotEmpty()) {
                seenUrls.add(url)
                SAnime.create().apply {
                    this.url = url
                    this.title = title
                    this.thumbnail_url = if (imgSrc != null) fixUrl(imgSrc) else null
                }
            } else null
        }
        // Site pagination is a bit complex with 'cursor', for now supports only first page or search results
        return AnimesPage(animeList, false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl/${anime.url}")).execute()
        val doc = response.asJsoup()
        
        return anime.apply {
            if (anime.url.startsWith("view.php")) {
                description = doc.selectFirst("p.leading-relaxed")?.text()
                genre = doc.select("div.flex.flex-wrap.items-center.gap-3 span").joinToString { it.text() }
                status = SAnime.COMPLETED
            } else {
                // TV Series details from tview.php or tv.php
                description = doc.selectFirst("p.text-slate-800")?.text() ?: doc.selectFirst("p.leading-relaxed")?.text()
                genre = doc.select("span.chip").joinToString { it.text() }
            }
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl/${anime.url}")).execute()
        val doc = response.asJsoup()
        
        if (anime.url.startsWith("view.php")) {
            // Movie
            val videoLink = doc.selectFirst("a.cta[href^='player.php']")?.attr("href")
                ?: doc.selectFirst("a.btn-primary[href^='download.php']")?.attr("href")
                ?: anime.url
            
            return listOf(SEpisode.create().apply {
                name = "Play Movie"
                this.url = videoLink
                episode_number = 1F
            })
        } else {
            // TV Series
            val episodes = mutableListOf<SEpisode>()
            val epItems = doc.select("a.ep-item")
            
            // If multiple seasons exist, we might only be seeing one.
            // But for simplicity, let's parse what's on the page.
            epItems.forEachIndexed { index, element ->
                val epUrl = element.attr("data-src")
                val epName = element.selectFirst("div.text-sm")?.text() ?: "Episode ${index + 1}"
                val epNum = element.attr("data-epnum").toFloatOrNull() ?: (index + 1).toFloat()
                
                episodes.add(SEpisode.create().apply {
                    this.name = epName
                    this.url = if (epUrl.startsWith("/")) epUrl else "/$epUrl" // Ensure absolute-ish path
                    this.episode_number = epNum
                })
            }
            
            if (episodes.isEmpty()) {
                // Fallback for some series layouts
                val videoLink = doc.selectFirst("video source")?.attr("src")
                if (videoLink != null) {
                    episodes.add(SEpisode.create().apply {
                        name = "Play"
                        this.url = videoLink
                        episode_number = 1F
                    })
                }
            }
            
            return episodes.reversed()
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = if (episode.url.startsWith("http")) {
            episode.url
        } else {
            "$baseUrl${if (episode.url.startsWith("/")) "" else "/"}${episode.url}"
        }
        
        if (url.contains("player.php")) {
            val response = client.newCall(GET(url)).execute()
            val doc = response.asJsoup()
            val videoSrc = doc.selectFirst("video source")?.attr("src")
            if (videoSrc != null) {
                val finalUrl = if (videoSrc.startsWith("http")) videoSrc else "$baseUrl${if (videoSrc.startsWith("/")) "" else "/"}$videoSrc"
                return listOf(Video(finalUrl, "Direct", finalUrl))
            }
        }
        
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
