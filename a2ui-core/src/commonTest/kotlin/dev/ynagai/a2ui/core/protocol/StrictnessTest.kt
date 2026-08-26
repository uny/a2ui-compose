package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The rules that keep a malformed payload from being read as a well-formed one.
 *
 * Every case here is a payload the v1.0 schemas reject that an earlier revision accepted by
 * reading a wrong-typed value as absent, or a value this library emitted that it could not read
 * back. They are grouped together because they share one cause: a modelled key whose value fails
 * its type check must be *rejected*, never silently treated as missing — otherwise the default
 * that gets applied is one the payload never asked for, and the offending value is dropped on the
 * way back out rather than reported.
 */
class StrictnessTest {
    private val json = A2uiJson.strict

    @Test
    fun `a wrong-typed catalog keyword is rejected rather than read as absent`() {
        // Was: protocolVersion silently null, so effectiveProtocolVersion reported "0.9".
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>("""{"catalogId":"c","protocolVersion":1.0}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>("""{"catalogId":"c","title":42}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>("""{"catalogId":42}""")
        }
    }

    @Test
    fun `a wrong-typed function keyword is rejected rather than defaulted`() {
        // Was: allowedCallers silently null, so a function marked agentOnly read as rendererOnly.
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","functions":{"f":{"returnType":"any","allowedCallers":["agentOnly"]}}}""",
            )
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","functions":{"f":{"returnType":"any","requiresUserActivation":"true"}}}""",
            )
        }
    }

    @Test
    fun `a catalog whose components or functions are not objects is rejected`() {
        // Was: decoded as an empty catalog, so every component reference read as undefined.
        for (bad in listOf(""""components":[]""", """"functions":42""")) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<CatalogDefinition>("""{"catalogId":"c",$bad}""")
            }
        }
    }

    @Test
    fun `a catalog definition built in Kotlin survives a round trip`() {
        // Was: serialize emitted only the raw schema, so returnType and the composition keywords
        // were dropped and the result no longer decoded.
        val catalog = CatalogDefinition(
            catalogId = "c",
            components = mapOf(
                "Text" to ComponentDefinition(
                    schema = buildJsonObject { put("type", JsonPrimitive("object")) },
                    allowedParents = listOf("Surface"),
                    allowedChildren = emptyList(),
                ),
            ),
            functions = mapOf(
                "f" to FunctionDefinition(
                    schema = FunctionCallValidationSchema(
                        buildJsonObject { put("type", JsonPrimitive("object")) },
                    ),
                    returnType = ReturnType.VOID,
                    allowedCallers = AllowedCallers.AGENT_ONLY,
                    // false, not true: an activation-gated function may only be rendererOnly.
                    requiresUserActivation = false,
                ),
            ),
        )
        val decoded = json.decodeFromString<CatalogDefinition>(json.encodeToString(catalog))
        val text = decoded.components.getValue("Text")
        assertEquals(listOf("Surface"), text.allowedParents)
        assertEquals(emptyList(), text.allowedChildren)
        val f = decoded.functions.getValue("f")
        assertEquals(ReturnType.VOID, f.returnType)
        assertEquals(AllowedCallers.AGENT_ONLY, f.allowedCallers)
        assertEquals(false, f.requiresUserActivation)
    }

    @Test
    fun `a wrong-typed component catalogId is rejected rather than dropped`() {
        // Was: read as null and filtered out of `properties`, so the component silently re-bound
        // to the surface's default catalog.
        assertFailsWith<SerializationException> {
            json.decodeFromString<AgentToRendererMessage>(
                """{"version":"v1.0","createSurface":{"surfaceId":"s","components":[{"id":"r","component":"T","catalogId":123}]}}""",
            )
        }
    }

    @Test
    fun `a wrong-typed action or validation field is rejected rather than erased`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<RendererToAgentMessage>(
                """{"version":"v1.0","action":{"name":"n","surfaceId":"s","sourceComponentId":"c","timestamp":"t","context":{},"userMessage":42}}""",
            )
        }
        // Was: severity read as null, so the ERROR default was reported for a payload that did
        // not say ERROR.
        assertFailsWith<SerializationException> {
            json.decodeFromString<ValidationResult>("""{"valid":true,"severity":7}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<ValidationResult>("""{"valid":true,"code":42}""")
        }
    }

    @Test
    fun `error scope exclusivity is decided by key presence not by parsed type`() {
        // Was: a wrong-typed surfaceId read as absent, so a payload carrying BOTH scope keys
        // passed the oneOf check and was re-encoded with the conflicting key gone.
        assertFailsWith<SerializationException> {
            json.decodeFromString<RendererToAgentMessage>(
                """{"version":"v1.0","error":{"code":"E","message":"m","surfaceId":123,"functionCallId":"c1"}}""",
            )
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<RendererToAgentMessage>(
                """{"version":"v1.0","error":{"code":"E","message":"m","surfaceId":"s","functionCallId":"c1"}}""",
            )
        }
    }

    @Test
    fun `the renderer data model is held to the protocol version`() {
        // Was: any version string accepted, and an absent one silently defaulted to v1.0.
        assertFailsWith<SerializationException> {
            json.decodeFromString<RendererDataModel>("""{"version":"v0.1","surfaces":{}}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<RendererDataModel>("""{"surfaces":{}}""")
        }
        assertEquals(
            setOf("main"),
            json.decodeFromString<RendererDataModel>(
                """{"version":"v1.0","surfaces":{"main":{}}}""",
            ).surfaces.keys,
        )
    }

    @Test
    fun `a carry-through key never overwrites the modelled key it collides with`() {
        // Was: the bag was written last, so a property named `id` rewrote the component's id.
        assertEquals(
            """{"id":"root","component":"Text","evil":1}""",
            json.encodeToString(
                Component.serializer(),
                Component(
                    id = "root",
                    component = "Text",
                    properties = mapOf("id" to JsonPrimitive("hijacked"), "evil" to JsonPrimitive(1)),
                ),
            ),
        )
        assertEquals(
            """{"valid":true}""",
            json.encodeToString(
                ValidationResult.serializer(),
                ValidationResult(valid = true, additional = mapOf("valid" to JsonPrimitive(false))),
            ),
        )
        assertEquals(
            """{"v1.0":{"supportedCatalogIds":["a"]}}""",
            json.encodeToString(
                AgentCapabilities.serializer(),
                AgentCapabilities(
                    AgentCapabilitiesV1(listOf("a")),
                    otherVersions = mapOf("v1.0" to JsonPrimitive("shadow")),
                ),
            ),
        )
    }

    @Test
    fun `a carry-through key cannot speak for a modelled field that is absent`() {
        // The collision is only masked by a later `put` when the modelled value is non-null, so
        // the null case is the one that leaks: `severity = null` means "the payload did not say",
        // which a consumer reads as Severity.DEFAULT. Emitting the bag's `severity` turned that
        // into WARNING on the next decode.
        val encoded = json.encodeToString(
            ValidationResult.serializer(),
            ValidationResult(
                valid = true,
                severity = null,
                additional = mapOf("severity" to JsonPrimitive("warning")),
            ),
        )
        assertEquals("""{"valid":true}""", encoded)
        assertEquals(null, json.decodeFromString<ValidationResult>(encoded).severity)
    }

    @Test
    fun `a decoded payload re-encodes to the bytes it came from`() {
        // Filtering the carry-through bag rather than reordering it is what preserves this; one
        // site reordered instead and moved `detail` in front of `valid`.
        for (case in listOf(
            """{"valid":true,"detail":"x"}""",
            """{"valid":false,"code":"E","message":"m","severity":"warning","vendor":1}""",
        )) {
            assertEquals(case, json.encodeToString(json.decodeFromString<ValidationResult>(case)))
        }
    }

    @Test
    fun `a verbatim definition keyword survives re-encoding under lenient`() {
        // The typed `metadata` re-encode is lossy where the raw definition is not: under lenient,
        // Metadata drops the keys it does not model, so overwriting the raw value with it lost
        // them. The raw schema now wins for any key it already carries.
        val source = """{"catalogId":"c","components":{"T":{"metadata":{"vendor":1}}}}"""
        assertEquals(
            source,
            A2uiJson.lenient.encodeToString(
                A2uiJson.lenient.decodeFromString<CatalogDefinition>(source),
            ),
        )
    }

    @Test
    fun `a schema keyword bag cannot speak for a modelled catalog key either`() {
        // The last site left on the old pattern. Only a hand-built value can collide here, since
        // the decoder filters schemaKeywords down to the three $-keywords.
        assertEquals(
            """{"catalogId":"c"}""",
            json.encodeToString(
                CatalogDefinition.serializer(),
                CatalogDefinition(
                    catalogId = "c",
                    schemaKeywords = mapOf("protocolVersion" to JsonPrimitive("1.0")),
                ),
            ),
        )
    }

    @Test
    fun `lenient tolerates an unknown envelope key that strict refuses`() {
        // Was: the envelope counted keys itself and never consulted ignoreUnknownKeys, so the
        // opt-in configuration rejected a trace id just as strict did.
        val payload = """{"version":"v1.0","deleteSurface":{"surfaceId":"s"},"traceId":"x"}"""
        assertFailsWith<SerializationException> {
            json.decodeFromString<AgentToRendererMessage>(payload)
        }
        assertEquals(
            "s",
            assertIs<DeleteSurfaceMessage>(
                A2uiJson.lenient.decodeFromString<AgentToRendererMessage>(payload),
            ).surfaceId,
        )
    }

    @Test
    fun `a null literal is unconstructible rather than unreadable once written`() {
        // Was: encoding produced a bare `null` that this same serializer then refused to read.
        assertFailsWith<A2uiFormatException> { DynamicValue.Literal(JsonNull) }
        assertEquals(
            DynamicValue.Literal(JsonPrimitive(1)),
            json.decodeFromString(
                DynamicValueSerializer,
                json.encodeToString(DynamicValueSerializer, DynamicValue.Literal(JsonPrimitive(1))),
            ),
        )
    }

    @Test
    fun `a non-finite number literal is unconstructible`() {
        // JSON has no way to write either, so the guard belongs on the type — the same rule
        // DynamicValue.Literal applies to JsonNull.
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<A2uiFormatException> { DynamicNumber.Literal(bad) }
        }
        assertEquals(1.5, DynamicNumber.Literal(1.5).value)
    }

    @Test
    fun `a number outside the range JSON can round trip is rejected on the way in`() {
        // Was: 1e999 decoded to Infinity and only failed later, while encoding the renderer's
        // own state; and 2^63 was re-emitted as 9223372036854775807, a different integer.
        assertFailsWith<SerializationException> {
            json.decodeFromString(DynamicNumberSerializer, "1e999")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(DynamicNumberSerializer, "-1e999")
        }
        // 2^63 must not come back as Long.MAX_VALUE. Asserted as a value rather than as text:
        // `Double.toString` differs between JVM and JS/Wasm, so pinning the spelling here would
        // pass on jvmTest and fail on the browser targets.
        val atLongBoundary = json.encodeToString(
            DynamicNumberSerializer,
            json.decodeFromString(DynamicNumberSerializer, "9223372036854775808"),
        )
        assertNotEquals("9223372036854775807", atLongBoundary)
        assertEquals(
            DynamicNumber.Literal(9223372036854775808.0),
            json.decodeFromString(DynamicNumberSerializer, atLongBoundary),
        )
        assertEquals("42", json.encodeToString(DynamicNumberSerializer, DynamicNumber.Literal(42.0)))
    }

    @Test
    fun `the catalog and envelope protocol versions are spelled differently on purpose`() {
        val catalog = json.decodeFromString<CatalogDefinition>(
            """{"catalogId":"c","protocolVersion":"1.0"}""",
        )
        assertEquals(CatalogDefinition.PROTOCOL_VERSION, catalog.effectiveProtocolVersion)
        assertEquals("v1.0", dev.ynagai.a2ui.core.A2ui.PROTOCOL_VERSION)
    }

    @Test
    fun `a function definition that omits required reports an empty list`() {
        val decoded = json.decodeFromString<CatalogDefinition>(
            """{"catalogId":"c","functions":{"f":{"type":"object","returnType":"any"}}}""",
        )
        assertEquals(emptyList(), decoded.functions.getValue("f").schema.required)
    }

    @Test
    fun `a surface data model must be an object`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<RendererDataModel>("""{"version":"v1.0","surfaces":{"a":1}}""")
        }
        assertEquals(
            JsonObject(emptyMap()),
            json.decodeFromString<RendererDataModel>(
                """{"version":"v1.0","surfaces":{"a":{}}}""",
            ).surfaces.getValue("a"),
        )
    }
}
