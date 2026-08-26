package dev.ynagai.a2ui.core.surface

import kotlinx.serialization.json.JsonElement

/**
 * Where a relative data binding resolves from, and what `@index` returns.
 *
 * A component renders in the **root scope** by default. A container whose children are a
 * [dev.ynagai.a2ui.core.protocol.ChildList.Template] instantiates its template once per item of
 * the bound array, and each instance renders in a **collection scope** whose base is that item.
 *
 * The chain is kept rather than flattened to a single base pointer because `@index` reads the
 * innermost iteration index, which a flattened base cannot recover: `/rows/2/cells/0` does not
 * say whether the enclosing loop is over `cells` or `rows`.
 */
public sealed interface EvaluationScope {
    /** The pointer that a relative binding in this scope is measured from. */
    public val base: JsonPointer

    /**
     * The 0-based index of the item being iterated, or null outside a list template.
     *
     * Null is what makes `@index` an error rather than a default: the specification says a call
     * outside a collection scope "MUST" be treated as an error or evaluated as invalid, so the
     * evaluator needs to tell "not iterating" from "iterating at 0".
     */
    public val index: Int? get() = null

    /** The scope every surface starts in, where relative and absolute pointers coincide. */
    public data object Root : EvaluationScope {
        override val base: JsonPointer get() = JsonPointer.ROOT
    }

    /**
     * One item of a list template, nested inside [parent].
     *
     * [base] is the item's own pointer — the template's bound path extended by [index] — so a
     * relative binding of `name` inside a template over `/employees` reads
     * `/employees/0/name` for the first instance.
     */
    public data class Collection(
        public val parent: EvaluationScope,
        public val path: JsonPointer,
        override val index: Int,
    ) : EvaluationScope {
        override val base: JsonPointer = parent.base.resolve(path).child(index.toString())
    }
}

/** The scope for item [index] of a template bound to [path], nested inside this scope. */
public fun EvaluationScope.iterate(path: JsonPointer, index: Int): EvaluationScope.Collection =
    EvaluationScope.Collection(this, path, index)

/**
 * [pointer] rebased on this scope: absolute pointers unchanged, relative ones measured from
 * [EvaluationScope.base].
 *
 * A relative pointer in [EvaluationScope.Root] therefore resolves from the root of the data model.
 * The specification only defines the relative form inside a template, so this is a choice: it
 * makes the two forms coincide where no iteration is in progress, rather than making every
 * relative binding outside a template resolve to nothing.
 */
public fun EvaluationScope.rebase(pointer: JsonPointer): JsonPointer = base.resolve(pointer)

/** The value [pointer] addresses in [dataModel] once rebased on this scope, or null when absent. */
public fun EvaluationScope.resolve(dataModel: JsonElement, pointer: JsonPointer): JsonElement? =
    dataModel.resolve(rebase(pointer))

/**
 * The index `@index` reads here, or null when this scope is not inside a list template.
 *
 * Components nested inside a template instance render in that instance's scope rather than in one
 * of their own, so the innermost iteration is simply this scope's own [EvaluationScope.index].
 */
public fun EvaluationScope.currentIndex(): Int? = index
