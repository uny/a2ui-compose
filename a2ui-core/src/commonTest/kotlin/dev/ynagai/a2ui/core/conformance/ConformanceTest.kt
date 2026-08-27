package dev.ynagai.a2ui.core.conformance

import dev.ynagai.a2ui.core.validation.ValidationLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The A2UI v1.0 conformance suite, run against this implementation.
 *
 * This is the first half of G1's completion condition, and it is deliberately not written as one
 * test per assertion: the cases are data, they come from a file that a newer specification
 * revision replaces wholesale, and a hand-written test per case would be a second place to keep
 * the list. The failure message names each case the way `run_tests.py` does — file and
 * zero-based index — so a failure here can be looked up there.
 *
 * A case is *not* run through the model's own decoder first. The decoder is strict and refuses
 * much of what these payloads carry, so a rejection there would be indistinguishable from a
 * rejection by the catalog — and one of the two is what is being measured.
 */
class ConformanceTest {
    @Test
    fun the_suite_is_the_size_the_specification_says() {
        // A guard on the harness rather than on the implementation. Reading the wrong directory,
        // or a codegen that silently dropped a file, would otherwise show up as a green run over
        // fewer cases than the specification has.
        assertEquals(153, CONFORMANCE_CASES.size, "the suite should hold 153 assertions")
        assertEquals(14, CONFORMANCE_CASES.map { it.file }.distinct().size)
    }

    @Test
    fun every_assertion_holds() {
        val failures = CONFORMANCE_CASES.mapNotNull { case ->
            val result = verdict(case)
            when {
                result.isValid == case.expectedValid -> null
                case.expectedValid ->
                    "$case\n    expected accepted, refused: ${result.violations.take(3)}"
                else -> "$case\n    expected refused, accepted"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${CONFORMANCE_CASES.size} assertions failed:\n" +
                failures.joinToString("\n"),
        )
    }

    @Test
    fun no_case_meets_a_keyword_this_implementation_skips() {
        // The keyword subset is partial by design, and an unapplied keyword shows up as acceptance
        // rather than as failure -- so a green suite above proves nothing on its own without this.
        val skipped = CONFORMANCE_CASES
            .flatMap { case -> verdict(case).unsupportedKeywords.map { case.name to it } }
        assertEquals(emptyList(), skipped)
    }

    @Test
    fun no_case_exhausts_the_bounds() {
        // A truncated run answers about the budget rather than about the payload. If one of these
        // ever needs a larger bound, that is a finding about the bound and not a number to raise.
        val truncated = CONFORMANCE_CASES.filter { verdict(it).truncated }.map { it.name }
        assertEquals(emptyList(), truncated)
    }

    @Test
    fun both_catalogs_the_suites_name_are_actually_exercised() {
        // Three suites bind the placeholder to the testing catalog. Running everything against the
        // basic one gets ten assertions wrong, and this is what keeps that from being reintroduced
        // quietly -- the harness would still be green on the other 143.
        val catalogs = CONFORMANCE_CASES.map { it.suite.catalogId }.distinct()
        assertEquals(2, catalogs.size, "expected both catalogs to be in play, got $catalogs")
        assertTrue(CONFORMANCE_CASES.any { it.suite.catalogId == TESTING.catalogId })
    }

    @Test
    fun all_three_target_schemas_are_exercised() {
        val targets = CONFORMANCE_CASES.map { it.suite.target }.distinct()
        assertEquals(3, targets.size, "expected all three documents to be in play, got $targets")
    }
}

/**
 * What the suite actually costs, measured rather than assumed.
 *
 * A bound picked to make the tests pass is a bound nobody can raise or lower with confidence
 * later. These record the real figures, so a change to either the schemas or the evaluator that
 * moves them shows up as a number rather than as a green run.
 */
class ConformanceCostTest {
    @Test
    fun records_the_depth_the_deepest_case_needs() {
        val needed = smallest { depth ->
            val limits = ValidationLimits(maxDepth = depth)
            val validator = validatorAt(limits)
            CONFORMANCE_CASES.none { verdict(it, limits, validator).truncated }
        }
        // Schema depth, not instance depth. One level of nested function call costs roughly eight
        // frames -- `$ref` to `FunctionCall`, its `oneOf`, `anyFunction`, its `oneOf`, the
        // function, its `allOf`, its `properties`, then the argument -- so a payload nesting
        // `and(or(not(...)))` a few deep reaches into the hundreds. `checkable_components` #8 is
        // that payload, and it is a case the specification expects a renderer to accept.
        assertTrue(needed <= ValidationLimits.DEFAULT.maxDepth, "the suite needs $needed")
        assertTrue(
            ValidationLimits.DEFAULT.maxDepth >= needed * 2,
            "the default leaves less than a factor of two over the $needed the suite needs",
        )
    }

    @Test
    fun records_the_step_budget_the_suite_needs() {
        val needed = smallest(from = 1_000, factor = 2) { steps ->
            val limits = ValidationLimits(maxSteps = steps)
            val validator = validatorAt(limits)
            CONFORMANCE_CASES.none { verdict(it, limits, validator).truncated }
        }
        assertTrue(needed <= ValidationLimits.DEFAULT.maxSteps, "the suite needs $needed")
    }
}

/** The smallest value in a doubling sequence for which [holds] is true. */
private fun smallest(from: Int = 8, factor: Int = 2, holds: (Int) -> Boolean): Int {
    var value = from
    while (value < 1 shl 24) {
        if (holds(value)) return value
        value *= factor
    }
    error("no value in range satisfied the predicate")
}
