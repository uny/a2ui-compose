#!/bin/sh
# Every module that carries an `api/` dump must have had its `checkKotlinAbi` task *execute* in
# the Gradle run whose log this reads.
#
# On Kotlin 2.3 the check is opt-in: a bare `abiValidation {}` block leaves `checkKotlinAbi`
# SKIPPED, and the build exits 0 having compared nothing against `api/`. The workflows would stay
# green through an edit that dropped `enabled.set(true)` from a module, or a Kotlin release that
# changed the DSL default again -- the exit code is the same either way (#90, measured on #89).
# Gradle offers no "this task ran" assertion, so this reads the task lines of a `--console=plain`
# log, which prints one `> Task :<path>` header per task in the graph with its outcome after it.
#
# What counts as executed: a bare header, `UP-TO-DATE` and `FROM-CACHE` -- the last two mean an
# identical run already compared the dump, which is the same finding. `SKIPPED` and `NO-SOURCE`
# do not: nothing was compared. A module with no header at all is a failure too, so a module the
# build stopped reaching is reported rather than passed over -- and the module list is derived
# from the `api/` directories in the tree, not written here, so a published module added with a
# dump is covered by the dump's existence.
#
# Called from `build.yml`, `cd.yml` and `release-dry-run.yml` after their Gradle build -- one
# file, not three copies -- and exercised in both directions by `check-abi-ran.test.sh`.
#
# Usage: check-abi-ran.sh <gradle log> <repository root>
set -eu

log="${1:?usage: $0 <gradle log> <repository root>}"
root="${2:?usage: $0 <gradle log> <repository root>}"

# Absence checks first, as in `check-published-signed.sh`: everything below looks for lines in a
# file, and an empty or missing log would pass a grep for "no SKIPPED" without having looked at a
# build. The log must also be the one the Gradle run wrote, not a redirect that lost the task
# headers -- `--console=plain` is what prints them, and a log without a single one is not that.
if [ ! -s "$log" ]; then
  echo "::error::no Gradle log at $log -- the build step did not tee its output there, so there is nothing to inspect" >&2
  exit 1
fi
if ! grep -q '^> Task :' "$log"; then
  echo "::error::$log carries no '> Task :' lines; the Gradle run must pass --console=plain for its task outcomes to be readable" >&2
  exit 1
fi

# The modules whose dumps exist. `*/api` and not a list: the dump is the thing the check compares
# against, so a module that has one is a module whose check must run.
modules=$(cd "$root" && for dir in */api; do [ -d "$dir" ] && echo "${dir%/api}"; done)
if [ -z "$modules" ]; then
  echo "::error::no <module>/api directory under $root -- the dumps this check guards are not there" >&2
  exit 1
fi

failures=0
for module in $modules; do
  # Anchored on the header, so the deprecation notice that names the task ("use
  # :x:checkKotlinAbi instead") is not mistaken for it. The outcome is whatever follows the
  # task path on that line: nothing, or one word.
  line=$(grep -E "^> Task :$module:checkKotlinAbi( |$)" "$log" | tail -n 1)
  if [ -z "$line" ]; then
    echo "::error:::$module:checkKotlinAbi is not in the log; the build never reached the module's ABI check" >&2
    failures=$((failures + 1))
    continue
  fi
  outcome=${line#"> Task :$module:checkKotlinAbi"}
  outcome=${outcome# }
  case "$outcome" in
    "" | UP-TO-DATE | FROM-CACHE)
      echo ":$module:checkKotlinAbi ${outcome:-executed}"
      ;;
    *)
      echo "::error:::$module:checkKotlinAbi $outcome -- the dump in $module/api was not compared; is abiValidation { enabled.set(true) } still in $module/build.gradle.kts?" >&2
      failures=$((failures + 1))
      ;;
  esac
done

[ "$failures" -eq 0 ] || exit 1
echo "The ABI check executed for every module with a dump."
