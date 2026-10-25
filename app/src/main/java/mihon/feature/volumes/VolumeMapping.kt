package mihon.feature.volumes

import kotlinx.serialization.Serializable

/**
 * How the entries of a manga are numbered: as chapters (the default) or as volumes.
 */
@Serializable
enum class ContentFormat {
    CHAPTERS,
    VOLUMES,
}

/**
 * Where the chapters of a volume were taken from, manual edits are never replaced by a new lookup.
 */
@Serializable
enum class VolumeSource {
    MANGAUPDATES,
    WIKIPEDIA,
    MANUAL,
}

/**
 * The chapters a volume collects, both ends included.
 */
@Serializable
data class VolumeChapters(
    val firstChapter: Int,
    val lastChapter: Int,
    val source: VolumeSource,
)

/**
 * The content format of one manga and, for volumes, the chapters each volume collects keyed by volume number.
 */
@Serializable
data class MangaVolumeMapping(
    val format: ContentFormat = ContentFormat.CHAPTERS,
    val query: String? = null,
    val mangaUpdatesId: Long? = null,
    val wikipediaPage: String? = null,
    val volumes: Map<String, VolumeChapters> = emptyMap(),
    val updatedAt: Long = 0,
) {
    /**
     * The chapters of [volume], a volume as numbered in the app, e.g. 3 or 5.5 falling back to volume 5.
     */
    fun chaptersOf(volume: Double): VolumeChapters? {
        return volumes[formatVolume(volume)] ?: volumes[formatVolume(kotlin.math.floor(volume))]
    }

    /**
     * The number of volumes fully read once [lastChapterRead] chapters are read.
     */
    fun volumesReadAt(lastChapterRead: Double): Int? {
        return volumes
            .filter { (_, chapters) -> chapters.lastChapter <= lastChapterRead }
            .keys
            .mapNotNull { it.toDoubleOrNull() }
            .maxOrNull()
            ?.toInt()
    }
}

/**
 * The global file content: every manga with a content format, keyed by [mappingKey].
 */
@Serializable
data class VolumeMappings(
    val version: Int = 1,
    val mangas: Map<String, MangaVolumeMapping> = emptyMap(),
)

/**
 * The key of a manga in [VolumeMappings], its source and url so that it outlives removing the manga or a restore.
 */
fun mappingKey(sourceId: Long, url: String): String = "$sourceId:$url"

/**
 * Formats a volume number without trailing zeros, e.g. 3 or 5.5.
 */
fun formatVolume(volume: Double): String {
    return if (volume == kotlin.math.floor(volume)) volume.toLong().toString() else volume.toString()
}
