package mihon.feature.volumes

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat

/**
 * Looks up the chapters of each volume of a series: on MangaUpdates first, from the releases that name their volume,
 * then on Wikipedia, from the chapter lists of its volumes.
 */
@Inject
class VolumeLookup(
    private val network: NetworkHelper,
    private val json: Json,
) {

    data class Result(
        val mangaUpdatesId: Long?,
        val mangaUpdatesTitle: String?,
        val wikipediaPage: String?,
        val fandomPage: String?,
        val mangaUpdates: Map<String, IntRange>,
        val wikipedia: Map<String, IntRange>,
        val fandom: Map<String, IntRange>,
    ) {
        val isEmpty: Boolean
            get() = mangaUpdates.isEmpty() && wikipedia.isEmpty() && fandom.isEmpty()
    }

    private val client get() = network.client

    private val headers = Headers.Builder().add("User-Agent", USER_AGENT).build()

    /**
     * Looks up [query], or the MangaUpdates series [mangaUpdatesId] when the manga is already tracked there. A link
     * to a Fandom page as [query] reads only that page.
     */
    suspend fun lookup(query: String, mangaUpdatesId: Long? = null): Result = withContext(Dispatchers.IO) {
        FandomVolumeParser.parseUrl(query)?.let { (wiki, page) ->
            val fandom = runCatching { fandomVolumes(wiki, page) }
                .onFailure { logcat(LogPriority.WARN, it) { "Fandom page failed" } }
                .getOrNull()
                .orEmpty()
            return@withContext Result(
                mangaUpdatesId = null,
                mangaUpdatesTitle = null,
                wikipediaPage = null,
                fandomPage = query.trim().takeIf { fandom.isNotEmpty() },
                mangaUpdates = emptyMap(),
                wikipedia = emptyMap(),
                fandom = fandom,
            )
        }

        val series = mangaUpdatesId?.let { it to null }
            ?: runCatching { searchMangaUpdates(query) }
                .onFailure { logcat(LogPriority.WARN, it) { "MangaUpdates search failed" } }
                .getOrNull()
        val releases = series?.let { (id, _) ->
            runCatching { mangaUpdatesReleases(id) }
                .onFailure { logcat(LogPriority.WARN, it) { "MangaUpdates releases failed" } }
                .getOrNull()
        }.orEmpty()

        val titles = listOfNotNull(query, series?.second).distinct()
        val wikipedia = runCatching { searchWikipedia(titles) }
            .onFailure { logcat(LogPriority.WARN, it) { "Wikipedia lookup failed" } }
            .getOrNull()
        val fandom = runCatching { searchFandom(titles) }
            .onFailure { logcat(LogPriority.WARN, it) { "Fandom lookup failed" } }
            .getOrNull()

        Result(
            mangaUpdatesId = series?.first,
            mangaUpdatesTitle = series?.second,
            wikipediaPage = wikipedia?.first,
            fandomPage = fandom?.first,
            mangaUpdates = VolumeMappingBuilder.fromReleases(releases),
            wikipedia = wikipedia?.second.orEmpty(),
            fandom = fandom?.second.orEmpty(),
        )
    }

    /**
     * The MangaUpdates series whose title is closest to [query], as its id and title.
     */
    private suspend fun searchMangaUpdates(query: String): Pair<Long, String?>? {
        val body = buildJsonObject {
            put("search", query)
            put("perpage", 10)
        }
        val results = postJson("$MANGAUPDATES_URL/v1/series/search", body)["results"]?.jsonArray.orEmpty()
        return results
            .mapNotNull { result ->
                val record = result.jsonObject["record"]?.jsonObject ?: return@mapNotNull null
                val id = record["series_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val title = record.string("title")
                val hitTitle = result.jsonObject.string("hit_title")
                val score = maxOf(
                    title?.let { VolumeMappingBuilder.similarity(it, query) } ?: 0.0,
                    hitTitle?.let { VolumeMappingBuilder.similarity(it, query) } ?: 0.0,
                )
                Triple(id, title, score)
            }
            .maxByOrNull { it.third }
            ?.takeIf { it.third >= MIN_SIMILARITY }
            ?.let { (id, title) -> id to title }
    }

    /**
     * The volume and chapter of every release of the MangaUpdates series [seriesId].
     */
    private suspend fun mangaUpdatesReleases(seriesId: Long): List<Pair<String?, String?>> {
        val releases = mutableListOf<Pair<String?, String?>>()
        for (page in 1..MANGAUPDATES_MAX_PAGES) {
            val body = buildJsonObject {
                put("search", seriesId.toString())
                put("search_type", "series")
                put("page", page)
                put("perpage", 100)
            }
            val results = postJson("$MANGAUPDATES_URL/v1/releases/search", body)["results"]?.jsonArray.orEmpty()
            results.forEach { result ->
                val releaseSeries = result.jsonObject["metadata"]?.jsonObject
                    ?.get("series")?.jsonObject
                    ?.get("series_id")?.jsonPrimitive?.longOrNull
                if (releaseSeries != null && releaseSeries != seriesId) return@forEach
                val record = result.jsonObject["record"]?.jsonObject ?: return@forEach
                releases += record.string("volume") to record.string("chapter")
            }
            if (results.size < 100) break
        }
        return releases
    }

    /**
     * The first Wikipedia page with a volume list for one of [titles], as its title and the chapters of each volume.
     * Chapter lists split over several pages, e.g. "List of One Piece chapters (1–186)", are read together.
     */
    private suspend fun searchWikipedia(titles: List<String>): Pair<String, Map<String, IntRange>>? {
        for (title in titles) {
            // Wikipedia's own search finds list pages under their English title, other results must match
            val searched = searchWikipediaTitles("$title manga chapters")
            val candidates = buildList {
                add("List of $title chapters")
                add("List of $title volumes")
                addAll(searched.take(3).filter { it.startsWith("List of ") })
                addAll(searched.filter { VolumeMappingBuilder.similarity(it, title) >= MIN_SIMILARITY })
                add(title)
            }.distinct()

            val volumes = mutableListOf<WikipediaVolumeParser.ParsedVolume>()
            var firstPage: String? = null
            for (page in candidates.take(WIKIPEDIA_MAX_PAGES)) {
                val parsed = wikitext(page)?.let(WikipediaVolumeParser::parse).orEmpty()
                if (parsed.isEmpty()) continue
                if (firstPage == null) firstPage = page
                volumes += parsed
                // Only long series split their chapter list in parts, e.g. "List of One Piece chapters (1–186)"
                if (!page.startsWith("List of ") || " (" !in page) break
            }

            val ranges = WikipediaVolumeParser.toRanges(volumes.distinctBy { it.volume })
            if (ranges.isNotEmpty() && firstPage != null) return firstPage to ranges
        }
        return null
    }

    private suspend fun searchWikipediaTitles(query: String): List<String> {
        val url = WIKIPEDIA_API.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "search")
            .addQueryParameter("srsearch", query)
            .addQueryParameter("srlimit", "8")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        val response = getJson(url.toString())
        return response["query"]?.jsonObject?.get("search")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.string("title") }
            .sortedByDescending { it.startsWith("List of ") }
    }

    private suspend fun wikitext(page: String): String? {
        val url = WIKIPEDIA_API.toHttpUrl().newBuilder()
            .addQueryParameter("action", "parse")
            .addQueryParameter("page", page)
            .addQueryParameter("prop", "wikitext")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        return runCatching { getJson(url.toString())["parse"]?.jsonObject?.string("wikitext") }.getOrNull()
    }

    /**
     * The first Fandom wiki of one of [titles] with a page listing the chapters of its volumes, as the page link and
     * the chapters of each volume. Wikis are found from their likely names and Fandom's own search.
     */
    private suspend fun searchFandom(titles: List<String>): Pair<String, Map<String, IntRange>>? {
        val guessed = titles.flatMap(FandomVolumeParser::wikiNames).distinct().map { "https://$it.fandom.com" }
        val searched = runCatching { searchFandomWikis(titles.first()) }.getOrNull().orEmpty()
        val wikis = (guessed + searched).distinct().take(FANDOM_MAX_WIKIS)
        for (wiki in wikis) {
            if (!isFandomWikiOf(wiki, titles)) continue
            for (page in fandomListPages(wiki)) {
                val volumes = runCatching { fandomVolumes(wiki, page) }.getOrNull().orEmpty()
                if (volumes.isNotEmpty()) return "$wiki/wiki/${page.replace(' ', '_')}" to volumes
            }
        }
        return null
    }

    /**
     * Wikis found by Fandom's search of every wiki for [query].
     */
    private suspend fun searchFandomWikis(query: String): List<String> {
        val url = "$FANDOM_SEARCH_URL?query=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val html = client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
        return fandomWikiLink.findAll(html)
            .map { it.groupValues[1] }
            .filterNot { it in FANDOM_NOT_WIKIS }
            .distinct()
            .take(3)
            .map { "https://$it.fandom.com" }
            .toList()
    }

    /**
     * Whether [wiki] exists and is named after one of [titles], a likely name can be another series' wiki.
     */
    private suspend fun isFandomWikiOf(wiki: String, titles: List<String>): Boolean {
        val siteName = runCatching {
            getJson("$wiki/api.php?action=query&meta=siteinfo&format=json&formatversion=2")["query"]?.jsonObject
                ?.get("general")?.jsonObject
                ?.string("sitename")
        }.getOrNull() ?: return false
        val name = siteName.replace(Regex("""\bwiki\b""", RegexOption.IGNORE_CASE), " ")
        return titles.any { VolumeMappingBuilder.similarity(name, it) >= FANDOM_MIN_SIMILARITY }
    }

    /**
     * Pages of [wiki] that may list its chapters by volume, the usual names first then what its search finds.
     */
    private suspend fun fandomListPages(wiki: String): List<String> {
        val url = "$wiki/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "search")
            .addQueryParameter("srsearch", "list of chapters volumes")
            .addQueryParameter("srlimit", "10")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        val searched = runCatching {
            getJson(url.toString())["query"]?.jsonObject?.get("search")?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject.string("title") }
                .filter { fandomListTitle.containsMatchIn(it) }
                .sortedByDescending { it.contains("list", ignoreCase = true) }
        }.getOrNull().orEmpty()
        return (FANDOM_LIST_PAGES + searched).distinct().take(FANDOM_MAX_PAGES)
    }

    private suspend fun fandomVolumes(wiki: String, page: String): Map<String, IntRange> {
        val url = "$wiki/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "parse")
            .addQueryParameter("page", page)
            .addQueryParameter("prop", "text")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        val html = getJson(url.toString())["parse"]?.jsonObject?.string("text") ?: return emptyMap()
        return FandomVolumeParser.parse(html)
    }

    private suspend fun getJson(url: String): JsonObject {
        return client.newCall(GET(url, headers)).awaitSuccess().use { response ->
            json.parseToJsonElement(response.body.string()).jsonObject
        }
    }

    private suspend fun postJson(url: String, body: JsonObject): JsonObject {
        val request = POST(url, headers, body.toString().toRequestBody(JSON_MEDIA_TYPE))
        return client.newCall(request).awaitSuccess().use { response ->
            json.parseToJsonElement(response.body.string()).jsonObject
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val MANGAUPDATES_URL = "https://api.mangaupdates.com"
        private const val WIKIPEDIA_API = "https://en.wikipedia.org/w/api.php"
        private const val USER_AGENT = "MihonVolumes/1.0 (https://github.com/KirouGC/mihon-volumes)"
        private const val MANGAUPDATES_MAX_PAGES = 10
        private const val WIKIPEDIA_MAX_PAGES = 6
        private const val MIN_SIMILARITY = 0.5
        private const val FANDOM_SEARCH_URL = "https://community.fandom.com/wiki/Special:SearchCommunity"
        private const val FANDOM_MAX_WIKIS = 6
        private const val FANDOM_MAX_PAGES = 6
        private const val FANDOM_MIN_SIMILARITY = 0.34
        private val FANDOM_NOT_WIKIS = setOf("www", "community", "static", "auth", "services", "about", "fandom")
        private val FANDOM_LIST_PAGES = listOf(
            "List of Chapters and Volumes",
            "List of chapters",
            "List of Chapters",
            "List of volumes",
            "Chapters",
            "Volumes",
            "Manga",
        )
        private val fandomWikiLink = Regex("""https?://([a-z0-9-]+)\.fandom\.com""")
        private val fandomListTitle = Regex("""chapter|volume|manga""", RegexOption.IGNORE_CASE)
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
