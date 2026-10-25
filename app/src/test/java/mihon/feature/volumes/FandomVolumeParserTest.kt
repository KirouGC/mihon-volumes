package mihon.feature.volumes

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FandomVolumeParserTest {

    @Test
    fun `Volume headings followed by chapter lists`() {
        val html = """
            <h2><span class="mw-headline" id="Volume_1">Volume 1</span></h2>
            <p>Released on May 19, 2016. Its cover shows chapter 40's dress.</p>
            <ul>
              <li><a href="/wiki/Chapter_1">Chapter 1</a>: I Want to Make Them Invite Me to a Movie</li>
              <li><a href="/wiki/Chapter_2">Chapter 2</a>: Kaguya Wants to Trade</li>
            </ul>
            <h2><span class="mw-headline" id="Volume_2">Volume 2</span></h2>
            <ul>
              <li>Chapter 3: Kaguya Wants It</li>
              <li>Chapter 4: Kaguya Wants to Be Noticed</li>
            </ul>
            <table class="navbox"><tr><td>Volume 1 Volume 2</td><td>Chapter 1 Chapter 300</td></tr></table>
        """.trimIndent()

        FandomVolumeParser.parse(html) shouldBe mapOf("1" to 1..2, "2" to 3..4)
    }

    @Test
    fun `Table with a volume column spanning its chapters`() {
        val html = """
            <table class="wikitable">
              <tr><th>Volume</th><th>Ch.</th><th>Title</th></tr>
              <tr><td rowspan="2">1</td><td>1</td><td>The First</td></tr>
              <tr><td>2</td><td>The 2nd and 100th</td></tr>
              <tr><td rowspan="3">2</td><td>3</td><td>Third</td></tr>
              <tr><td>4</td><td>Fourth</td></tr>
              <tr><td>5</td><td>Fifth</td></tr>
            </table>
        """.trimIndent()

        FandomVolumeParser.parse(html) shouldBe mapOf("1" to 1..2, "2" to 3..5)
    }

    @Test
    fun `One table per volume under its heading`() {
        val html = """
            <div class="mw-heading mw-heading2"><h2 id="Volume_1">Volume 1: The Beginning</h2></div>
            <table class="wikitable">
              <tbody>
                <tr><th>No.</th><th>Title</th></tr>
                <tr><td>001</td><td>One</td></tr>
                <tr><td>002</td><td>Two</td></tr>
              </tbody>
            </table>
            <div class="mw-heading mw-heading2"><h2 id="Volume_2">Volume 2: The Middle</h2></div>
            <table class="wikitable">
              <tbody>
                <tr><th>No.</th><th>Title</th></tr>
                <tr><td>003</td><td>Three</td></tr>
              </tbody>
            </table>
        """.trimIndent()

        FandomVolumeParser.parse(html) shouldBe mapOf("1" to 1..2, "2" to 3..3)
    }

    @Test
    fun `Volume rows listing their chapters`() {
        val html = """
            <table class="article-table">
              <tr><th>Volume</th><th>Release date</th><th>Chapters</th></tr>
              <tr><td>Vol. 1</td><td>2016</td><td>1–10</td></tr>
              <tr><td>Vol. 2</td><td>2016</td><td>Chapter 11 - Chapter 20</td></tr>
            </table>
        """.trimIndent()

        FandomVolumeParser.parse(html) shouldBe mapOf("1" to 1..10, "2" to 11..20)
    }

    @Test
    fun `Fandom links and wiki names`() {
        FandomVolumeParser.parseUrl(
            "https://kaguyasama-wa-kokurasetai.fandom.com/wiki/List_of_Chapters_and_Volumes",
        ) shouldBe ("https://kaguyasama-wa-kokurasetai.fandom.com" to "List_of_Chapters_and_Volumes")
        FandomVolumeParser.parseUrl("https://onepiece.fandom.com/es/wiki/Cap%C3%ADtulos") shouldBe
            ("https://onepiece.fandom.com/es" to "Capítulos")
        FandomVolumeParser.parseUrl("Kaguya-sama") shouldBe null

        FandomVolumeParser.wikiNames("Kaguya-sama wa Kokurasetai") shouldContain "kaguyasama-wa-kokurasetai"
    }
}
