package mihon.feature.volumes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WikipediaVolumeParserTest {

    private fun ranges(wikitext: String) = WikipediaVolumeParser.toRanges(WikipediaVolumeParser.parse(wikitext))

    @Test
    fun `Numbered list template with start`() {
        val wikitext = """
            {{Graphic novel list/header
             | OneLanguage = no
            }}
            {{Graphic novel list
             | VolumeNumber    = 1
             | OriginalRelDate = August 18, 2020<ref name="v1">{{cite web|url=https://example.com|title=Vol 1}}</ref>
             | OriginalISBN    = 978-4-09-850210-9
             | ChapterList     =
            {{Numbered list|start=1
            |{{nihongo|"The Journey's End"|冒険の終わり|Bōken no Owari}}
            |{{nihongo|"It Didn't Have to Be Magic..."|別に魔法じゃなくたって…|Betsu ni Mahō ja Nakutatte...}}
            |"Blue Moonweed"
            }}
             | Summary = A summary with a [[Link|pipe]] in it.
            }}
            {{Graphic novel list
             | VolumeNumber    = 2
             | ChapterList     =
            {{Numbered list|start=4
            |"Chapter four"
            |"Chapter five"
            }}
            }}
            {{Graphic novel list/footer}}
        """.trimIndent()

        ranges(wikitext) shouldBe mapOf("1" to 1..3, "2" to 4..5)
    }

    @Test
    fun `Bullet list with chapter numbers`() {
        val wikitext = """
            {{Graphic novel list
             | VolumeNumber = 1
             | ChapterList =
            * 001. {{nihongo|"Romance Dawn"|ROMANCE DAWN}}
            * 002. "They Call Him Straw Hat Luffy"
            * 003. "Introducing Pirate Hunter Zoro"
            }}
            {{Graphic novel list
             | VolumeNumber = 2
             | ChapterList =
            * Chapter 4: "The Great Captain Morgan"
            * Chapter 5: "The King of the Pirates"
            }}
        """.trimIndent()

        ranges(wikitext) shouldBe mapOf("1" to 1..3, "2" to 4..5)
    }

    @Test
    fun `Titles only are counted on from the previous volume`() {
        val wikitext = """
            {{Graphic novel list
             | VolumeNumber = 1
             | ChapterList =
            * "First"
            * "Second"
            ** a nested note that isn't a chapter
            * "Third"
            }}
            {{Graphic novel list
             | VolumeNumber = 2
             | ChapterList =
            * "Fourth"
            * "Fifth"
            }}
        """.trimIndent()

        ranges(wikitext) shouldBe mapOf("1" to 1..3, "2" to 4..5)
    }

    @Test
    fun `Numbering restarting in each volume only counts chapters`() {
        val wikitext = """
            {{Graphic novel list
             | VolumeNumber = 1
             | ChapterList =
            # "First"
            # "Second"
            }}
            {{Graphic novel list
             | VolumeNumber = 2
             | ChapterList =
            * 1. "Third"
            * 2. "Fourth"
            * 3. "Fifth"
            }}
        """.trimIndent()

        ranges(wikitext) shouldBe mapOf("1" to 1..2, "2" to 3..5)
    }

    @Test
    fun `Html ordered list with start`() {
        val wikitext = """
            {{Graphic novel list
             | VolumeNumber = 3
             | ChapterList =
            <ol start="17">
            <li>"Seventeen"</li>
            <li>"Eighteen"</li>
            </ol>
            }}
        """.trimIndent()

        ranges(wikitext) shouldBe mapOf("3" to 17..18)
    }

    @Test
    fun `Volume without a chapter list stops counting`() {
        val wikitext = """
            {{Graphic novel list
             | VolumeNumber = 1
             | ChapterList =
            * "First"
            }}
            {{Graphic novel list
             | VolumeNumber = 2
            }}
            {{Graphic novel list
             | VolumeNumber = 3
             | ChapterList =
            * "Unknown"
            }}
            {{Graphic novel list
             | VolumeNumber = 4
             | ChapterList =
            * Chapter 30
            * Chapter 31
            }}
        """.trimIndent()

        ranges(wikitext) shouldBe mapOf("1" to 1..1, "4" to 30..31)
    }
}
