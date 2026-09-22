#!/bin/sh
# Both directions of `check-abi-ran.sh`, against Gradle logs written by hand.
#
# Run from anywhere: `sh .github/scripts/check-abi-ran.test.sh`. Runs every case and exits
# non-zero if any did not behave, naming each.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
script="$here/check-abi-ran.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
expect() {
  # expect <case name> <expected exit> <log> <repository root>
  name="$1" want="$2" log="$3" root="$4"
  set +e
  output=$("$script" "$log" "$root" 2>&1)
  got=$?
  set -e
  if [ "$got" -ne "$want" ]; then
    echo "FAIL $name: expected exit $want, got $got"
    echo "$output" | sed 's/^/    /'
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

# A tree with the two dumps the check is derived from, and a sibling with none -- the gallery,
# which is not published and whose check the script must not demand.
root="$work/root"
mkdir -p "$root/a2ui-core/api" "$root/a2ui-material3/api" "$root/a2ui-gallery/src"

# A `--console=plain` log with the shape a real run has around the lines that matter: the task
# headers of neighbouring tasks, and the summary. The outcome words are Gradle's own.
plain_log() {
  # plain_log <core outcome> <material3 outcome>
  cat <<EOF
> Task :a2ui-core:compileKotlinJvm
> Task :a2ui-core:internalDumpKotlinAbi
> Task :a2ui-core:checkKotlinAbi${1:+ $1}
> Task :a2ui-gallery:checkKotlinAbi SKIPPED
> Task :a2ui-material3:internalDumpKotlinAbi
> Task :a2ui-material3:checkKotlinAbi${2:+ $2}
> Task :buildSrc:test

BUILD SUCCESSFUL in 4m 12s
EOF
}

plain_log "" "" > "$work/executed.log"
expect "every dump's check executed" 0 "$work/executed.log" "$root"

plain_log "UP-TO-DATE" "FROM-CACHE" > "$work/cached.log"
expect "UP-TO-DATE and FROM-CACHE count as executed" 0 "$work/cached.log" "$root"

# The finding #90 opens with, verbatim from the bare-`abiValidation {}` run on Kotlin 2.3.21:
# the module's check SKIPPED, the deprecated aggregator after it green, exit 0.
{
  plain_log "" "SKIPPED"
  echo "> Task :a2ui-material3:checkLegacyAbi"
  echo "Task :a2ui-material3:checkLegacyAbi is deprecated, use :a2ui-material3:checkKotlinAbi instead"
} > "$work/skipped.log"
expect "one module's check SKIPPED" 1 "$work/skipped.log" "$root"

# The skipped module must be named, or the failure is a hunt.
set +e
named=$("$script" "$work/skipped.log" "$root" 2>&1 | grep -c ':a2ui-material3:checkKotlinAbi SKIPPED')
set -e
if [ "$named" -eq 1 ]; then echo "ok   the skipped module is named"; else
  echo "FAIL the skipped module is not named"; failures=$((failures + 1)); fi

# The deprecation notice mentions the task by name; it is not a header and must not count as one.
{
  plain_log "" "" | grep -v ':a2ui-material3:checkKotlinAbi'
  echo "Task :a2ui-material3:checkLegacyAbi is deprecated, use :a2ui-material3:checkKotlinAbi instead"
} > "$work/notice-only.log"
expect "a deprecation notice is not a task header" 1 "$work/notice-only.log" "$root"

plain_log "NO-SOURCE" "" > "$work/no-source.log"
expect "NO-SOURCE is not executed" 1 "$work/no-source.log" "$root"

plain_log "" "" | grep -v ':a2ui-core:checkKotlinAbi' > "$work/missing.log"
expect "a dump whose check is absent from the log" 1 "$work/missing.log" "$root"

: > "$work/empty.log"
expect "log empty" 1 "$work/empty.log" "$root"

expect "log absent" 1 "$work/nowhere.log" "$root"

# A log the rich console wrote has no task headers to read; the script must say so rather than
# report nothing skipped.
printf 'BUILD SUCCESSFUL in 4m 12s\n' > "$work/rich.log"
expect "a log without task headers" 1 "$work/rich.log" "$root"

mkdir -p "$work/no-dumps/a2ui-gallery/src"
expect "a tree with no api/ directory" 1 "$work/executed.log" "$work/no-dumps"

[ "$failures" -eq 0 ] || { echo "$failures case(s) failed"; exit 1; }
echo "all cases passed"
