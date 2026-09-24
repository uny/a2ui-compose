#!/bin/sh
# Every POM a publish wrote under a Maven repository must carry what Central requires of it.
#
# Central refuses a deployment whose POM lacks a project name, description, URL, licence,
# developer or SCM URL -- and it says so only in its own validation, after the full build and a
# portal round-trip. A local repository takes such a POM without complaint, and nothing a consumer
# resolves reads those fields, so the consumer gate passes too. 0.2.0 went up that way: its fourth
# module had no `gradle.properties`, so no `POM_NAME` or `POM_DESCRIPTION`, and Central answered
# "Project name is missing". This is what stops it before the upload.
#
# Called from `cd.yml` and `release-dry-run.yml`, and exercised in both directions by
# `check-published-poms.test.sh`.
#
# Usage: check-published-poms.sh <repository directory>
set -eu

repo="${1:?usage: $0 <repository directory>}"

# As in `check-published-signed.sh`: the count comes first, because a check for absences passes
# just as cheerfully when it is looking at nothing.
poms=$(find "$repo" -name '*.pom' 2>/dev/null | sort)
if [ -z "$poms" ]; then
  echo "::error::no POMs under $repo — the publish did not land there, so the check below would pass without inspecting anything" >&2
  exit 1
fi
echo "Inspecting $(echo "$poms" | wc -l | tr -d ' ') published POMs."

# A project's own `<name>`, `<description>` and `<url>` are the ones outside the blocks that carry
# names and URLs of their own -- a licence's name is not the project's. Gradle writes one element
# per line, so dropping those blocks line by line leaves the project's. Each field must be there
# and non-empty; an element with nothing in it is a name, not a value.
missing=""
for pom in $poms; do
  own=$(awk '
    /<(licenses|developers|scm|dependencies|dependencyManagement|organization|parent)>/ { skip++ }
    !skip { print }
    /<\/(licenses|developers|scm|dependencies|dependencyManagement|organization|parent)>/ { skip-- }
  ' "$pom")
  for field in name description url; do
    echo "$own" | grep -q "<$field>[^<[:space:]][^<]*</$field>" || missing="$missing
$pom: <$field>"
  done
  grep -q '<license>' "$pom" || missing="$missing
$pom: <licenses>"
  grep -q '<developer>' "$pom" || missing="$missing
$pom: <developers>"
  awk '/<scm>/,/<\/scm>/' "$pom" | grep -q '<url>[^<[:space:]]' || missing="$missing
$pom: <scm><url>"
done
if [ -n "$missing" ]; then
  echo "::error::published POMs lack fields Maven Central requires" >&2
  echo "$missing" | sed '/^$/d'
  exit 1
fi
echo "Every published POM carries the fields Central requires."
