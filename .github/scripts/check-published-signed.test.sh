#!/bin/sh
# Both directions of `check-published-signed.sh`, against a repository laid out by hand.
#
# Run from anywhere: `sh .github/scripts/check-published-signed.test.sh`. Exits non-zero on the
# first case that does not behave, naming it.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
script="$here/check-published-signed.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
expect() {
  # expect <case name> <expected exit> <repository directory>
  name="$1" want="$2" repo="$3"
  set +e
  output=$("$script" "$repo" 2>&1)
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

# A publication as `publishToMavenLocal` lays it out: the five real artifacts, each with its
# `.asc`, plus the index file that must be ignored.
lay_out() {
  dir="$1/dev/ynagai/a2ui/a2ui-core/0.1.0"
  mkdir -p "$dir"
  for file in a2ui-core-0.1.0.jar a2ui-core-0.1.0-sources.jar a2ui-core-0.1.0-javadoc.jar \
      a2ui-core-0.1.0.module a2ui-core-0.1.0.pom; do
    : > "$dir/$file"
    : > "$dir/$file.asc"
  done
  : > "$1/dev/ynagai/a2ui/a2ui-core/maven-metadata-local.xml"
}

lay_out "$work/signed"
expect "every file signed" 0 "$work/signed"

lay_out "$work/one-missing"
rm "$work/one-missing/dev/ynagai/a2ui/a2ui-core/0.1.0/a2ui-core-0.1.0.module.asc"
expect "one .asc removed" 1 "$work/one-missing"

# The unsigned file must be named, or the failure is a hunt.
set +e
named=$("$script" "$work/one-missing" 2>&1 | grep -c 'a2ui-core-0.1.0.module$')
set -e
if [ "$named" -eq 1 ]; then echo "ok   the missing signature is named"; else
  echo "FAIL the missing signature is not named"; failures=$((failures + 1)); fi

expect "repository directory absent" 1 "$work/nowhere"

mkdir -p "$work/empty"
expect "repository present but empty" 1 "$work/empty"

# Checksums and the index are not deployed files, so their lack of an `.asc` is not a finding.
lay_out "$work/with-checksums"
: > "$work/with-checksums/dev/ynagai/a2ui/a2ui-core/0.1.0/a2ui-core-0.1.0.jar.sha1"
: > "$work/with-checksums/dev/ynagai/a2ui/a2ui-core/0.1.0/a2ui-core-0.1.0.jar.md5"
expect "checksums and the index need no signature" 0 "$work/with-checksums"

[ "$failures" -eq 0 ] || { echo "$failures case(s) failed"; exit 1; }
echo "all cases passed"
