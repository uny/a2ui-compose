#!/bin/sh
# Both directions of `check-published-poms.sh`, against POMs written by hand.
#
# Run from anywhere: `sh .github/scripts/check-published-poms.test.sh`. Runs every case and exits
# non-zero if any did not behave, naming each.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
script="$here/check-published-poms.sh"
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

# A POM in the shape Gradle writes: one element per line, the licence and the developer carrying a
# `<name>` and `<url>` of their own.
pom() {
  # pom <directory> <artifact> [element to drop]
  dir="$1/dev/ynagai/a2ui/$2/0.2.0"
  mkdir -p "$dir"
  cat > "$dir/$2-0.2.0.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.ynagai.a2ui</groupId>
  <artifactId>$2</artifactId>
  <version>0.2.0</version>
  <name>A2UI Thing</name>
  <description>What it is.</description>
  <url>https://github.com/uny/a2ui-compose</url>
  <licenses>
    <license>
      <name>The Apache Software License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>uny</id>
      <name>Yuki Nagai</name>
      <url>https://github.com/uny</url>
    </developer>
  </developers>
  <scm>
    <connection>scm:git:git://github.com/uny/a2ui-compose.git</connection>
    <url>https://github.com/uny/a2ui-compose</url>
  </scm>
</project>
POM
  [ $# -lt 3 ] || sed -i.bak "/^  <$3>.*<\/$3>$/d" "$dir/$2-0.2.0.pom"
}

pom "$work/complete" a2ui-core
pom "$work/complete" a2ui-material3-markdown
expect "every POM complete" 0 "$work/complete"

# 0.2.0's own failure: a module with no POM_NAME or POM_DESCRIPTION. Its licence and developer
# still carry a `<name>`, which must not stand in for the project's.
pom "$work/no-name" a2ui-core
pom "$work/no-name" a2ui-material3-markdown name
expect "project name missing, licence name present" 1 "$work/no-name"

pom "$work/no-description" a2ui-material3-markdown description
expect "description missing" 1 "$work/no-description"

pom "$work/no-url" a2ui-material3-markdown url
expect "project url missing, scm url present" 1 "$work/no-url"

pom "$work/empty-name" a2ui-core
sed -i.bak 's|<name>A2UI Thing</name>|<name></name>|' "$work/empty-name/dev/ynagai/a2ui/a2ui-core/0.2.0/a2ui-core-0.2.0.pom"
expect "name present but empty" 1 "$work/empty-name"

# The failing module must be named, or the failure is a hunt.
set +e
named=$("$script" "$work/no-name" 2>&1 | grep -c 'a2ui-material3-markdown-0.2.0.pom: <name>')
set -e
if [ "$named" -eq 1 ]; then echo "ok   the POM missing its name is named"; else
  echo "FAIL the POM missing its name is not named"; failures=$((failures + 1)); fi

expect "repository directory absent" 1 "$work/nowhere"

mkdir -p "$work/empty"
expect "repository present but empty" 1 "$work/empty"

[ "$failures" -eq 0 ] || { echo "$failures case(s) failed"; exit 1; }
echo "all cases passed"
