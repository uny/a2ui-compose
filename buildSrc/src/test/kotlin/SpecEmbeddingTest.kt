import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every guard in `SpecEmbedding.kt` exists to turn a compile error in a generated file under
 * `build/` that nobody wrote into a message naming the document at fault. The five call sites in
 * this build trip none of them, so these tests are the only place a failure path runs -- and each
 * one asserts that the message names the document, not that it matches a string.
 */
class SpecEmbeddingTest {
    // -- constantName ---------------------------------------------------------------------------

    @Test
    fun `constantName upper-cases the stem and drops the directory and extension`() {
        assertEquals("INITIAL_STATE_VALIDATION", constantName("a/b/initial_state_validation.json"))
        assertEquals("_LEADING", constantName("_leading.json"))
        assertEquals("V2", constantName("v2.json"))
        // Only `.json` is stripped; anything else is part of the stem and judged as such.
        assertEquals("SCHEMA", constantName("schema"))
    }

    @Test
    fun `constantName refuses a stem that is not an identifier, naming the file`() {
        for (file in listOf("dynamic-values.json", "00_simple-text.json", "1st.json", "a.b.json", "with space.json", ".json")) {
            val error = assertFailsWith<IllegalArgumentException>(file) { constantName("dir/$file") }
            assertContains(error.message.orEmpty(), "`$file`")
        }
    }

    // -- chunkedWholeCodePoints -------------------------------------------------------------------

    @Test
    fun `chunkedWholeCodePoints splits at the width when no pair straddles a boundary`() {
        assertEquals(listOf("abc", "def", "g"), "abcdefg".chunkedWholeCodePoints(3))
        assertEquals(listOf("abc"), "abc".chunkedWholeCodePoints(3))
        assertEquals(emptyList(), "".chunkedWholeCodePoints(3))
    }

    @Test
    fun `chunkedWholeCodePoints pushes a boundary past a surrogate pair rather than through it`() {
        // U+1F600 is two UTF-16 units; with a width of 3 the pair would be cut after its high half.
        val text = "ab😀cd"
        val chunks = text.chunkedWholeCodePoints(3)
        assertEquals(listOf("ab😀", "cd"), chunks)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `chunkedWholeCodePoints refuses a non-positive width instead of looping`() {
        for (size in listOf(0, -1, Int.MIN_VALUE)) {
            val error = assertFailsWith<IllegalArgumentException>("size = $size") { "abc".chunkedWholeCodePoints(size) }
            assertContains(error.message.orEmpty(), "$size")
        }
    }

    @Test
    fun `chunkedWholeCodePoints round-trips random inputs without splitting a pair`() {
        // The one-off harness that verified this in review ran ~1M inputs; a fixed seed and a few
        // thousand cases keep the property in CI without making the test slow or flaky.
        val random = Random(20260920)
        val alphabet = listOf("a", "Z", "\n", "\\", "$", "😀", "𝄞", "é", "日")
        repeat(3_000) {
            val text = buildString { repeat(random.nextInt(0, 40)) { append(alphabet.random(random)) } }
            val size = random.nextInt(1, 8)
            val chunks = text.chunkedWholeCodePoints(size)
            assertEquals(text, chunks.joinToString(""), "round-trip of $text at $size")
            assertTrue(chunks.none { it.isEmpty() }, "empty chunk in $chunks")
            chunks.zipWithNext { a, b ->
                assertTrue(!(a.last().isHighSurrogate() && b.first().isLowSurrogate()), "split pair between `$a` and `$b`")
            }
            // A chunk is the width, or one wider when a pair was pushed out; only the last may be shorter.
            chunks.dropLast(1).forEach { assertTrue(it.length in size..size + 1, "chunk `$it` at width $size") }
        }
    }

    // -- literal ----------------------------------------------------------------------------------

    @Test
    fun `literal escapes every character a Kotlin line string cannot hold raw`() {
        assertEquals("\"a\\\\b\"", literal("a\\b", ""))
        assertEquals("\"say \\\"hi\\\"\"", literal("say \"hi\"", ""))
        assertEquals("\"\\\$ref\"", literal("\$ref", ""))
        assertEquals("\"line\\r\\nnext\"", literal("line\r\nnext", ""))
        // Backslash first, or the backslash added by a later escape would be doubled.
        assertEquals("\"\\\\\\\"\"", literal("\\\"", ""))
    }

    @Test
    fun `literal chunks the text before escaping and joins the chunks with the indent`() {
        val text = "a".repeat(100) + "b".repeat(50)
        val indent = "    "
        assertEquals("\"" + "a".repeat(100) + "\" +\n" + indent + "\"" + "b".repeat(50) + "\"", literal(text, indent))
        // A chunk of 100 quotes escapes to 200 units. The boundary is decided before escaping, so
        // the first line holds all 100 escaped quotes and no `\"` is cut in half.
        assertEquals(
            "\"" + "\\\"".repeat(100) + "\" +\n" + indent + "\"" + "\\\"".repeat(50) + "\"",
            literal("\"".repeat(150), indent),
        )
    }

    @Test
    fun `literal keeps a surrogate pair astride the chunk width in one chunk`() {
        val text = "a".repeat(99) + "😀" + "z"
        val chunks = literal(text, "").split("\" +\n\"")
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].endsWith("😀"), "first chunk `${chunks[0]}` ends with the whole pair")
        assertEquals("z\"", chunks[1])
    }

    @Test
    fun `literal round-trips random text through the reverse of its escapes`() {
        val random = Random(20260920)
        val alphabet = listOf("a", "\\", "\"", "$", "\r", "\n", "😀", "{", "}")
        repeat(2_000) {
            val text = buildString { repeat(random.nextInt(0, 250)) { append(alphabet.random(random)) } }
            assertEquals(text, unescape(literal(text, "  ")), "round-trip of ${text.encodeToByteArray().toList()}")
        }
    }

    /** Reverses [literal]: the chunk separators first, then the escapes in the opposite order. */
    private fun unescape(source: String): String {
        val joined = source.removePrefix("\"").removeSuffix("\"").split("\" +\n  \"").joinToString("")
        val out = StringBuilder()
        var i = 0
        while (i < joined.length) {
            val c = joined[i]
            if (c == '\\') {
                when (val next = joined[i + 1]) {
                    'r' -> out.append('\r')
                    'n' -> out.append('\n')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    // -- resolveDocuments -------------------------------------------------------------------------

    private val location = File("/module/spec/cases")

    private fun scanned(vararg files: String) = ScannedDirectory("cases", location, files.toList())

    private fun resolve(
        documents: Map<String, String> = emptyMap(),
        scanned: ScannedDirectory? = null,
        namedConstants: Boolean = true,
    ) = resolveDocuments("generateFixtures", "Fixtures", documents, scanned, namedConstants)

    private inline fun failing(block: () -> Unit): String =
        assertFailsWith<IllegalArgumentException>(block = block).message.orEmpty()

    @Test
    fun `resolveDocuments lists the documents first and the scanned files after, sorted`() {
        val all = resolve(
            documents = mapOf("COMMON_TYPES" to "common_types.json"),
            scanned = scanned("b.json", "a.json"),
        )
        assertEquals(
            listOf("COMMON_TYPES" to "common_types.json", "A" to "cases/a.json", "B" to "cases/b.json"),
            all.entries.map { it.toPair() },
        )
    }

    @Test
    fun `resolveDocuments keys scanned files by filename when constants are off`() {
        val all = resolve(scanned = scanned("00_simple-text.json", "01_list.json"), namedConstants = false)
        assertEquals(mapOf("00_simple-text.json" to "cases/00_simple-text.json", "01_list.json" to "cases/01_list.json"), all)
    }

    @Test
    fun `resolveDocuments refuses an empty scanned directory, naming where it looked`() {
        val message = failing { resolve(scanned = scanned()) }
        assertContains(message, "`cases`")
        assertContains(message, location.toString())
    }

    @Test
    fun `resolveDocuments refuses two scanned files that derive the same constant`() {
        val message = failing { resolve(scanned = scanned("schema.json", "SCHEMA.json")) }
        assertContains(message, "cases/schema.json")
        assertContains(message, "cases/SCHEMA.json")
    }

    @Test
    fun `resolveDocuments refuses a scanned file that would shadow a listed document`() {
        val message = failing {
            resolve(documents = mapOf("SCHEMA" to "old/schema.json"), scanned = scanned("schema.json"))
        }
        assertContains(message, "SCHEMA")
        assertContains(message, "old/schema.json")
        assertContains(message, "cases/schema.json")
    }

    @Test
    fun `resolveDocuments refuses two documents that share a filename`() {
        val message = failing {
            resolve(documents = mapOf("A" to "x/schema.json", "B" to "y/schema.json"))
        }
        assertContains(message, "x/schema.json")
        assertContains(message, "y/schema.json")
    }

    @Test
    fun `resolveDocuments refuses a document named like the index, unless constants are off`() {
        val message = failing { resolve(scanned = scanned("all.json")) }
        assertContains(message, "cases/all.json")
        assertContains(message, "`ALL`")
        assertContains(failing { resolve(documents = mapOf("ALL" to "all.json")) }, "`ALL`")
        // Without constants there is no `const val ALL` to clash with the index.
        assertEquals(mapOf("all.json" to "cases/all.json"), resolve(scanned = scanned("all.json"), namedConstants = false))
    }

    @Test
    fun `resolveDocuments refuses an empty corpus, naming the task and object`() {
        val message = failing { resolve() }
        assertContains(message, "`generateFixtures`")
        assertContains(message, "`Fixtures.ALL`")
    }

    @Test
    fun `resolveDocuments refuses a hand-listed key that is not a constant name`() {
        val message = failing { resolve(documents = mapOf("basic-catalog" to "basic_catalog.json")) }
        assertContains(message, "`basic-catalog`")
        assertContains(message, "`basic_catalog.json`")
        // Keys are not checked when no constants are emitted from them.
        assertEquals(
            mapOf("basic-catalog" to "basic_catalog.json"),
            resolve(documents = mapOf("basic-catalog" to "basic_catalog.json"), namedConstants = false),
        )
    }

    @Test
    fun `resolveDocuments refuses a scanned file that is not a constant name`() {
        val message = failing { resolve(scanned = scanned("dynamic-values.json")) }
        assertContains(message, "`dynamic-values.json`")
    }
}
