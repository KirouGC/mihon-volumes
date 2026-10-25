package mihon.feature.volumes

/**
 * Reads the chapters of each volume from the wikitext of a Wikipedia page, from its `{{Graphic novel list}}`
 * templates: each one has a `VolumeNumber` and a `ChapterList`.
 *
 * A chapter list is either numbered ("001.", "Chapter 9:", `{{Numbered list|start=9|...}}`, `<ol start="9">`), and
 * those numbers are kept, or only lists titles, and the chapters are then counted on from the previous volume.
 */
object WikipediaVolumeParser {

    /**
     * A volume as read from the page: the chapter numbers written in its list and how many chapters it lists.
     */
    data class ParsedVolume(val volume: String, val chapterNumbers: List<Int>, val itemCount: Int)

    private val templateStart = Regex("""\{\{\s*graphic[ _]novel[ _]list\s*(?=[|}])""", RegexOption.IGNORE_CASE)

    private val listTemplateStart = Regex(
        """\{\{\s*(?:numbered|ordered)[ _]list\s*(?=[|}])""",
        RegexOption.IGNORE_CASE,
    )

    private val listTemplateOptions = setOf("start", "type", "list_style", "list_style_type", "item_style", "style")

    private val refs = Regex(
        """<ref[^>/]*/>|<ref[^>]*>.*?</ref>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val comments = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    private val anchors = Regex(
        """\{\{\s*(?:visible anchor|vanchor|anchor)\s*\|([^|}]*)[^}]*}}""",
        RegexOption.IGNORE_CASE,
    )

    private val number = Regex("""\d+(?:\.\d+)?""")

    private val olStart = Regex("""<ol[^>]*\bstart\s*=\s*"?(\d+)""", RegexOption.IGNORE_CASE)

    private val listItemTag = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val liValue = Regex("""<li[^>]*\bvalue\s*=\s*"?(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * A chapter number at the start of a list item, e.g. `001. "Title"`, `Chapter 9: Title`, `#12 Title`, `第3話`.
     */
    private val leadingChapterNumber = Regex(
        """^[\s']*(?:chapter|chap\.?|ch\.?|episode|ep\.?|act|lesson|stage|no\.|#|第)?\s*0*(\d+)(?:\.\d+)?""" +
            """\s*(?:[.:)\-–—]|話|$|\s)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(wikitext: String): List<ParsedVolume> {
        val text = wikitext.replace(comments, "").replace(refs, "")
        return templateStart.findAll(text).mapIndexedNotNull { index, match ->
            val end = findTemplateEnd(text, match.range.first) ?: return@mapIndexedNotNull null
            val params = namedParams(splitTopLevel(text.substring(match.range.last + 1, end - 2)))
            val volume = params["volumenumber"]?.let { number.find(it)?.value }
                ?.let { formatVolume(it.toDouble()) }
                ?: (index + 1).toString()
            val (numbers, count) = parseChapterList(params["chapterlist"].orEmpty())
            ParsedVolume(volume, numbers, count)
        }.toList()
    }

    /**
     * The chapters of each volume, keyed by volume number, from the [volumes] of [parse] in page order.
     */
    fun toRanges(volumes: List<ParsedVolume>): Map<String, IntRange> {
        val ranges = linkedMapOf<String, IntRange>()
        var previousLast: Int? = 0
        for (parsed in volumes.sortedBy { it.volume.toDoubleOrNull() ?: Double.MAX_VALUE }) {
            val numbers = parsed.chapterNumbers
            val last = previousLast
            // Lists numbered again from 1 in each volume only tell how many chapters there are
            val restartsNumbering = numbers.isNotEmpty() && last != null && last > 0 && numbers.min() <= 1
            val range = when {
                numbers.isNotEmpty() && !restartsNumbering -> numbers.min()..numbers.max()
                parsed.itemCount > 0 && last != null -> (last + 1)..(last + parsed.itemCount)
                else -> null
            }
            if (range != null) ranges[parsed.volume] = range
            previousLast = range?.last
        }
        return ranges
    }

    private fun parseChapterList(value: String): Pair<List<Int>, Int> {
        val list = value.replace(anchors, "$1")

        listTemplateStart.find(list)?.let { match ->
            val end = findTemplateEnd(list, match.range.first) ?: return@let
            val parts = splitTopLevel(list.substring(match.range.last + 1, end - 2))
            val options = parts.filter { it.substringBefore('=', "").trim().lowercase() in listTemplateOptions }
            val items = (parts - options.toSet()).filter { it.isNotBlank() }
            val start = options
                .firstOrNull { it.substringBefore('=').trim().equals("start", ignoreCase = true) }
                ?.let { number.find(it.substringAfter('='))?.value?.toDouble()?.toInt() }
            val numbers = start?.let { first -> (first..<first + items.size).toList() } ?: numbersOfItems(items)
            return numbers to items.size
        }

        val liTags = listItemTag.findAll(list).count()
        if (liTags > 0) {
            val values = liValue.findAll(list).map { it.groupValues[1].toInt() }.toList()
            val start = olStart.find(list)?.groupValues?.get(1)?.toInt()
            val numbers = when {
                values.size == liTags -> values
                start != null -> (start..<start + liTags).toList()
                else -> emptyList()
            }
            return numbers to liTags
        }

        val items = list.lines()
            .filter { it.startsWith("*") || it.startsWith("#") }
            .filterNot { it.length > 1 && it[1] in "*#:" }
            .map { it.drop(1) }
        return numbersOfItems(items) to items.size
    }

    /**
     * The chapter numbers written at the start of [items], only when every item has one.
     */
    private fun numbersOfItems(items: List<String>): List<Int> {
        val numbers = items.filter { it.isNotBlank() }.map { item ->
            leadingChapterNumber.find(item.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
        }
        return numbers.sorted()
    }

    /**
     * The index after the `}}` closing the template opened at [start].
     */
    private fun findTemplateEnd(text: String, start: Int): Int? {
        var depth = 0
        var i = start
        while (i < text.length - 1) {
            when {
                text.startsWith("{{", i) -> {
                    depth++
                    i += 2
                }
                text.startsWith("}}", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * Splits template parameters at the `|` that aren't inside a nested template or link.
     */
    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < body.length) {
            when {
                body.startsWith("{{", i) || body.startsWith("[[", i) -> {
                    depth++
                    current.append(body, i, i + 2)
                    i += 2
                }
                (body.startsWith("}}", i) || body.startsWith("]]", i)) && depth > 0 -> {
                    depth--
                    current.append(body, i, i + 2)
                    i += 2
                }
                body[i] == '|' && depth == 0 -> {
                    parts += current.toString()
                    current.clear()
                    i++
                }
                else -> {
                    current.append(body[i])
                    i++
                }
            }
        }
        parts += current.toString()
        return parts.drop(1)
    }

    private fun namedParams(parts: List<String>): Map<String, String> {
        return parts.filter { '=' in it }.associate { part ->
            part.substringBefore('=').lowercase().filterNot { it.isWhitespace() || it == '_' } to
                part.substringAfter('=').trim()
        }
    }
}
