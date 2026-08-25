package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The shared `common_types.json` shapes that are not part of a message envelope. */
class CommonTypeTest {
    private val json = A2uiJson.strict

    @Test
    fun `children are either a fixed list or a list template`() {
        assertEquals(
            ChildList.Static(listOf("a", "b")),
            json.decodeFromString<ChildList>("""["a","b"]"""),
        )
        assertEquals(
            ChildList.Template(componentId = "row", path = "/items"),
            json.decodeFromString<ChildList>("""{"componentId":"row","path":"/items"}"""),
        )
        assertFailsWith<SerializationException> { json.decodeFromString<ChildList>("""["a",2]""") }
        assertFailsWith<SerializationException> {
            json.decodeFromString<ChildList>("""{"componentId":"row"}""")
        }
    }

    @Test
    fun `children round trip back to the json they came from`() {
        for (case in listOf("""["a","b"]""", """{"componentId":"row","path":"/items"}""")) {
            assertEquals(case, json.encodeToString(json.decodeFromString<ChildList>(case)))
        }
    }

    @Test
    fun `an action is an agent event or a local function call but never both`() {
        assertEquals(
            Action.Event(ActionEvent(name = "submit")),
            json.decodeFromString<Action>("""{"event":{"name":"submit"}}"""),
        )
        assertEquals(
            Action.Invoke(FunctionCall("openUrl")),
            json.decodeFromString<Action>("""{"functionCall":{"call":"openUrl"}}"""),
        )
        assertFailsWith<SerializationException> {
            json.decodeFromString<Action>("""{"event":{"name":"a"},"functionCall":{"call":"f"}}""")
        }
        assertFailsWith<SerializationException> { json.decodeFromString<Action>("""{}""") }
        assertFailsWith<SerializationException> {
            json.decodeFromString<Action>("""{"event":{"name":"a"},"extra":1}""")
        }
    }

    @Test
    fun `an event context resolves each value as a dynamic value`() {
        val action = assertIs<Action.Event>(
            json.decodeFromString<Action>(
                """{"event":{"name":"buy","userMessage":{"path":"/label"},
                   "context":{"id":"static","qty":{"path":"/cart/qty"}}}}""",
            ),
        )
        assertEquals(DataBinding("/label"), action.event.userMessage)
        assertEquals(
            mapOf(
                "id" to DynamicValue.Literal(JsonPrimitive("static")),
                "qty" to DataBinding("/cart/qty"),
            ),
            action.event.context,
        )
    }

    @Test
    fun `accessibility attributes accept literals and bindings alike`() {
        val attributes = json.decodeFromString<AccessibilityAttributes>(
            """{"label":"Mute","description":{"path":"/hint"},"live":"assertive","hidden":{"call":"isHidden"}}""",
        )
        assertEquals(DynamicString.Literal("Mute"), attributes.label)
        assertEquals(DataBinding("/hint"), attributes.description)
        assertEquals(LiveRegion.ASSERTIVE, attributes.live)
        assertEquals(FunctionCall("isHidden"), attributes.hidden)
        assertFailsWith<SerializationException> {
            json.decodeFromString<AccessibilityAttributes>("""{"live":"shouty"}""")
        }
    }

    @Test
    fun `an omitted accessibility attribute stays omitted on the way out`() {
        assertEquals(
            """{"label":"Mute"}""",
            json.encodeToString(AccessibilityAttributes(label = DynamicString.Literal("Mute"))),
        )
    }

    @Test
    fun `a check rule binds its condition to a path or a call`() {
        assertEquals(
            CheckRule(condition = FunctionCall("required"), message = "Required"),
            json.decodeFromString<CheckRule>("""{"condition":{"call":"required"},"message":"Required"}"""),
        )
        assertFailsWith<SerializationException> {
            json.decodeFromString<CheckRule>("""{"condition":"required"}""")
        }
    }

    @Test
    fun `a validation result keeps the keys the schema leaves open`() {
        val result = json.decodeFromString<ValidationResult>(
            """{"valid":false,"code":"EXPIRED_CARD","message":"Expired","severity":"warning","vendor":1}""",
        )
        assertEquals(false, result.valid)
        assertEquals(Severity.WARNING, result.severity)
        assertEquals(mapOf("vendor" to JsonPrimitive(1)), result.additional)
        assertFailsWith<SerializationException> { json.decodeFromString<ValidationResult>("""{}""") }
    }

    @Test
    fun `the reserved surface container names are fixed`() {
        assertEquals("Surface", Surface.COMPONENT)
        assertEquals("root", Surface.ROOT_ID)
    }
}
