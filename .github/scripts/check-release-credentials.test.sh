#!/bin/sh
# Both directions of `check-release-credentials.sh`: every shape it must refuse, and every shape
# that looks wrong but is not.
#
# Run from anywhere: `sh .github/scripts/check-release-credentials.test.sh`. Runs every case and
# exits non-zero if any did not behave, naming each.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
script="$here/check-release-credentials.sh"

# A complete, well-formed set. Every case below starts from this and changes one thing.
good_key_id=59cace12
good_key=$(printf '%s\n' \
  '-----BEGIN PGP PRIVATE KEY BLOCK-----' '' 'lQdGBGh...not a real key...' '=abcd' \
  '-----END PGP PRIVATE KEY BLOCK-----')

failures=0
expect() {
  # expect <case name> <expected exit> [NAME=value ...]
  #
  # Runs the script in a clean environment holding the five good values and then the given ones,
  # so a case reads as its delta from a complete set, a later assignment overriding an earlier one.
  # `env -i`, so a case cannot pass on a value leaked from the caller's shell.
  name="$1" want="$2"
  shift 2
  set +e
  output=$(env -i PATH="$PATH" \
    MAVEN_CENTRAL_USERNAME=user MAVEN_CENTRAL_PASSWORD=pass \
    GPG_KEY_ID="$good_key_id" GPG_PRIVATE_KEY="$good_key" GPG_PASSPHRASE=secret \
    "$@" sh "$script" 2>&1)
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

expect "five present, 8-hex id, armored key" 0

# Each of the five, empty -- the shape an absent GitHub secret takes.
expect "MAVEN_CENTRAL_USERNAME empty" 1 MAVEN_CENTRAL_USERNAME=
expect "MAVEN_CENTRAL_PASSWORD empty" 1 MAVEN_CENTRAL_PASSWORD=
expect "GPG_KEY_ID empty" 1 GPG_KEY_ID=
expect "GPG_PRIVATE_KEY empty" 1 GPG_PRIVATE_KEY=
expect "GPG_PASSPHRASE empty" 1 GPG_PASSPHRASE=

# Unset entirely, which a caller outside GitHub Actions can do and `set -u` would otherwise turn
# into a different failure than the one being tested. `expect` always sets all five, so this one
# case runs the script directly. The message is checked as well as the exit status: under bash
# 3.2 the shell's own `unbound variable` abort also exits 1, so the status alone would stay green
# with `${name:-}` regressed to `$name`.
set +e
output=$(env -i PATH="$PATH" MAVEN_CENTRAL_USERNAME=user MAVEN_CENTRAL_PASSWORD=pass \
  GPG_KEY_ID="$good_key_id" GPG_PRIVATE_KEY="$good_key" sh "$script" 2>&1)
got=$?
set -e
case "$got:$output" in
  1:*"Not set"*" GPG_PASSPHRASE"* ) echo "ok   GPG_PASSPHRASE unset" ;;
  * )
    echo "FAIL GPG_PASSPHRASE unset: expected exit 1 naming the secret, got exit $got"
    echo "$output" | sed 's/^/    /'
    failures=$((failures + 1))
    ;;
esac

# A missing secret must be named, or the failure is a hunt through five settings. All five at
# once, so that each name's place in the script's list is what this asserts: with one dropped, the
# `GPG_KEY_ID empty` and `GPG_PRIVATE_KEY empty` cases above still exit 1, through the shape check
# that follows -- the very message the list exists to pre-empt.
set +e
named=$(env -i PATH="$PATH" MAVEN_CENTRAL_USERNAME= MAVEN_CENTRAL_PASSWORD= \
  GPG_KEY_ID= GPG_PRIVATE_KEY= GPG_PASSPHRASE= sh "$script" 2>&1 \
  | grep -c 'MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD GPG_KEY_ID GPG_PRIVATE_KEY GPG_PASSPHRASE$')
set -e
if [ "$named" -eq 1 ]; then echo "ok   every missing secret is named"; else
  echo "FAIL the missing secrets are not all named"; failures=$((failures + 1)); fi

# The key id: both prefixes pass; the two longer forms `gpg` also prints do not.
expect "0x prefix" 0 GPG_KEY_ID=0x59cace12
expect "0X prefix, upper-case hex" 0 GPG_KEY_ID=0X59CACE12
expect "16-hex long id" 1 GPG_KEY_ID=1234567859cace12
expect "40-hex fingerprint" 1 GPG_KEY_ID=0123456789abcdef0123456789abcdef59cace12
expect "non-hex" 1 GPG_KEY_ID=59cacg12
expect "7 hex" 1 GPG_KEY_ID=59cace1
# `$(...)` strips a trailing newline, so the value is built with a sentinel and the sentinel cut.
id_with_newline=$(printf '59cace12\nx')
id_with_newline=${id_with_newline%x}
expect "key id with a trailing newline" 1 GPG_KEY_ID="$id_with_newline"
expect "key id with a trailing space" 1 GPG_KEY_ID="59cace12 "

# The key: the footer is required, and a partial paste is the realistic way to lose it.
expect "key not armored" 1 GPG_PRIVATE_KEY="lQdGBGh...not a real key..."
expect "key header only, footer missing" 1 \
  GPG_PRIVATE_KEY="$(printf '%s\n' '-----BEGIN PGP PRIVATE KEY BLOCK-----' '' 'lQdGBGh...')"

# False rejections: everything `gpg` reads past must pass here too. The trailing newline and the
# CR are built by hand rather than through `$(...)` or `sed`: the former strips trailing newlines
# (the "five present" case again, wearing a different name) and `\r` in a `sed` replacement is a
# carriage return on GNU sed and a literal `r` on some BSD ones.
key_with_newline=$(printf '%s\nx' "$good_key")
key_with_newline=${key_with_newline%x}
expect "key with a trailing newline" 0 GPG_PRIVATE_KEY="$key_with_newline"
expect "key with trailing whitespace" 0 GPG_PRIVATE_KEY="$good_key   "
cr=$(printf '\rx')
cr=${cr%x}
expect "key with CRLF line ends" 0 GPG_PRIVATE_KEY="$(printf '%s' "$good_key" | sed "s/\$/$cr/")"
expect "key with a leading blank line" 0 GPG_PRIVATE_KEY="$(printf '\n%s' "$good_key")"
expect "two armor blocks concatenated" 0 GPG_PRIVATE_KEY="$(printf '%s\n%s' "$good_key" "$good_key")"

[ "$failures" -eq 0 ] || { echo "$failures case(s) failed"; exit 1; }
echo "all cases passed"
