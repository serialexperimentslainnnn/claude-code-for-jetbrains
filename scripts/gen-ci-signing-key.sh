#!/usr/bin/env bash
# Generate the CI-only GPG key that signs release artifacts in GitHub Actions.
#
# WHY THIS KEY IS SEPARATE FROM THE MAINTAINER KEY, AND MUST STAY SEPARATE
# ----------------------------------------------------------------------
# The maintainer key lives on a YubiKey. It cannot be exported — that is the point of it — so
# it cannot sign inside a runner. Automating artifact signatures therefore requires a SOFTWARE key whose
# private material sits in a GitHub secret, readable by the job that references it and by anything that
# compromises the runner.
#
# That is a real, accepted trade-off, and the mitigation is that the two keys must never be confusable:
#
#   * the MAINTAINER key signs COMMITS and TAGS. That signature means "a person chose to release this."
#   * this CI key signs RELEASE ARTIFACTS. That signature means "this workflow produced these bytes."
#
# If both signatures looked alike, a leaked CI key would impersonate the maintainer. So this key carries a
# uid that says what it is out loud, and it EXPIRES — a leaked key that expires stops being useful on its
# own, without anyone having to notice the leak first.
#
# This script never touches your real keyring: it works in a throwaway GNUPGHOME under a temp directory,
# emits what you need, and deletes it.
#
#   ./scripts/gen-ci-signing-key.sh                 # print everything (manual setup / rotation)
#   ./scripts/gen-ci-signing-key.sh --outdir DIR    # write private.asc, public.asc, passphrase into DIR
#
# --outdir exists so scripts/bootstrap-ci.sh can pipe the private key straight into `gh secret set`
# without it ever crossing a terminal, a scrollback buffer, or a shell history.
set -euo pipefail

command -v gpg >/dev/null || { echo "gpg is required" >&2; exit 1; }

outdir=""
if [ "${1:-}" = "--outdir" ]; then
  outdir="${2:?--outdir needs a directory}"
  mkdir -p "$outdir"
  chmod 700 "$outdir"
fi

NAME="Claude Code Native CI (release artifacts only — NOT the maintainer key)"
EXPIRY="1y"

# The key MUST carry an email, and it must be the maintainer's verified GitHub address.
#
# The first version of this script set only Name-Real, so the key was generated with no email in its uid
# at all. Everything still worked — artifacts were signed, `gpg --verify` passed — except the one thing
# that is visible to everyone: GitHub never showed the release tag as Verified, and could not, because
# marking a signature Verified requires THREE things to agree (docs: "Associating an email with your GPG
# key"): the tagger's email, an email in a uid of the registered key, and a verified email on the account.
# A key with no email fails the second forever. GitHub's own API reported the key as `emails=` — empty.
#
# Using the maintainer's address here does blunt one edge of the separation this file argues for, so state
# what actually keeps the two keys distinguishable now: the uid NAME says out loud that this is a CI key,
# the key EXPIRES after a year, and it is certified by the hardware key rather than merely asserted. What
# it no longer provides is separation by address — and it never could, because GitHub offers no way to
# verify a tag signed by a key bearing an address that is not yours.
#
# READ from the maintainer key, never written here. The project publishes no contact address anywhere (see
# CHANGELOG 5.0.0), and a committed script is published: hardcoding it would put the address into the
# repository, into every clone, and into the search index — undoing that decision to save one lookup.
# It also cannot drift, since the address that must match is by definition the one on the key.
maintainer_fpr="${CI_KEY_FROM:-$(git config --get user.signingkey || true)}"
[ -n "$maintainer_fpr" ] || {
  echo "error: git config user.signingkey is unset, so the maintainer key is unknown." >&2
  echo "       Set it, or pass the address explicitly:  CI_KEY_EMAIL=you@example.com $0" >&2
  exit 1
}
EMAIL="${CI_KEY_EMAIL:-$(gpg --list-keys --with-colons "$maintainer_fpr" 2>/dev/null \
  | awk -F: '/^uid:/ {print $10; exit}' | sed -n 's/.*<\(.*\)>.*/\1/p')}"
[ -n "$EMAIL" ] || {
  echo "error: could not read an email from the maintainer key $maintainer_fpr." >&2
  echo "       A CI key without an email can never produce a verified tag — that is what this fixes." >&2
  exit 1
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"
export GNUPGHOME="$tmp/gnupg"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"

passphrase=$(gpg --gen-random --armor 2 32)

cat > "$tmp/params" <<EOF
%echo Generating CI signing key…
Key-Type: EDDSA
Key-Curve: ed25519
Key-Usage: sign
Name-Real: $NAME
Name-Email: $EMAIL
Expire-Date: $EXPIRY
Passphrase: $passphrase
%commit
%echo done
EOF

gpg --batch --gen-key "$tmp/params" 2>/dev/null
fpr=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr:/ {print $10; exit}')

gpg --batch --pinentry-mode loopback --passphrase "$passphrase" \
    --armor --export-secret-keys "$fpr" > "$tmp/private.asc"
gpg --armor --export "$fpr" > "$tmp/public.asc"

if [ -n "$outdir" ]; then
  umask 077
  cp "$tmp/private.asc" "$outdir/private.asc"
  cp "$tmp/public.asc"  "$outdir/public.asc"
  printf '%s' "$passphrase" > "$outdir/passphrase"
  printf '%s' "$fpr"        > "$outdir/fingerprint"
  echo "wrote private.asc, public.asc, passphrase, fingerprint to $outdir" >&2
  exit 0
fi

cat <<EOF

================================================================================
CI signing key generated.

  Fingerprint : $fpr
  Expires     : in $EXPIRY (renew or replace before then — see SECURITY.md)
  Identity    : $NAME <$EMAIL>

--------------------------------------------------------------------------------
1) Add TWO secrets to the 'marketplace' environment
   (Settings > Environments > marketplace > Add environment secret).

   They go in the ENVIRONMENT, not in repository secrets: environment secrets do
   not exist for any other job, and this one admits only 'main' and 'v*.*.*'. It
   scopes the key; it does NOT hold the release back — there is no reviewer on it.

   GPG_SIGNING_KEY        <- the block printed below
   GPG_SIGNING_PASSPHRASE <- $passphrase

--------------------------------------------------------------------------------
2) Publish the PUBLIC key so users can verify a release — as part of the trust
   chain, in step 3, never on its own. A leaf with nothing above it asks the
   reader to trust a fingerprint printed in the repository the artifact came
   from, which is not an anchor.

--------------------------------------------------------------------------------
3) Certify it with BOTH hardware CAs, so the CI key is ENDORSED rather than
   merely asserted by a file in the same repo an attacker would edit — and so
   the endorsement can be revoked from hardware if the key ever leaks. Then
   write the chain as ONE file, CAs first and the CI key last:

     gpg --import public.asc                       # PUBLIC half only
     gpg --local-user <ROOT_FPR> --quick-sign-key $fpr
     gpg --local-user <INT_FPR>  --quick-sign-key $fpr
     { gpg --armor --export <ROOT_FPR> <INT_FPR>
       gpg --armor --export $fpr; } > docs/trust-chain.asc

   --quick-sign-key, NEVER --quick-lsign-key: a local certification is stripped
   on export, so the file would carry the CAs and no endorsement — and it looks
   exactly like a correct one until somebody else imports it.

--------------------------------------------------------------------------------
4) Register the PUBLIC key on your GitHub ACCOUNT, or the release tag will never
   show as Verified. This step was missing from earlier versions of this script,
   which is exactly how a release shipped with an unverified tag.

     gh auth refresh -s write:gpg_key    # one-off, interactive
     gh gpg-key add public.asc           # the LEAF alone, not the chain: this
                                         # endpoint takes one armored key, and
                                         # the chain's first block is a CA
     gh api user/gpg_keys --jq '.[]|"\(.key_id) \(([.emails[]?.email]|join(",")))"'

   The last command is the check that matters: if the key lists NO emails, the
   tag cannot be verified no matter what else is correct.

--------------------------------------------------------------------------------
5) Never import the PRIVATE half into your keyring. It belongs in exactly one
   place — the GitHub environment secret. Keeping it out of your keyring is what
   stops it quietly becoming a second maintainer identity.
================================================================================

----- GPG_SIGNING_KEY (paste everything between the markers) -----
EOF
cat "$tmp/private.asc"
cat <<'EOF'
----- end GPG_SIGNING_KEY -----

----- public key: save as public.asc, then certify it into docs/trust-chain.asc -----
EOF
cat "$tmp/public.asc"
echo "----- end public key -----"
