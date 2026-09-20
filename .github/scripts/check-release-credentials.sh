#!/bin/sh
# Every secret a release needs, checked before the build rather than at the step that uses it.
#
# Read from the environment: MAVEN_CENTRAL_USERNAME, MAVEN_CENTRAL_PASSWORD, GPG_KEY_ID,
# GPG_PRIVATE_KEY, GPG_PASSPHRASE -- the five `cd.yml` maps from the `release` environment's
# secrets. Two of these fail in ways worth pre-empting:
#
#  - A missing secret is the empty string, not an error. `signingInMemoryKeyId` empty fails with
#    `The key ID must be in a valid form (eg 00B5050F or 0x00B5050F), given value:` -- a message
#    about the key's *shape* for a secret that was never set, arriving after the full suite has run.
#  - Gradle takes the short 8-hex key id only. Measured on 2026-09-06 against a throwaway key: the
#    8-hex form and *omitting the property entirely* both sign; the 16-hex long id, the 40-hex
#    fingerprint and the empty string all fail the `sign*Publication` tasks.
#    `gpg --list-secret-keys --keyid-format SHORT` prints the form that works.
#
# `GPG_PASSPHRASE` is required deliberately, and this check is stricter than the toolchain: the
# plugin resolves `signingInMemoryKeyPassword` as `.orElse("").get()` (0.37.0 bytecode), so an
# unprotected key signs fine with the property empty or absent. Requiring it encodes a policy
# rather than a technical need -- this repository signs with a passphrase-protected key, so an
# empty `GPG_PASSPHRASE` means a secret nobody set, not a key that needs none. If that key is ever
# replaced with an unprotected one, drop the name from the list below; the failure otherwise reads
# as a missing secret.
#
# A script rather than a `run:` block because `cd.yml` runs only on a release tag and so can never
# be exercised by CI -- this file can be, and is, on every PR, by
# `check-release-credentials.test.sh`.
# `release-dry-run.yml` calls it too, against a key it generated in the job, so the shapes real
# `gpg` output takes are checked against the same patterns a release applies to the stored secrets.
#
# Usage: check-release-credentials.sh   (no arguments; reads the five variables above)
set -eu

missing=
for name in MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD GPG_KEY_ID GPG_PRIVATE_KEY GPG_PASSPHRASE; do
  # `${name:-}` rather than `$name`: under `set -u` a variable that is unset -- rather than set to
  # the empty string, which is what an absent GitHub secret becomes -- would kill the script with
  # its own error instead of being named here.
  eval "value=\${$name:-}"
  [ -n "$value" ] || missing="$missing $name"
done
if [ -n "$missing" ]; then
  echo "::error::Not set (cd.yml reads these from the secrets of the 'release' environment):$missing" >&2
  exit 1
fi

# Exact, so a key id carrying a stray newline or space is refused rather than passed on to Gradle
# with the same bytes. `0x` and `0X` both, because `gpg` prints neither and a hand-typed prefix
# comes in either case.
case "$GPG_KEY_ID" in
  [0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f] | \
  0[xX][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f] ) ;;
  * )
    echo "::error::GPG_KEY_ID must be the short 8-hex key id (gpg --list-secret-keys --keyid-format SHORT), not a long id or a fingerprint." >&2
    exit 1
    ;;
esac

# Header *and* footer: a value holding only the first line is non-empty and starts correctly, and
# would fail only in the `sign*Publication` tasks after the full build. Anything around the block
# -- a trailing newline, CRLF line ends, a leading blank line, a second block concatenated after
# the first -- is left to `gpg`, which reads past all of them.
case "$GPG_PRIVATE_KEY" in
  *"BEGIN PGP PRIVATE KEY BLOCK"*"END PGP PRIVATE KEY BLOCK"* ) ;;
  * )
    echo "::error::GPG_PRIVATE_KEY must be the ASCII-armored private key, header and footer included (gpg --export-secret-keys --armor <SHORT_KEY_ID>)." >&2
    exit 1
    ;;
esac

echo "Every release credential is present and in the shape the build accepts."
