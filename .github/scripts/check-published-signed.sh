#!/bin/sh
# Every file a publish wrote under a Maven repository must have a detached `.asc` beside it.
#
# Central requires a signature for every deployed file, and a local repository takes unsigned
# artifacts without complaint. A publish fails only when a publication *declares* an `.asc` it
# cannot produce -- so if `signAllPublications()` were removed or made conditional, nothing would
# be declared, the publish would succeed, the consumer gate would pass (resolution does not read
# signatures), and the break would surface in Central's own validation after the full build and a
# portal round-trip. This is what stops it before the upload.
#
# Called from `cd.yml` and `release-dry-run.yml` -- one file, not two copies -- and exercised in
# both directions by `check-published-signed.test.sh`.
#
# Usage: check-published-signed.sh <repository directory>
set -eu

repo="${1:?usage: $0 <repository directory>}"

# The POM count comes first because everything after it looks for an *absence*, and a check like
# that reports success just as cheerfully when it is looking at nothing. `-o pipefail` is not
# portable to every `sh`, so inside `find … | wc -l` the status is `wc`'s and find's exit on a
# missing directory is discarded. Both ways of looking at nothing -- an absent tree and a present
# but empty one -- are caught by the count.
published=$(find "$repo" -name '*.pom' 2>/dev/null | wc -l | tr -d ' ')
if [ "$published" -eq 0 ]; then
  echo "::error::no POMs under $repo — the publish did not land there, so the check below would pass without inspecting anything" >&2
  exit 1
fi
echo "Inspecting $published published POMs."

# Checksums are Central's to compute and `maven-metadata*` is the repository's index, not a
# deployed file; everything else -- jar, sources, javadoc, `.module`, `.pom`, klib, aar -- needs
# its sibling.
unsigned=$(find "$repo" -type f \
  ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
  ! -name 'maven-metadata*' \
  -exec sh -c '[ -f "$1.asc" ] || echo "$1"' _ {} \;)
if [ -n "$unsigned" ]; then
  echo "::error::published files carry no detached signature; Maven Central requires one per deployed file" >&2
  echo "$unsigned"
  exit 1
fi
echo "Every published file carries a .asc."
