package mihon.feature.volumes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VolumeMappingBuilderTest {

    @Test
    fun `Releases are grouped by volume`() {
        val releases = listOf(
            "1" to "1-3",
            "1" to "4",
            "2" to "5.5",
            "02" to "8",
            null to "9",
            "3" to null,
            "1-2" to "1-8",
        )

        VolumeMappingBuilder.fromReleases(releases) shouldBe mapOf("1" to 1..4, "2" to 5..8)
    }

    @Test
    fun `Overlapping release volumes are left out`() {
        val releases = listOf("1" to "1-10", "2" to "5-15", "3" to "11-20")

        VolumeMappingBuilder.fromReleases(releases) shouldBe mapOf("1" to 1..10, "3" to 11..20)
    }

    @Test
    fun `Manual edits win, then Wikipedia, then MangaUpdates`() {
        val previous = mapOf(
            "1" to VolumeChapters(1, 7, VolumeSource.MANUAL),
            "2" to VolumeChapters(9, 15, VolumeSource.WIKIPEDIA),
        )
        val wikipedia = mapOf("1" to 1..8, "2" to 9..16)
        val mangaUpdates = mapOf("2" to 9..17, "3" to 17..24)

        VolumeMappingBuilder.merge(previous, wikipedia, mangaUpdates) shouldBe mapOf(
            "1" to VolumeChapters(1, 7, VolumeSource.MANUAL),
            "2" to VolumeChapters(9, 16, VolumeSource.WIKIPEDIA),
            "3" to VolumeChapters(17, 24, VolumeSource.MANGAUPDATES),
        )
    }

    @Test
    fun `Titles are cleaned for searching`() {
        VolumeMappingBuilder.cleanTitle(
            "The Healer Consort (2025-2026) (Digital-Compilation) (Square Enix) (DigitalMangaFan)",
        ) shouldBe "The Healer Consort"
        VolumeMappingBuilder.cleanTitle("[Group] Frieren v01-12") shouldBe "Frieren"
    }

    @Test
    fun `Volumes read are the volumes whose last chapter is read`() {
        val mapping = MangaVolumeMapping(
            format = ContentFormat.VOLUMES,
            volumes = mapOf(
                "1" to VolumeChapters(1, 8, VolumeSource.WIKIPEDIA),
                "2" to VolumeChapters(9, 16, VolumeSource.WIKIPEDIA),
            ),
        )

        mapping.volumesReadAt(16.0) shouldBe 2
        mapping.volumesReadAt(10.0) shouldBe 1
        mapping.volumesReadAt(3.0) shouldBe null
        mapping.chaptersOf(2.5) shouldBe VolumeChapters(9, 16, VolumeSource.WIKIPEDIA)
    }
}
