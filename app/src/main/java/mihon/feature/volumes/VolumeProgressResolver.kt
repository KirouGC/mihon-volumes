package mihon.feature.volumes

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.interactor.GetManga

/**
 * Turns what was read into the chapter number trackers get: for a manga read in volumes, the last chapter of the
 * volume, since trackers like AniList count chapters.
 */
@Inject
class VolumeProgressResolver(
    private val getManga: GetManga,
    private val store: VolumeMappingStore,
) {

    sealed interface Progress {
        /**
         * The chapter number to send, for a manga in chapters it's the number read.
         */
        data class Chapter(val chapterNumber: Double) : Progress

        /**
         * The manga is read in volumes but [volume] has no chapters yet, nothing is sent.
         */
        data class UnmappedVolume(val volume: Double) : Progress
    }

    suspend fun resolve(mangaId: Long, number: Double): Progress {
        val mapping = mappingOf(mangaId) ?: return Progress.Chapter(number)
        val chapters = mapping.chaptersOf(number) ?: return Progress.UnmappedVolume(number)
        return Progress.Chapter(chapters.lastChapter.toDouble())
    }

    /**
     * The mapping of [mangaId] when it's read in volumes.
     */
    suspend fun mappingOf(mangaId: Long): MangaVolumeMapping? {
        val manga = getManga.await(mangaId) ?: return null
        return store.get(manga.source, manga.url)?.takeIf { it.format == ContentFormat.VOLUMES }
    }
}
