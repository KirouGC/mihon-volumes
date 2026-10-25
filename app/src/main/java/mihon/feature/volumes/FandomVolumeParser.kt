package mihon.feature.volumes

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads the chapters of each volume from the rendered HTML of a Fandom page. Fandom wikis don't share templates, so
 * two common layouts are read and the one finding more volumes wins:
 * - tables with a chapter column and a volume column, or a "Volume N" heading above each table
 * - "Volume N" headings or lines followed by "Chapter N" items
 */
object FandomVolumeParser {

    private val volumeLine = Regex(
        """^\s*(?:volume|vol\.?|tankōbon|tankobon)\s*#?\s*0*(\d+(?:\.\d+)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val chapterMention = Regex("""\b(?:chapter|ch\.?)\s*#?\s*0*(\d+)\b""", RegexOption.IGNORE_CASE)

    /**
     * A chapter starting a line, e.g. a list item "Chapter 12: Title", unlike a summary mentioning a chapter.
     */
    private val chapterLine = Regex("""^\s*(?:chapter|ch\.?)\s*#?\s*0*(\d+)\b""", RegexOption.IGNORE_CASE)

    private val volumeHeader = Regex("""\b(?:vol(?:ume)?\.?|tankōbon|tankobon)""", RegexOption.IGNORE_CASE)

    private val chapterHeader = Regex("""\bch(?:apters?)?\b|#|\bno\.""", RegexOption.IGNORE_CASE)

    private val numbersOnly = Regex("""^[\d\s,;&–—~-]+$""")

    private val number = Regex("""\d+(?:\.\d+)?""")

    private val integer = Regex("""\d+""")

    private val fandomUrl =
        Regex("""^https?://([a-z0-9-]+(?:\.[a-z0-9-]+)?)\.fandom\.com(?:/[a-z-]{2,5})?/wiki/([^?#]+)""")

    private val slugSeparators = Regex("""[^\p{L}\p{N}]+""")

    /**
     * The wiki and page of a Fandom page link, e.g. "https://kaguya.fandom.com/wiki/List_of_Chapters"
     * -R> "https://kaguya.fandom.com" and "List_of_Chapters".
     */
    fun parseUrl(url: String): Pair<String, String>? {
        val match = fandomUrl.find(url.trim()) ?: return null
        val base = url.trim().substringBefore("/wiki/")
        return base to java.net.URLDecoder.decode(match.groupValues[2], "UTF-8")
    }

    /**
     * Likely names of the Fandom wiki of [title], e.g. "Kaguya-sama wa Kokurasetai" -R> "kaguyasama-wa-kokurasetai",
     * "kaguya-sama-wa-kokurasetai", "kaguyasamawakokurasetai", "kaguyasama".
     */
    fun wikiNames(title: String): List<String> {
        val words = title.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val joinedWords = words.map { it.replace(slugSeparators, "") }.filter { it.isNotEmpty() }
        val allParts = title.lowercase().split(slugSeparators).filter { it.isNotEmpty() }
        return listOf(
            joinedWords.joinToString("-"),
            allParts.joinToString("-"),
            allParts.joinToString(""),
            joinedWords.firstOrNull().orEmpty(),
        )
            .filter { it.length >= 3 }
            .distinct()
    }

    fun parse(html: String): Map<String, IntRange> {
        val document = Jsoup.parse(html)
        // Navigation boxes list every volume and chapter of the series at the bottom of pages
        document.select(".navbox, .portable-infobox, .toc, #toc, .mw-references-wrap, sup.reference").remove()
        val fromTables = VolumeMappingBuilder.orderedRanges(fromTables(document))
        val fromText = VolumeMappingBuilder.orderedRanges(fromText(document))
        return if (fromTables.size >= fromText.size) fromTables else fromText
    }

    private fun fromTables(document: Document): Map<String, List<Int>> {
        val chapters = linkedMapOf<String, MutableList<Int>>()
        document.select("table").forEach { table ->
            val grid = grid(table)
            if (grid.size < 2) return@forEach
            val headerIndex = grid.indexOfFirst { row -> row.any { it.isHeader } }.takeIf { it >= 0 } ?: 0
            val header = grid[headerIndex].map { it.text }
            val chapterColumn = header.indexOfFirst {
                chapterHeader.containsMatchIn(it) &&
                    !volumeHeader.containsMatchIn(it)
            }
            if (chapterColumn < 0) return@forEach
            val volumeColumn = header.indexOfFirst { volumeHeader.containsMatchIn(it) }
            val headingVolume = precedingHeadingVolume(table)

            grid.drop(headerIndex + 1).forEach rows@{ row ->
                val chapterText = row.getOrNull(chapterColumn)?.text ?: return@rows
                val rowChapters = chapterNumbers(chapterText).ifEmpty { return@rows }
                val volume = if (volumeColumn >= 0) {
                    row.getOrNull(volumeColumn)?.text?.let { number.find(it)?.value }
                } else {
                    headingVolume
                } ?: return@rows
                chapters.getOrPut(formatVolume(volume.toDouble())) { mutableListOf() } += rowChapters
            }
        }
        return chapters
    }

    private fun fromText(document: Document): Map<String, List<Int>> {
        document.select("br, p, li, tr, td, th, h1, h2, h3, h4, h5, h6, div, dt, dd, caption").forEach {
            it.appendText("\n")
        }
        val chapters = linkedMapOf<String, MutableList<Int>>()
        var currentVolume: String? = null
        document.body().wholeText().lines().forEach { line ->
            volumeLine.find(line)?.let { match ->
                currentVolume = formatVolume(match.groupValues[1].toDouble())
                return@forEach
            }
            val volume = currentVolume ?: return@forEach
            chapterLine.find(line)?.let { match ->
                chapters.getOrPut(volume) { mutableListOf() } += match.groupValues[1].toInt()
            }
        }
        return chapters
    }

    /**
     * The chapters a table cell names: "Chapter 3" or "Ch. 3-5", a cell of numbers only like "1–10", else its first
     * number since titles can hold numbers too.
     */
    private fun chapterNumbers(text: String): List<Int> {
        val mentions = chapterMention.findAll(text).map { it.groupValues[1].toInt() }.toList()
        return when {
            mentions.isNotEmpty() -> mentions
            numbersOnly.matches(text.trim()) -> integer.findAll(text).map { it.value.toInt() }.toList()
            else -> listOfNotNull(integer.find(text)?.value?.toInt())
        }
    }

    /**
     * The volume of the closest heading before [table], e.g. "Volume 3: Title".
     */
    private fun precedingHeadingVolume(table: Element): String? {
        var element: Element? = table
        while (element != null) {
            var sibling = element.previousElementSibling()
            while (sibling != null) {
                if (sibling.tagName() in HEADINGS || sibling.hasClass("mw-heading")) {
                    return volumeLine.find(sibling.text())?.groupValues?.get(1)
                }
                sibling = sibling.previousElementSibling()
            }
            element = element.parent()
        }
        return null
    }

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private data class Cell(val text: String, val isHeader: Boolean)

    /**
     * The cells of [table] row by row, a cell spanning rows or columns repeated in each.
     */
    private fun grid(table: Element): List<List<Cell>> {
        val rows = table.select("> tr, > thead > tr, > tbody > tr, > tfoot > tr")
        val grid = mutableListOf<MutableList<Cell?>>()
        rows.forEachIndexed { rowIndex, tr ->
            while (grid.size <= rowIndex) grid += mutableListOf<Cell?>()
            var column = 0
            tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }.forEach { td ->
                val current = grid[rowIndex]
                while (column < current.size && current[column] != null) column++
                val cell = Cell(td.text(), td.tagName() == "th")
                val rowSpan = td.attr("rowspan").toIntOrNull()?.coerceIn(1, 500) ?: 1
                val colSpan = td.attr("colspan").toIntOrNull()?.coerceIn(1, 50) ?: 1
                for (r in rowIndex..<rowIndex + rowSpan) {
                    while (grid.size <= r) grid += mutableListOf<Cell?>()
                    val target = grid[r]
                    for (c in column..<column + colSpan) {
                        while (target.size <= c) target += null
                        target[c] = cell
                    }
                }
                column += colSpan
            }
        }
        return grid.take(rows.size).map { row -> row.map { it ?: Cell("", false) } }
    }
}
