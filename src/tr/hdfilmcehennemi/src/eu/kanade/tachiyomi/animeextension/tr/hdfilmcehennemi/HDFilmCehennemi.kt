package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.RapidrameExtractor
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.VidmolyExtractor
import eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi.extractors.XBetExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class HDFilmCehennemi : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HDFilmCehennemi"

    override val baseUrl = "https://www.hdfilmcehennemi.de"

    override val lang = "tr"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/en-cok-begenilen-filmleri-izle/page/$page/")

    override fun popularAnimeSelector() = "div.row div.poster > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("h2.title")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("data-src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li > a[rel=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page/")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun getFilterList() = HDFilmCehennemiFilters.FILTER_LIST

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val headers = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return when {
            query.isNotBlank() -> {
                val body = FormBody.Builder().add("query", query).build()

                POST("$baseUrl/search/", headers, body)
            }
            else -> {
                val params = HDFilmCehennemiFilters.getSearchParameters(filters)

                val form = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("kesfet[type]", params.type)
                    .addFormDataPart("kesfet[genres]", params.genres)
                    .addFormDataPart("kesfet[years]", params.years)
                    .addFormDataPart("kesfet[imdb]", params.imdbScore)
                    .addFormDataPart("kesfet[orderBy]", params.order)
                    .addFormDataPart("page", page.toString())
                    .build()

                POST("$baseUrl/movies/load/", headers, form)
            }
        }
    }

    @Serializable
    data class SearchResponse(val result: List<ItemDto>)

    @Serializable
    data class ItemDto(val title: String, val poster: String, val slug: String, val slug_prefix: String)

    @Serializable
    data class FilterSearchResponse(val html: String, val showMore: Boolean, val status: Int)

    override fun searchAnimeParse(response: Response): AnimesPage {
        return when {
            response.request.url.toString().contains("/search/") -> { // Text search
                val data = response.parseAs<SearchResponse>()
                val items = data.result.map {
                    SAnime.create().apply {
                        title = it.title
                        thumbnail_url = "$baseUrl/uploads/poster/" + it.poster
                        url = "/" + it.slug_prefix + it.slug
                    }
                }

                AnimesPage(items, false)
            }

            else -> { // Filter search
                val data = response.parseAs<FilterSearchResponse>()
                if (data.status != 1) return AnimesPage(emptyList(), false)

                val doc = response.asJsoup(data.html)
                val items = doc.select(searchAnimeSelector()).map(::searchAnimeFromElement)

                AnimesPage(items, data.showMore)
            }
        }
    }

    override fun searchAnimeSelector() = "div.poster > a"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        status = when {
            document.location().contains("/dizi/") -> SAnime.UNKNOWN // serie
            else -> SAnime.COMPLETED // movie
        }

        val div = document.selectFirst("div.card-body > div.row")!!

        div.selectFirst("img")!!.run {
            thumbnail_url = absUrl("src")
            title = attr("alt")
        }

        genre = div.select("div > a[href*=tur/]").eachText().joinToString().takeIf(String::isNotEmpty)
        artist = div.select("a.chip[href*=oyuncu/]").eachText().joinToString().takeIf(String::isNotEmpty)

        description = div.selectFirst("article > p")?.text()
    }

    // ============================== Episodes ==============================
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        // Series
        if (anime.url.contains("/dizi/")) return super.fetchEpisodeList(anime)

        // Movies
        return Observable.just(
            listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = "Movie"
                    episode_number = 1F
                },
            ),
        )
    }

    override fun episodeListParse(response: Response) =
        super.episodeListParse(response).sortedByDescending { it.episode_number }

    override fun episodeListSelector() = "div#seasonsTabs-tabContent div.card-list-item > a"

    private val numberRegex by lazy { Regex("(\\d+)\\.") }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))

        name = element.selectFirst("h3")!!.text()

        date_upload = element.selectFirst("date")?.attr("datetime")?.toDate() ?: 0L

        val (seasonNum, epNum) = numberRegex.findAll(name).map { it.groupValues.last() }.toList()
        // good luck trying to track this xD
        episode_number = "$seasonNum.${epNum.padStart(3, '0')}".toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    private val vidmolyExtractor by lazy { VidmolyExtractor(client, headers) }
    private val rapidrameExtractor by lazy { RapidrameExtractor(client, headers, json) }
    private val xbetExtractor by lazy { XBetExtractor(client, headers, json) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }

        return doc.select("div.card-body > nav > a:not([href^=#])")
            .drop(1)
            .parallelMap { client.newCall(GET(it.absUrl("href") + "/")).execute().use { it.asJsoup() } }
            .let { listOf(doc) + it }
            .mapNotNull { it.selectFirst("div.card-video > iframe")?.attr("data-src") }
            .filter(String::isNotBlank)
            .parallelMap { url ->
                runCatching {
                    when {
                        url.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(url)
                        url.contains("$baseUrl/playerr") -> rapidrameExtractor.videosFromUrl(url)
                        url.contains("trstx.org") -> xbetExtractor.videosFromUrl(url)
                        else -> emptyList()
                    }
                }.getOrNull().orEmpty()
            }.flatten().ifEmpty { throw Exception("No videos available xD") }
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T {
        return use { it.body.string() }.let(json::decodeFromString)
    }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "480p", "720p", "1080p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
