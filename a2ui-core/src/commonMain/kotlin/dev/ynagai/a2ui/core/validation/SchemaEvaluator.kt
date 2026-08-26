package dev.ynagai.a2ui.core.validation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Checks a JSON value against the subset of JSON Schema 2020-12 that A2UI v1.0 actually uses.
 *
 * **This is not a general JSON Schema validator and must not be described as one.** It implements
 * the keywords the published schemas assert with, listed in [SUPPORTED_KEYWORDS], and reports any
 * other keyword it meets through [SchemaValidation.unsupportedKeywords] rather than pretending to
 * have applied it. The distinction matters because the failure mode of a partial validator is
 * silent acceptance: a catalog that constrains a property with a keyword this does not know would
 * validate against nothing at all, and the payload would render.
 *
 * The scope was chosen by reading the keywords out of the published documents rather than by
 * picking a plausible subset — see the test that asserts the basic catalog stays inside it.
 *
 * Instances are agent-controlled, and so are the schemas once a catalog may be inlined in a
 * capabilities message. Everything here is therefore bounded by [ValidationLimits] rather than by
 * the shape of the input, and no value from the instance is ever copied into a message
 * ([SchemaViolation]).
 */
public class SchemaEvaluator(
    private val registry: SchemaRegistry,
    private val limits: ValidationLimits = ValidationLimits.DEFAULT,
) {
    /**
     * Whether [instance] satisfies the schema at [schema], which is read from [location]'s
     * document so that a relative `$ref` inside it resolves against the right base.
     */
    public fun validate(
        schema: JsonElement,
        location: SchemaLocation,
        instance: JsonElement,
    ): SchemaValidation {
        val run = Run()
        val outcome = try {
            run.evaluate(schema, location, instance, InstancePath.ROOT, depth = 0, collect = true)
        } catch (exhausted: BudgetExhausted) {
            return SchemaValidation(
                violations = listOf(exhausted.violation),
                unsupportedKeywords = run.unsupported,
                truncated = true,
            )
        }
        return SchemaValidation(
            violations = outcome.violations,
            unsupportedKeywords = run.unsupported,
            truncated = run.truncated,
        )
    }

    /**
     * One validation's mutable state.
     *
     * The budget is held here rather than per subschema for the reason the function evaluator's
     * is: a per-node budget is not a budget. A schema that applies `oneOf` over eighteen component
     * definitions, each of which reaches back into the catalog through a dynamic value, costs the
     * product of those fan-outs, and only a total charged across the whole run bounds it.
     */
    private inner class Run {
        var steps: Int = 0
        var truncated: Boolean = false
        val unsupported: MutableSet<String> = mutableSetOf()

        /** (subschema, instance location) pairs on the current path, for the cycle guard. */
        private val active: MutableSet<Pair<SchemaLocation, String>> = mutableSetOf()

        fun charge(at: InstancePath) {
            if (++steps > limits.maxSteps) {
                throw BudgetExhausted(
                    SchemaViolation(at.render(), "the schema did not settle within ${limits.maxSteps} steps."),
                )
            }
        }

        /**
         * Why none of [branches] matched, as the violations of the closest one.
         *
         * The branches were tried without collecting, because on the overwhelmingly common path
         * one of them matches and the others' messages are thrown away. That leaves nothing to
         * say when they all fail, and "no alternative matched" is not something an agent can act
         * on: the alternatives here are every function the catalog defines, and the answer the
         * caller needs is which argument of the one it named was wrong.
         *
         * So the branches are re-run collecting, on the failure path only, and the closest one is
         * reported. Closest means: it did not fail a discriminator ([Outcome.discriminated]) and,
         * among those, it complained least. Counting complaints alone is not enough and picks the
         * wrong branch on the very payload this is for — a `Button` missing its `child` draws more
         * complaints than a `Divider` that objects only to the name, so the message would be
         * `expected \`Divider\`` on a component the agent never mentioned.
         *
         * The step budget is charged for this exactly as for the first pass, so a payload that
         * fails deep inside a large `oneOf` cannot make a renderer pay twice without limit.
         */
        fun explain(
            branches: JsonArray,
            keyword: String,
            location: SchemaLocation,
            instance: JsonElement,
            at: InstancePath,
            depth: Int,
            collect: Boolean,
        ): List<Outcome> {
            if (!collect) return listOf(Outcome.invalid(false, at, "does not match."))
            var closest: Outcome? = null
            branches.forEachIndexed { index, branch ->
                val outcome = evaluate(
                    branch,
                    location.child(keyword, index.toString()),
                    instance,
                    at,
                    depth + 1,
                    collect = true,
                )
                if (outcome.violations.isNotEmpty() && outcome.closerThan(closest)) closest = outcome
            }
            return listOfNotNull(
                closest ?: Outcome.invalid(true, at, "no alternative the catalog allows here matched."),
            )
        }

        fun evaluate(
            schema: JsonElement,
            location: SchemaLocation,
            instance: JsonElement,
            at: InstancePath,
            depth: Int,
            collect: Boolean,
        ): Outcome {
            charge(at)
            if (depth > limits.maxDepth) {
                throw BudgetExhausted(
                    SchemaViolation(at.render(), "the schema nests deeper than ${limits.maxDepth}."),
                )
            }
            // A boolean schema. `false` appears throughout v1.0 as `additionalProperties: false`.
            (schema as? JsonPrimitive)?.booleanOrNull?.let { allowed ->
                return if (allowed) {
                    Outcome.VALID
                } else {
                    Outcome.invalid(collect, at, "no value is allowed here.")
                }
            }
            val obj = schema as? JsonObject
                ?: return Outcome.invalid(collect, at, "the catalog's schema is not a schema.")
            return applyKeywords(obj, location, instance, at, depth, collect)
        }

        private fun applyKeywords(
            schema: JsonObject,
            location: SchemaLocation,
            instance: JsonElement,
            at: InstancePath,
            depth: Int,
            collect: Boolean,
        ): Outcome {
            val violations = if (collect) mutableListOf<SchemaViolation>() else null
            var properties: MutableSet<String>? = null
            var items = 0
            var discriminated = false

            fun merge(outcome: Outcome) {
                if (outcome.violations.isNotEmpty()) {
                    violations?.let { list ->
                        outcome.violations.forEach { violation ->
                            if (list.size < limits.maxViolations) list += violation else truncated = true
                        }
                    }
                }
                if (outcome.evaluatedProperties.isNotEmpty()) {
                    (properties ?: mutableSetOf<String>().also { properties = it })
                        .addAll(outcome.evaluatedProperties)
                }
                if (outcome.evaluatedItems > items) items = outcome.evaluatedItems
                if (outcome.discriminated) discriminated = true
            }

            fun fail(message: String) {
                violations?.let { list ->
                    if (list.size < limits.maxViolations) {
                        list += SchemaViolation(at.render(), message)
                    } else {
                        truncated = true
                    }
                }
            }

            /** Whether anything has failed so far, whether or not violations are being collected. */
            var failed = false
            fun reject(message: String) {
                failed = true
                fail(message)
            }
            fun absorb(outcome: Outcome) {
                if (!outcome.valid) failed = true
                merge(outcome)
            }

            for ((keyword, value) in schema) {
                if (keyword in ANNOTATION_KEYWORDS) continue
                when (keyword) {
                    "\$ref" -> {
                        val reference = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        if (reference == null) {
                            reject("the catalog's `\$ref` is not a string.")
                            continue
                        }
                        val resolved = registry.resolve(reference, location)
                        if (resolved == null) {
                            reject("the catalog refers to `$reference`, which is not a schema this renderer holds.")
                            continue
                        }
                        val key = resolved.location to at.render()
                        if (!active.add(key)) {
                            // The reference graph is cyclic by design; revisiting the same
                            // subschema at the same place in the instance can only repeat work.
                            continue
                        }
                        try {
                            absorb(evaluate(resolved.schema, resolved.location, instance, at, depth + 1, collect))
                        } finally {
                            active.remove(key)
                        }
                    }

                    "type" -> if (!instance.matchesType(value)) {
                        reject("expected ${value.describeType()}, but the value is ${instance.typeName()}.")
                    }

                    "const" -> if (instance != value) {
                        // Safe to quote: the expected value comes from the catalog, not the wire.
                        discriminated = true
                        reject("expected ${value.describeLiteral()}.")
                    }

                    "enum" -> {
                        val options = value as? JsonArray
                        if (options == null) {
                            reject("the catalog's `enum` is not an array.")
                        } else if (options.none { it == instance }) {
                            discriminated = true
                            reject("expected one of ${options.joinToString { it.describeLiteral() }}.")
                        }
                    }

                    "required" -> {
                        val obj = instance as? JsonObject
                        val names = (value as? JsonArray).orEmptyStrings()
                        if (obj != null) {
                            names.filterNot(obj::containsKey).forEach { reject("`$it` is required.") }
                        }
                    }

                    "properties" -> {
                        val obj = instance as? JsonObject
                        val subschemas = value as? JsonObject
                        if (obj != null && subschemas != null) {
                            for ((name, subschema) in subschemas) {
                                val child = obj[name] ?: continue
                                (properties ?: mutableSetOf<String>().also { properties = it }).add(name)
                                absorb(
                                    evaluate(
                                        subschema,
                                        location.child("properties", name),
                                        child,
                                        at.child(name),
                                        depth + 1,
                                        collect,
                                    ),
                                )
                            }
                        }
                    }

                    "additionalProperties" -> {
                        val obj = instance as? JsonObject
                        if (obj != null) {
                            val named = schema.declaredPropertyNames()
                            for ((name, child) in obj) {
                                if (name in named) continue
                                (properties ?: mutableSetOf<String>().also { properties = it }).add(name)
                                absorb(
                                    evaluate(
                                        value,
                                        location.child("additionalProperties"),
                                        child,
                                        at.child(name),
                                        depth + 1,
                                        collect,
                                    ),
                                )
                            }
                        }
                    }

                    "items" -> {
                        val array = instance as? JsonArray
                        if (array != null) {
                            array.forEachIndexed { index, child ->
                                absorb(
                                    evaluate(
                                        value,
                                        location.child("items"),
                                        child,
                                        at.index(index),
                                        depth + 1,
                                        collect,
                                    ),
                                )
                            }
                            if (array.size > items) items = array.size
                        }
                    }

                    "minItems" -> {
                        val array = instance as? JsonArray
                        val minimum = (value as? JsonPrimitive)?.longOrNull
                        if (array != null && minimum != null && array.size < minimum) {
                            reject(
                                if (minimum == 1L) "at least one entry is required, but the array is empty."
                                else "at least $minimum entries are required, but the array has ${array.size}.",
                            )
                        }
                    }

                    "minimum" -> {
                        val number = (instance as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                        val minimum = (value as? JsonPrimitive)?.doubleOrNull
                        if (number != null && minimum != null && number < minimum) {
                            reject("must be at least ${value.describeLiteral()}.")
                        }
                    }

                    "allOf" -> (value as? JsonArray)?.forEachIndexed { index, branch ->
                        absorb(
                            evaluate(
                                branch,
                                location.child("allOf", index.toString()),
                                instance,
                                at,
                                depth + 1,
                                collect,
                            ),
                        )
                    }

                    "anyOf" -> {
                        val branches = value as? JsonArray ?: JsonArray(emptyList())
                        var matched = false
                        branches.forEachIndexed { index, branch ->
                            val outcome = evaluate(
                                branch,
                                location.child("anyOf", index.toString()),
                                instance,
                                at,
                                depth + 1,
                                collect = false,
                            )
                            if (outcome.valid) {
                                matched = true
                                merge(outcome.withoutViolations())
                            }
                        }
                        if (!matched) {
                            failed = true
                            explain(branches, "anyOf", location, instance, at, depth, collect)
                                .forEach(::merge)
                        }
                    }

                    "oneOf" -> {
                        val branches = value as? JsonArray ?: JsonArray(emptyList())
                        var match: Outcome? = null
                        var matches = 0
                        for ((index, branch) in branches.withIndex()) {
                            val outcome = evaluate(
                                branch,
                                location.child("oneOf", index.toString()),
                                instance,
                                at,
                                depth + 1,
                                collect = false,
                            )
                            if (outcome.valid) {
                                matches++
                                if (matches == 1) match = outcome else break
                            }
                        }
                        when (matches) {
                            0 -> {
                                failed = true
                                explain(branches, "oneOf", location, instance, at, depth, collect)
                                    .forEach(::merge)
                            }
                            1 -> merge(match!!.withoutViolations())
                            else -> reject(
                                "the value matches more than one of the alternatives the catalog allows here.",
                            )
                        }
                    }

                    "not" -> {
                        val outcome = evaluate(
                            value,
                            location.child("not"),
                            instance,
                            at,
                            depth + 1,
                            collect = false,
                        )
                        if (outcome.valid) reject("this value is not allowed here.")
                    }

                    "if" -> {
                        val condition = evaluate(
                            value,
                            location.child("if"),
                            instance,
                            at,
                            depth + 1,
                            collect = false,
                        )
                        val branch = if (condition.valid) "then" else "else"
                        if (condition.valid) merge(condition.withoutViolations())
                        schema[branch]?.let { subschema ->
                            absorb(
                                evaluate(
                                    subschema,
                                    location.child(branch),
                                    instance,
                                    at,
                                    depth + 1,
                                    collect,
                                ),
                            )
                        }
                    }

                    // Applied by `if` above, which needs the condition's outcome to choose
                    // between them. Without an `if` they assert nothing, as the specification says.
                    "then", "else" -> Unit

                    "format" -> {
                        val name = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        val text = (instance as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        if (name != null && text != null) {
                            when (checkFormat(name, text)) {
                                FormatVerdict.VALID -> Unit
                                FormatVerdict.INVALID -> reject("is not a valid `$name`.")
                                FormatVerdict.UNKNOWN -> unsupported += "format: $name"
                            }
                        }
                    }

                    // Deferred: it consults the annotations every other keyword produced, so it
                    // cannot run inside this loop.
                    "unevaluatedProperties" -> Unit

                    else -> unsupported += keyword
                }
            }

            schema["unevaluatedProperties"]?.let { subschema ->
                val obj = instance as? JsonObject
                // Only meaningful once the rest of the schema held: the properties an alternative
                // evaluated are not evaluated at all if that alternative did not match.
                if (obj != null && !failed) {
                    val evaluated = properties ?: emptySet<String>()
                    for ((name, child) in obj) {
                        if (name in evaluated) continue
                        val outcome = evaluate(
                            subschema,
                            location.child("unevaluatedProperties"),
                            child,
                            at.child(name),
                            depth + 1,
                            collect = false,
                        )
                        if (!outcome.valid) {
                            reject("`$name` is not a property the catalog defines here.")
                        } else {
                            (properties ?: mutableSetOf<String>().also { properties = it }).add(name)
                        }
                    }
                }
            }

            return Outcome(
                violations = violations?.toList() ?: if (failed) INVALID_MARKER else emptyList(),
                evaluatedProperties = properties ?: emptySet(),
                evaluatedItems = items,
                discriminated = discriminated,
            )
        }
    }

    private companion object {
        /**
         * A stand-in for "this failed" used when violations are not being collected, so that a
         * speculative branch of `oneOf` costs one shared list rather than one message per failure.
         */
        val INVALID_MARKER: List<SchemaViolation> = listOf(SchemaViolation("", "does not match."))
    }
}

/** Signals that a run hit [ValidationLimits]; carried out as an exception to unwind the recursion. */
private class BudgetExhausted(val violation: SchemaViolation) : RuntimeException(violation.message)

/**
 * What one subschema said about one value.
 *
 * @property discriminated whether a `const` or `enum` failed anywhere under this subschema. v1.0
 *   uses `const` on `call` and on `component` as a discriminator — the catalog says so outright,
 *   with `"discriminator": {"propertyName": "component"}` on `anyComponent` — so a branch that
 *   failed one is a branch the value was never meant for, however few other complaints it had.
 *   That is what tells an alternative the value nearly matched from eighteen it did not.
 */
internal data class Outcome(
    val violations: List<SchemaViolation>,
    val evaluatedProperties: Set<String>,
    val evaluatedItems: Int,
    val discriminated: Boolean = false,
) {
    val valid: Boolean get() = violations.isEmpty()

    fun withoutViolations(): Outcome = if (violations.isEmpty()) this else copy(violations = emptyList())

    companion object {
        val VALID: Outcome = Outcome(emptyList(), emptySet(), 0)

        fun invalid(collect: Boolean, at: InstancePath, message: String): Outcome = Outcome(
            violations = if (collect) listOf(SchemaViolation(at.render(), message)) else NOT_COLLECTED,
            evaluatedProperties = emptySet(),
            evaluatedItems = 0,
        )

        private val NOT_COLLECTED: List<SchemaViolation> = listOf(SchemaViolation("", "does not match."))
    }
}

/**
 * Whether this failed branch explains the value better than [other].
 *
 * A branch that kept its discriminator always beats one that did not, however many other
 * complaints it accumulated; between two branches on the same side of that line, fewer complaints
 * wins.
 */
private fun Outcome.closerThan(other: Outcome?): Boolean = when {
    other == null -> true
    discriminated != other.discriminated -> !discriminated
    else -> violations.size < other.violations.size
}

private fun JsonArray?.orEmptyStrings(): List<String> =
    this?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty()

/** The property names a schema names directly, which `additionalProperties` applies to the rest of. */
private fun JsonObject.declaredPropertyNames(): Set<String> =
    (this["properties"] as? JsonObject)?.keys.orEmpty()

private fun SchemaLocation.child(vararg steps: String): SchemaLocation =
    copy(pointer = steps.fold(pointer) { acc, step -> "$acc/${step.escapePointer()}" })

private fun String.escapePointer(): String = replace("~", "~0").replace("/", "~1")

/** The keywords [SchemaEvaluator] applies. Anything else is reported rather than silently skipped. */
internal val SUPPORTED_KEYWORDS: Set<String> = setOf(
    "\$ref", "type", "const", "enum", "required", "properties", "additionalProperties", "items",
    "minItems", "minimum", "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "format",
    "unevaluatedProperties",
)

/**
 * Keywords that carry no assertion, so meeting one is not a gap in coverage.
 *
 * `$defs` holds subschemas that only `$ref` reaches, and the A2UI-specific keys (`returnType`,
 * `allowedParents`, and the rest) are catalog metadata that the model lifts out separately — they
 * constrain the catalog, not the instance.
 */
private val ANNOTATION_KEYWORDS: Set<String> = setOf(
    "\$schema", "\$id", "\$comment", "\$defs", "title", "description", "default", "examples",
    "deprecated", "readOnly", "writeOnly", "discriminator",
    "returnType", "allowedCallers", "requiresUserActivation", "allowedParents", "allowedChildren",
    "metadata", "catalogId", "protocolVersion", "instructions", "components", "functions",
)
