package mihon.feature.volumes

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks

@AssistedInject
class VolumeFormatViewModel(
    @Assisted private val mangaId: Long,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val store: VolumeMappingStore,
    private val lookup: VolumeLookup,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long): VolumeFormatViewModel
    }

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private var manga: Manga? = null

    /**
     * Volumes found but not in the app, kept so that volumes added later already have their chapters.
     */
    private var otherVolumes: Map<String, VolumeChapters> = emptyMap()

    private var searchJob: Job? = null

    fun load() {
        searchJob?.cancel()
        state.value = State()
        viewModelScope.launch {
            try {
                loadMapping()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to load the volume mapping" }
            }
        }
    }

    private suspend fun loadMapping() {
        val manga = getManga.await(mangaId) ?: return
        this.manga = manga
        val mapping = store.get(manga.source, manga.url)
        val tracks = getTracks.await(mangaId)
        val presentVolumes = getChaptersByMangaId.await(mangaId)
            .filter { it.isRecognizedNumber }
            .map { it.chapterNumber }
            .distinct()
            .sorted()
            .map(::formatVolume)

        val trackedTitle = TITLE_TRACKERS.firstNotNullOfOrNull { trackerId ->
            tracks.firstOrNull { it.trackerId == trackerId }?.title?.takeIf { it.isNotBlank() }
        }
        val mangaUpdatesId = mapping?.mangaUpdatesId
            ?: tracks.firstOrNull { it.trackerId == trackerManager.mangaUpdates.id }?.remoteId?.takeIf { it > 0 }

        val volumes = mapping?.volumes.orEmpty()
        otherVolumes = volumes.filterKeys { it !in presentVolumes }
        state.value = State(
            isLoaded = true,
            format = mapping?.format ?: ContentFormat.CHAPTERS,
            query = mapping?.query ?: trackedTitle ?: VolumeMappingBuilder.cleanTitle(manga.title),
            rows = presentVolumes.map { volume -> Row.of(volume, volumes[volume]) },
            mangaUpdatesId = mangaUpdatesId,
            wikipediaPage = mapping?.wikipediaPage,
            fandomPage = mapping?.fandomPage,
            hasSearched = mapping != null && volumes.isNotEmpty(),
        )
    }

    fun setFormat(format: ContentFormat) {
        state.update { it.copy(format = format) }
        val current = state.value
        if (format == ContentFormat.VOLUMES && !current.hasSearched && current.rows.none { it.source != null }) {
            search()
        }
    }

    fun setQuery(query: String) {
        state.update { it.copy(query = query) }
    }

    fun search() {
        val query = state.value.query.trim().ifEmpty { return }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            state.update { it.copy(isSearching = true) }
            // A new title can be another series, so a known MangaUpdates id only counts for the same search
            val previous = store.get(manga?.source ?: return@launch, manga?.url ?: return@launch)
            val mangaUpdatesId = state.value.mangaUpdatesId.takeIf {
                previous?.query == null || previous.query == query
            }
            val result = lookup.lookup(query, mangaUpdatesId)

            val manual = state.value.rows.mapNotNull { row ->
                row.toChapters()?.takeIf { it.source == VolumeSource.MANUAL }?.let { row.volume to it }
            }.toMap() + otherVolumes.filterValues { it.source == VolumeSource.MANUAL }
            val merged = VolumeMappingBuilder.merge(manual, result.wikipedia, result.mangaUpdates, result.fandom)

            val presentVolumes = state.value.rows.map { it.volume }.toSet()
            otherVolumes = merged.filterKeys { it !in presentVolumes }
            state.update { current ->
                current.copy(
                    isSearching = false,
                    hasSearched = true,
                    rows = current.rows.map { row -> Row.of(row.volume, merged[row.volume]) },
                    mangaUpdatesId = result.mangaUpdatesId,
                    wikipediaPage = result.wikipediaPage,
                    fandomPage = result.fandomPage,
                    foundNothing = result.isEmpty,
                )
            }
        }
    }

    fun editRow(volume: String, from: String, to: String) {
        state.update { current ->
            current.copy(
                rows = current.rows.map { row ->
                    if (row.volume == volume) row.copy(from = from, to = to, source = VolumeSource.MANUAL) else row
                },
            )
        }
    }

    fun save(onSaved: () -> Unit) {
        val manga = manga ?: return
        val current = state.value
        viewModelScope.launch {
            val volumes = otherVolumes + current.rows.mapNotNull { row -> row.toChapters()?.let { row.volume to it } }
            store.set(
                manga.source,
                manga.url,
                MangaVolumeMapping(
                    format = current.format,
                    query = current.query.trim().ifEmpty { null },
                    mangaUpdatesId = current.mangaUpdatesId,
                    wikipediaPage = current.wikipediaPage,
                    fandomPage = current.fandomPage,
                    volumes = volumes.toSortedMap(compareBy { it.toDoubleOrNull() ?: Double.MAX_VALUE }),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            onSaved()
        }
    }

    @Immutable
    data class State(
        val isLoaded: Boolean = false,
        val format: ContentFormat = ContentFormat.CHAPTERS,
        val query: String = "",
        val rows: List<Row> = emptyList(),
        val mangaUpdatesId: Long? = null,
        val wikipediaPage: String? = null,
        val fandomPage: String? = null,
        val isSearching: Boolean = false,
        val hasSearched: Boolean = false,
        val foundNothing: Boolean = false,
    ) {
        val missingVolumes: List<String>
            get() = rows.filter { it.toChapters() == null }.map { it.volume }
    }

    /**
     * A volume in the app and its chapters as typed, [source] is null while it has none.
     */
    @Immutable
    data class Row(
        val volume: String,
        val from: String,
        val to: String,
        val source: VolumeSource?,
    ) {
        fun toChapters(): VolumeChapters? {
            val first = from.trim().toIntOrNull() ?: return null
            val last = to.trim().toIntOrNull() ?: return null
            if (first < 0 || last < first) return null
            return VolumeChapters(first, last, source ?: VolumeSource.MANUAL)
        }

        companion object {
            fun of(volume: String, chapters: VolumeChapters?) = Row(
                volume = volume,
                from = chapters?.firstChapter?.toString().orEmpty(),
                to = chapters?.lastChapter?.toString().orEmpty(),
                source = chapters?.source,
            )
        }
    }

    companion object {
        /**
         * Trackers whose title is a cleaner search than the title from the source: AniList, MyAnimeList, MangaUpdates.
         */
        private val TITLE_TRACKERS = listOf(2L, 1L, 7L)
    }
}
