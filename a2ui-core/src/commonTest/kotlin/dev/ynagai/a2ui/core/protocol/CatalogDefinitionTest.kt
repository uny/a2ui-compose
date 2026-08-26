package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A catalog is read as typed metadata wrapped around the raw JSON Schema of each definition. The
 * schema itself stays unparsed — see [FunctionCallValidationSchema].
 */
class CatalogDefinitionTest {
    private val json = A2uiJson.strict

    private val catalog = """
        {
          "catalogId": "example.com:testing",
          "protocolVersion": "1.0",
          "title": "Testing catalog",
          "components": {
            "Text": {
              "type": "object",
              "properties": {"component": {"const": "Text"}, "text": {"type": "string"}},
              "required": ["component", "text"],
              "allowedParents": ["Surface"]
            }
          },
          "functions": {
            "openUrl": {
              "type": "object",
              "properties": {
                "call": {"const": "openUrl"},
                "args": {"type": "object", "properties": {"url": {"type": "string"}}}
              },
              "required": ["call"],
              "returnType": "void",
              "allowedCallers": "rendererOnly",
              "requiresUserActivation": true
            }
          }
        }
    """.trimIndent()

    @Test
    fun `a catalog reads its metadata and keeps each definition schema verbatim`() {
        val decoded = json.decodeFromString<CatalogDefinition>(catalog)
        assertEquals("example.com:testing", decoded.catalogId)
        assertEquals("1.0", decoded.effectiveProtocolVersion)

        val text = decoded.components.getValue("Text")
        assertEquals(listOf("Surface"), text.allowedParents)
        assertNull(text.allowedChildren)
        val textProperties = text.schema["properties"] as kotlinx.serialization.json.JsonObject
        assertEquals(json.parseToJsonElement("""{"const":"Text"}"""), textProperties["component"])

        val openUrl = decoded.functions.getValue("openUrl")
        assertEquals(ReturnType.VOID, openUrl.returnType)
        assertEquals(AllowedCallers.RENDERER_ONLY, openUrl.effectiveAllowedCallers)
        assertEquals(true, openUrl.requiresUserActivation)
        assertEquals("openUrl", openUrl.schema.callName)
        assertEquals(listOf("call"), openUrl.schema.required)
        assertEquals(
            json.parseToJsonElement("""{"type":"object","properties":{"url":{"type":"string"}}}"""),
            assertNotNull(openUrl.schema.argsSchema),
        )
    }

    @Test
    fun `the schema defaults apply when a catalog omits them`() {
        val minimal = json.decodeFromString<CatalogDefinition>(
            """{"catalogId":"c","functions":{"f":{"type":"object","properties":{"call":{"const":"f"}},"required":["call"],"returnType":"any"}}}""",
        )
        assertEquals("0.9", minimal.effectiveProtocolVersion)
        assertNull(minimal.protocolVersion)
        val function = minimal.functions.getValue("f")
        assertNull(function.allowedCallers)
        assertEquals(AllowedCallers.RENDERER_ONLY, function.effectiveAllowedCallers)
    }

    @Test
    fun `a catalog needs an id and its functions need a known return type`() {
        assertFailsWith<SerializationException> { json.decodeFromString<CatalogDefinition>("""{"title":"x"}""") }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","functions":{"f":{"type":"object","returnType":"widget"}}}""",
            )
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","functions":{"f":{"type":"object"}}}""",
            )
        }
    }

    @Test
    fun `a malformed composition keyword is rejected instead of read as unconstrained`() {
        for (bad in listOf(""""Surface"""", """[123]""")) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<CatalogDefinition>(
                    """{"catalogId":"c","components":{"AppLayout":{"type":"object","allowedParents":$bad}}}""",
                )
            }
        }
    }

    @Test
    fun `a catalog rejects a top-level key the schema does not define`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>("""{"catalogId":"c","unexpected":true}""")
        }
    }

    @Test
    fun `an allOf-shaped function definition exposes its branches instead of a call name`() {
        val decoded = json.decodeFromString<CatalogDefinition>(
            """{"catalogId":"c","functions":{"f":{"type":"object","allOf":[{"${'$'}ref":"#/x"}],"returnType":"boolean"}}}""",
        )
        val function = decoded.functions.getValue("f")
        assertNull(function.schema.callName)
        assertEquals(1, function.schema.allOf?.size)
    }

    @Test
    fun `a catalog keeps its schema keywords so its refs stay resolvable`() {
        val source = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","${'$'}id":"https://x/c.json","${'$'}defs":{"anyComponent":{"type":"object"},"anyFunction":{"type":"object"}},"catalogId":"c"}"""
        val decoded = json.decodeFromString<CatalogDefinition>(source)
        assertEquals(setOf("${'$'}schema", "${'$'}id", "${'$'}defs"), decoded.schemaKeywords.keys)
        assertEquals(source, json.encodeToString(decoded))
    }

    @Test
    fun `renderer capabilities carry inline catalogs`() {
        val capabilities = json.decodeFromString<RendererCapabilities>(
            """{"v1.0":{"supportedCatalogIds":["a"],"inlineCatalogs":[{"catalogId":"b"}]}}""",
        )
        assertEquals(listOf("a"), capabilities.v1.supportedCatalogIds)
        assertEquals("b", capabilities.v1.inlineCatalogs?.single()?.catalogId)
    }

    @Test
    fun `agent capabilities default to refusing inline catalogs`() {
        val capabilities = json.decodeFromString<AgentCapabilities>("""{"v1.0":{"supportedCatalogIds":["a"]}}""")
        assertEquals(false, capabilities.v1.acceptsInlineCatalogsOrDefault)
        assertNull(capabilities.v1.acceptsInlineCatalogs)
    }

    @Test
    fun `a card advertising other protocol versions is kept rather than refused`() {
        val source = """{"v1.0":{"supportedCatalogIds":["a"]},"v0.9":{"supportedCatalogIds":["legacy"]}}"""
        val capabilities = json.decodeFromString<AgentCapabilities>(source)
        assertEquals(listOf("a"), capabilities.v1.supportedCatalogIds)
        assertEquals(setOf("v0.9"), capabilities.otherVersions.keys)
        assertEquals(source, json.encodeToString(capabilities))
    }

    @Test
    fun `a card without a v1_0 entry is refused`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<AgentCapabilities>("""{"v0.9":{"supportedCatalogIds":["legacy"]}}""")
        }
    }

    @Test
    fun `the renderer data model is keyed by surface`() {
        val model = json.decodeFromString<RendererDataModel>(
            """{"version":"v1.0","surfaces":{"main":{"user":{"name":"Alice"}}}}""",
        )
        assertEquals(setOf("main"), model.surfaces.keys)
        assertEquals(
            """{"version":"v1.0","surfaces":{"main":{"user":{"name":"Alice"}}}}""",
            json.encodeToString(model),
        )
    }
}
