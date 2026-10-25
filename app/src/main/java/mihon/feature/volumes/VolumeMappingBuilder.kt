package mihon.feature.volumes

/**
 * Puts together the chapters of each volume from what MangaUpdates and Wikipedia found.
 */
object VolumeMappingBuilder {

    private val number = Regex("""\d+(?:\.\d+)?""")

    private val singleVolume = Regex("""^\s*0*(\d+(?:\.\d+)?)\s*$""")

    /**
     * The chapters of each volume from MangaUpdates releases, given as their volume and chapter, e.g. "3" and
     * "17-20". Releases of several volumes at once are left out since they don't tell where a volume ends.
     */
    fun fromReleases(releases: List<Pair<String?, String?>>): Map<String, IntRange> {
        val chaptersByVolume = mutableMapOf<String, MutableList<Int>>()
        releases.forEach { (volume, chapter) ->
            val volumeNumber = volume?.let { singleVolume.find(it)?.groupValues?.get(1) } ?: return@forEach
            val chapters = chapter?.let { number.findAll(it).map { n -> n.value.toDouble().toInt() }.toList() }
            if (chapters.isNullOrEmpty()) return@forEach
            chaptersByVolume.getOrPut(formatVolume(volumeNumber.toDouble())) { mutableListOf() } += chapters
        }
        return chaptersByVolume
            .mapValues { (_, chapters) -> chapters.min()..chapters.max() }
            .toSortedMap(compareBy { it.toDouble() })
            .let(::dropOverlapping)
    }

    /**
     * The volumes whose chapters come after the previous volume's, others are wrong releases or mixed editions.
     */
    private fun dropOverlapping(ranges: Map<String, IntRange>): Map<String, IntRange> {
        val result = linkedMapOf<String, IntRange>()
        var previousLast = Int.MIN_VALUE
        ranges.forEach { (volume, range) ->
            if (range.first > previousLast) {
                result[volume] = range
                previousLast = range.last
            }
        }
        return result
    }

    /**
     * The volumes to keep: manual edits of [previous] first, then Wikipedia, then MangaUpdates.
     */
    fun merge(
        previous: Map<String, VolumeChapters>,
        wikipedia: Map<String, IntRange>,
        mangaUpdates: Map<String, IntRange>,
    ): Map<String, VolumeChapters> {
        val volumes = (previous.keys + wikipedia.keys + mangaUpdates.keys)
            .sortedBy { it.toDoubleOrNull() ?: Double.MAX_VALUE }
        return volumes.associateWith { volume ->
            previous[volume]?.takeIf { it.source == VolumeSource.MANUAL }
                ?: wikipedia[volume]?.let { VolumeChapters(it.first, it.last, VolumeSource.WIKIPEDIA) }
                ?: mangaUpdates[volume]?.let { VolumeChapters(it.first, it.last, VolumeSource.MANGAUPDATES) }
                ?: previous.getValue(volume)
        }
    }

    /**
     * A title to search for, without what file sharing names add, e.g. "The Healer Consort (2025-2026)
     * (Digital-Compilation) (Square Enix)" -R> "The Healer Consort".
     */
    fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\([^)]*\)|\[[^\]]*\]|\{[^\}]*\}"""), " ")
            .replace(Regex("""\b(?:v|vol\.?|volume)\s*\d+(?:\s*-\s*\d+)?\b""", RegexOption.IGNORE_CASE), " ")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifEmpty { title.trim() }
    }

    /**
     * How close two titles are from 0 to 1, comparing their words regardless of case and punctuation.
     */
    fun similarity(a: String, b: String): Double {
        fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toSet()
        val wordsA = words(a)
        val wordsB = words(b)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0
        return (wordsA intersect wordsB).size.toDouble() / (wordsA union wordsB).size
    }
}
