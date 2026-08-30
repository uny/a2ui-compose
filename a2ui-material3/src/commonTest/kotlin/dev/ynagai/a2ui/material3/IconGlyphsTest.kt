package dev.ynagai.a2ui.material3

import androidx.compose.ui.graphics.vector.PathParser
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the glyph table covers the catalog, read off the catalog rather than counted by hand.
 *
 * The names live in the shipped catalog document, so this is the same kind of derivation the
 * gallery's `DrawableExamplesTest` makes: a specification that adds an icon fails here, naming the
 * one it added, instead of shipping a renderer that draws an empty square for it.
 */
class IconGlyphsTest {
    @Test
    fun every_icon_the_catalog_names_has_a_glyph() {
        val missing = catalogIconNames().filterNot { it in ICON_PATHS }
        assertEquals(emptyList(), missing, "icons named by the catalog with no glyph here")
    }

    @Test
    fun no_glyph_is_drawn_for_a_name_the_catalog_does_not_have() {
        // The other direction, and not pedantry: a name this table holds and the catalog does not
        // is either a typo -- which means the real name draws nothing -- or an icon the
        // specification dropped.
        val names = catalogIconNames().toSet()
        val extra = ICON_PATHS.keys.filterNot { it in names }.sorted()
        assertEquals(emptyList(), extra, "glyphs here for names the catalog does not name")
    }

    @Test
    fun every_glyph_parses_into_a_path() {
        // What the renderer does with the string, done here for all fifty-nine at once. A path
        // with a typo in it parses to nothing and draws as an empty square, which on screen is
        // indistinguishable from the icon simply being absent.
        val broken = ICON_PATHS.filter { (_, data) ->
            runCatching { PathParser().parsePathString(data).toNodes() }.getOrNull().isNullOrEmpty()
        }.keys.sorted()
        assertEquals(emptyList(), broken, "glyphs whose path data does not parse")
    }

    @Test
    fun the_catalog_names_the_fifty_nine_this_table_was_written_against() {
        // The count, once, so that a catalog which grew is distinguishable from one whose names
        // changed -- the two assertions above would both report the same list either way.
        assertTrue(
            catalogIconNames().size == 59,
            "the catalog named ${catalogIconNames().size} icons, not the 59 this was written for",
        )
    }

    /**
     * The `Icon` component's closed list of names, from the catalog this library ships.
     *
     * `name` is a `oneOf` of three forms -- the enum, an object carrying `svgPath`, and a data
     * binding -- and the enum is whichever branch has one. Found rather than indexed, so a
     * specification that reorders the branches does not silently return nothing.
     */
    private fun catalogIconNames(): List<String> {
        val document = A2uiJson.strict.parseToJsonElement(BasicCatalog.source) as JsonObject
        val components = document["components"] as JsonObject
        val name = (components["Icon"] as JsonObject)["properties"]
            ?.let { (it as JsonObject)["name"] } as JsonObject
        val branches = name["oneOf"] as JsonArray
        val values = branches.firstNotNullOf { (it as? JsonObject)?.get("enum") as? JsonArray }
        return values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}
