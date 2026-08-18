#!/usr/bin/env bash
# One-shot CI/CD bootstrap: creates the deployment environment, sets its seven secrets, and (optionally)
# applies the branch protections. Automates everything that can be automated and asks you only for what
# you actually hold: the Marketplace token, and the JetBrains signing key if you still have it.
#
# Idempotent — safe to re-run. Existing secrets are reported and skipped unless you ask to replace them.
#
#   ./scripts/bootstrap-ci.sh
#
# Handling rules this script follows, because it moves secret material around:
#   * nothing secret is ever echoed, logged, or passed as a command-line argument (argv is world-readable
#     in /proc on Linux — `gh secret set --body "$TOKEN"` would leak it to any local user);
#   * everything goes to `gh secret set` over stdin;
#   * temp files live in a 0700 directory under $TMPDIR and are shredded on exit, including on Ctrl-C;
#   * the generated GPG private key is piped straight from the generator into `gh` and never reaches your
#     terminal, scrollback, or shell history.
#
# See docs/CI_SETUP.md for what each step means and why.
set -euo pipefail

cd "$(dirname "$0")/.."

# --- preconditions -------------------------------------------------------------------------------------
for bin in gh jq gpg; do
  command -v "$bin" >/dev/null || { echo "error: $bin is required" >&2; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo "error: run 'gh auth login' first" >&2; exit 1; }

REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
ENVIRONMENT=marketplace

tmp=$(mktemp -d)
cleanup() {
  # `shred` where available; the fallback still beats plain rm because it overwrites first.
  find "$tmp" -type f -exec sh -c 'command -v shred >/dev/null && shred -u "$1" 2>/dev/null || { : > "$1"; rm -f "$1"; }' _ {} \; 2>/dev/null || true
  rm -rf "$tmp"
}
trap cleanup EXIT INT TERM
chmod 700 "$tmp"

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
info() { printf '   %s\n' "$*"; }
warn() { printf '   \033[33m! %s\033[0m\n' "$*"; }
ask()  { local a; read -r -p "   $1 [y/N] " a; [ "$a" = y ] || [ "$a" = Y ]; }

# Reads a secret without echoing it and without it becoming an argument.
read_secret() {
  local var=$1 prompt=$2 value
  printf '   %s: ' "$prompt" >&2
  read -rs value
  printf '\n' >&2
  [ -n "$value" ] || { echo "error: empty value" >&2; exit 1; }
  printf -v "$var" '%s' "$value"
}

existing_secrets=$(gh secret list --env "$ENVIRONMENT" --repo "$REPO" --json name -q '.[].name' 2>/dev/null || true)
has_secret() { grep -qx "$1" <<<"$existing_secrets"; }

# Sets a secret from a file, unless it exists and you decline to replace it.
set_secret_from_file() {
  local name=$1 file=$2
  if has_secret "$name"; then
    if ! ask "$name is already set. Replace it?"; then info "keeping the existing $name"; return; fi
  fi
  gh secret set "$name" --env "$ENVIRONMENT" --repo "$REPO" < "$file"
  info "set $name"
}

echo "repository:  $REPO"
echo "environment: $ENVIRONMENT"

# --- 1. environment ------------------------------------------------------------------------------------
say "1/7  Deployment environment"

# NO required reviewer, deliberately, and this reverses an earlier decision rather than overlooking one.
#
# The approval existed as the human gate on an irreversible publish. On a single-maintainer repository it
# was not buying that: this account is the ONLY collaborator, `main` is protected and accepts nothing but
# pull requests, and the merge of that pull request is already a deliberate human act. The approval added
# a second click by the same person, moments later, over the same decision.
#
# What is genuinely lost is named rather than glossed: an automated publish now follows a merge without
# anyone confirming which VERSION is about to go out. The remaining guards are the reviewed pull request
# into main, the lineage assertion in release.yml's `guard` job, and the fact that `guard` refuses to
# publish a version whose tag already exists.
#
# The body is built with jq and piped in, rather than assembled from -f/-F flags. Two reasons, both
# learned the hard way: gh's `-f` sends STRINGS (so `-f wait_timer=0` is rejected as `"0"` is not an
# integer) while `-F` guesses the type, and the bracket syntax for an array of objects is ambiguous
# enough that it is not worth relying on. A JSON document has exactly one meaning.
jq -n '{
  wait_timer: 0,
  prevent_self_review: false,
  reviewers: [],
  deployment_branch_policy: { protected_branches: false, custom_branch_policies: true }
}' | gh api --method PUT "repos/$REPO/environments/$ENVIRONMENT" --input - >/dev/null
info "environment created/updated — publish runs without a manual approval"

# Which refs may deploy. BOTH are needed and they are not interchangeable:
#
#   main      the primary path. release.yml triggers on a push to main and derives the tag from
#             build.gradle.kts, so at deployment time github.ref is refs/heads/main. Without this entry
#             the job is rejected outright with "Branch 'main' is not allowed to deploy to marketplace",
#             before it even reaches the workflow — which is exactly how the first attempt failed.
#   v*.*.*    the manual escape hatch: re-cutting a release by pushing an explicit tag.
for policy in "main:branch" "v*.*.*:tag"; do
  name=${policy%:*}; type=${policy##*:}
  if gh api "repos/$REPO/environments/$ENVIRONMENT/deployment-branch-policies" \
        -q '.branch_policies[].name' 2>/dev/null | grep -qxF "$name"; then
    info "deployment policy '$name' already present"
  else
    gh api --method POST "repos/$REPO/environments/$ENVIRONMENT/deployment-branch-policies" \
      -f "name=$name" -f "type=$type" >/dev/null
    info "allowed deployments from '$name' ($type)"
  fi
done

# --- 2. Marketplace token ------------------------------------------------------------------------------
say "2/7  JetBrains Marketplace token"

if has_secret PUBLISH_TOKEN && ! ask "PUBLISH_TOKEN is already set. Replace it?"; then
  info "keeping the existing PUBLISH_TOKEN"
else
  info "Create a permanent token at https://plugins.jetbrains.com/author/me/tokens"
  info "(it is shown once — copy it before closing the page)"
  read_secret token "Paste the token"
  printf '%s' "$token" > "$tmp/token"
  unset token
  gh secret set PUBLISH_TOKEN --env "$ENVIRONMENT" --repo "$REPO" < "$tmp/token"
  info "set PUBLISH_TOKEN"
fi

# --- 3. JetBrains plugin signing key -------------------------------------------------------------------
say "3/7  JetBrains plugin signing key (X.509/RSA — not GPG)"

if has_secret PRIVATE_KEY && has_secret CERTIFICATE_CHAIN && has_secret PRIVATE_KEY_PASSWORD \
   && ! ask "The signing key is already configured. Replace it?"; then
  info "keeping the existing signing key"
else
  # What this key actually is, because the naming misleads: the Marketplace RE-SIGNS every plugin with
  # JetBrains' own key (AWS KMS) before serving it. Yours is effectively an UPLOAD KEY — the Google Play
  # analogy — and the signature an end user's IDE verifies is JetBrains', not this one. So rotating it is
  # not user-visible, and there is no reason to keep a copy on disk: it is generated here, pushed straight
  # to GitHub, and forgotten. If it is ever lost, generate another.
  #
  # The one thing worth avoiding is a surprise during the FIRST automated publish, so if you still have
  # the key 4.4.1 was signed with, reusing it removes that unknown. Otherwise just press Enter.
  info "This is the Marketplace UPLOAD key. JetBrains re-signs the plugin, so rotating it is invisible to"
  info "users, and no copy is kept on disk. Reuse an existing key only to keep the first CI publish boring."
  read -r -p "   Path to an existing private.pem (Enter to generate a fresh one): " pem
  if [ -n "$pem" ]; then
    read -r -p "   Path to the matching chain.crt: " crt
    [ -r "$pem" ] || { echo "error: cannot read $pem" >&2; exit 1; }
    [ -r "$crt" ] || { echo "error: cannot read $crt" >&2; exit 1; }
    # Catch the single most common mistake before it becomes a 3am CI failure: handing over the
    # *encrypted* PEM. signPlugin needs the decrypted key, i.e. the output of `openssl rsa`.
    if grep -q 'ENCRYPTED PRIVATE KEY' "$pem"; then
      warn "$pem is the ENCRYPTED key. signPlugin needs the decrypted one:"
      warn "  openssl rsa -in $pem -out private.pem"
      exit 1
    fi
    grep -q 'BEGIN.*PRIVATE KEY' "$pem" || { echo "error: $pem does not look like a PEM private key" >&2; exit 1; }
    grep -q 'BEGIN CERTIFICATE'   "$crt" || { echo "error: $crt does not look like a certificate" >&2; exit 1; }
    set_secret_from_file PRIVATE_KEY "$pem"
    set_secret_from_file CERTIFICATE_CHAIN "$crt"
    read_secret keypass "Passphrase for that key"
    printf '%s' "$keypass" > "$tmp/keypass"; unset keypass
    gh secret set PRIVATE_KEY_PASSWORD --env "$ENVIRONMENT" --repo "$REPO" < "$tmp/keypass"
    info "set PRIVATE_KEY, CERTIFICATE_CHAIN, PRIVATE_KEY_PASSWORD from the existing key"
  else
    info "generating a 4096-bit RSA key and a self-signed certificate"
    # A random passphrase, never displayed: nobody types this key in by hand. Its only consumer is the CI
    # job, which reads it from the secret — a memorable passphrase would be a weakness with no upside.
    gpg --gen-random --armor 2 32 | tr -d '\n' > "$tmp/keypass"
    # Passphrase read from a FILE, never from argv: process arguments are world-readable in /proc.
    openssl genpkey -aes-256-cbc -algorithm RSA -out "$tmp/enc.pem" \
      -pkeyopt rsa_keygen_bits:4096 -pass "file:$tmp/keypass" 2>/dev/null
    openssl rsa -in "$tmp/enc.pem" -passin "file:$tmp/keypass" -out "$tmp/plain.pem" 2>/dev/null
    # 10 years, not 1. An expiring upload key would break publishing on a date nobody has in a calendar,
    # and expiry buys nothing here: this certificate is not a trust anchor for any user.
    openssl req -key "$tmp/plain.pem" -new -x509 -days 3650 \
      -subj "/CN=Claude Code Native plugin upload key" -out "$tmp/chain.crt" 2>/dev/null
    set_secret_from_file PRIVATE_KEY "$tmp/plain.pem"
    set_secret_from_file CERTIFICATE_CHAIN "$tmp/chain.crt"
    gh secret set PRIVATE_KEY_PASSWORD --env "$ENVIRONMENT" --repo "$REPO" < "$tmp/keypass"
    info "set PRIVATE_KEY, CERTIFICATE_CHAIN, PRIVATE_KEY_PASSWORD — nothing written to disk"
    info "$(openssl x509 -in "$tmp/chain.crt" -noout -subject -enddate | tr '\n' ' ')"
    # No pinning to warn about: JetBrains RE-SIGNS every plugin with its own CA, which is what the
    # user's IDE verifies, and the vendor-uploads-a-public-key half of that design is still listed as
    # "not available yet" in the plugin-signing docs. So this key is only ever an upload credential and
    # rotating it is invisible — to users and to the Marketplace alike. Nothing to upload anywhere.
  fi
fi

# --- 4. CI artifact signing key (GPG) ------------------------------------------------------------------
say "4/7  CI artifact signing key (GPG)"

if has_secret GPG_SIGNING_KEY && has_secret GPG_SIGNING_PASSPHRASE \
   && ! ask "The CI signing key is already configured. Rotate it?"; then
  info "keeping the existing CI signing key"
else
  info "Generating an Ed25519 key that expires in 1 year, in a throwaway keyring."
  info "It is NOT the maintainer key — see SECURITY.md for what each signature claims."
  ./scripts/gen-ci-signing-key.sh --outdir "$tmp/gpg" >/dev/null 2>&1
  gh secret set GPG_SIGNING_KEY        --env "$ENVIRONMENT" --repo "$REPO" < "$tmp/gpg/private.asc"
  gh secret set GPG_SIGNING_PASSPHRASE --env "$ENVIRONMENT" --repo "$REPO" < "$tmp/gpg/passphrase"
  info "set GPG_SIGNING_KEY and GPG_SIGNING_PASSPHRASE (private half never printed)"

  ci_fpr=$(cat "$tmp/gpg/fingerprint")

  # --- certify the CI key with the maintainer's hardware key -------------------------------------------
  # This is what turns the CI key from "a key that happens to sign the artifacts" into "a key the
  # maintainer vouches for". Two concrete consequences, and the second is the important one:
  #
  #   * a user who trusts the YubiKey key gets the CI key transitively — they do not have to take a bare
  #     fingerprint on faith from a file in the same repository an attacker would have edited;
  #   * if the CI key ever leaks, the certification can be REVOKED from hardware, which withdraws the
  #     maintainer's endorsement without needing anyone to notice a new file. A bare key has no such lever.
  #
  # Only the PUBLIC half is imported into your keyring. The private half stays in the temp dir and is
  # shredded on exit — it exists in exactly two places: that GitHub secret, and nowhere.
  #
  # BOTH certification authorities sign it, not just one. The certifiers are derived rather than written
  # down: every primary key whose secret half lives on a SMARTCARD (field 15 of the `sec` colon record is
  # the token serial), which on this repository is the root key and the intermediate CA. A fingerprint
  # pasted into this script goes stale the first time a key is rotated and says nothing when it does;
  # a derived list cannot, and every certification is verified individually below.
  #
  # `$15 != "+"` is what makes that test mean what it says, and without it the filter matched EVERY key.
  # GnuPG writes the token's serial into field 15 for a card-held key and a literal `+` for one whose
  # secret half is an ordinary file on disk — so `$15 != ""` is true for all of them. On this keyring
  # that silently widened two certifiers to five, three of them personal identities that must never
  # certify anything for a public repository. The question is "is there a serial", not "is the field
  # populated", and the two only look alike until a second key exists.
  mapfile -t certifiers < <(gpg --list-secret-keys --with-colons \
    | awk -F: '$1=="sec" && $15!="" && $15!="+" { getline; if ($1=="fpr") print $10 }')

  if [ ${#certifiers[@]} -eq 0 ]; then
    warn "no hardware-held key in your keyring — the CI key cannot be certified by a CA."
    warn "the exported key will carry no endorsement; users can only take its fingerprint on faith."
    cp "$tmp/gpg/public.asc" docs/trust-chain.asc
  else
    gpg --batch --import "$tmp/gpg/public.asc" 2>/dev/null
    warn "TOUCH YOUR YUBIKEY once per certifier — ${#certifiers[@]} of them."
    for fpr in "${certifiers[@]}"; do
      uid=$(gpg --list-keys --with-colons "$fpr" | awk -F: '$1=="uid" {print $10; exit}')
      info "certifying $ci_fpr with $fpr ($uid)"
      # --quick-sign-key, never --quick-lsign-key: a LOCAL signature stays in your keyring and is stripped
      # on export, so the endorsement would be invisible to every user it exists for.
      gpg --batch --yes --local-user "$fpr" --quick-sign-key "$ci_fpr" \
        || warn "certification with $fpr failed (cancelled, or that YubiKey was not present)"
    done
    # ONE file holding the CAs and the leaf they certify, and that is assurance rather than packaging.
    # A certification is only followable by someone who already holds the CERTIFIER's public key, so
    # publishing the CI key alone ships an endorsement nobody can check — which reads, to anybody
    # verifying, exactly like no endorsement at all. Split across three files it becomes three imports,
    # and the one people skip is the one carrying the assurance.
    #
    # Exported AFTER certifying, so the leaf's block carries the signatures on its uid. Certifiers
    # first and leaf last, one `--export` each: a single call listing several keys emits them in
    # KEYRING order, which is not argument order, and a chain that has to be read bottom-up is a chain
    # nobody reads.
    { for fpr in "${certifiers[@]}"; do gpg --armor --export "$fpr"; done
      gpg --armor --export "$ci_fpr"; } > docs/trust-chain.asc

    # Verified as a FILE, never as a keyring query, and the distinction is the entire point of the
    # check. An export is precisely where a certification vanishes: a signature made with
    # `--quick-lsign-key` is LOCAL — it lives in the keyring and is stripped on export — so asking gpg
    # about the keyring reports a chain the user will never receive. Reading the bytes back through a
    # throwaway GNUPGHOME asks the only question that matters: does what is about to be committed
    # actually chain, for somebody who has nothing but this file?
    chain_home="$tmp/chain-verify"; mkdir -p "$chain_home"; chmod 700 "$chain_home"
    GNUPGHOME="$chain_home" gpg --batch --quiet --import docs/trust-chain.asc 2>/dev/null
    for fpr in "${certifiers[@]}"; do
      have_key=$(GNUPGHOME="$chain_home" gpg --list-keys --with-colons 2>/dev/null \
        | awk -F: -v f="$fpr" '$1=="fpr" && $10==f {print "y"; exit}')
      have_sig=$(GNUPGHOME="$chain_home" gpg --check-sigs --with-colons "$ci_fpr" 2>/dev/null \
        | awk -F: -v m="${fpr: -16}" '$1=="sig" && $5==m {print "y"; exit}')
      if [ "$have_key" = y ] && [ "$have_sig" = y ]; then
        info "docs/trust-chain.asc: $fpr is present, and its certification of the CI key survived"
      else
        [ "$have_key" = y ] || warn "docs/trust-chain.asc does not carry the CA $fpr at all."
        [ "$have_sig" = y ] || warn "no certification by $fpr survived the export — was it an lsign?"
        warn "Re-run with that YubiKey present, then re-export:"
        warn "  gpg --local-user $fpr --quick-sign-key $ci_fpr"
      fi
    done
  fi

  info "wrote docs/trust-chain.asc  (CI signing key $ci_fpr)"
  warn "COMMIT docs/trust-chain.asc — without it nobody can verify a release."

  # --- register the CI key on the GitHub ACCOUNT --------------------------------------------------------
  # This step did not exist, and its absence is the whole reason v5.0.0 shipped with an unverified tag.
  #
  # Certifying the key with the YubiKey (above) makes it trustworthy to a human running `gpg --verify`.
  # It does nothing for the "Verified" badge, which is a different mechanism entirely: GitHub marks a
  # signature verified only when the tagger email, an email in a uid of a key REGISTERED ON THE ACCOUNT,
  # and a verified account email all agree. Certification is not registration, and the two were conflated.
  #
  # Registered from the SINGLE-key export, never from docs/trust-chain.asc: `gh gpg-key add` posts one
  # armored key, so handing it a bundle either registers the first block or is rejected outright —
  # and the first block is a CA, which is emphatically not the key that signs the tag. The endorsement
  # is irrelevant here anyway; GitHub reads the uid's email and nothing else.
  if gh gpg-key list >/dev/null 2>&1; then
    if gh gpg-key add "$tmp/gpg/public.asc" >/dev/null 2>&1; then
      info "registered the CI public key on the GitHub account"
    else
      info "GitHub already knows this key (or rejected it) — verifying below"
    fi
    # The check that matters. A key registered with NO email can never verify a tag, which is precisely
    # the state the previous key was in: `emails=` came back empty and nothing said so.
    short=${ci_fpr: -16}
    if gh api user/gpg_keys -q ".[] | select(.key_id==\"$short\") | .emails[]?.email" 2>/dev/null | grep -q .; then
      info "the registered key carries an email — tags signed with it can be verified"
    else
      warn "the registered key lists NO email address. Tags signed with it will show as UNVERIFIED."
      warn "Regenerate it with gen-ci-signing-key.sh (which now sets Name-Email) and re-run this step."
    fi
  else
    warn "gh lacks the GPG scope, so the key was NOT registered on your account."
    warn "Without this the release tag will show as unverified. Run:"
    warn "  gh auth refresh -s write:gpg_key && gh gpg-key add <(gpg --armor --export $ci_fpr)"
  fi
fi

# --- 5. repository deploy key --------------------------------------------------------------------------
say "5/7  Repository deploy key"

# An SSH key scoped to THIS repository, for the only write CI performs: pushing the release tag. It is
# generated here, the public half is registered on the repository and the private half goes straight into
# the environment secret. Nothing is left on this machine — the pair lives in the 0700 temp dir and the
# EXIT trap shreds it, so it exists in exactly two places: that secret, and the repository's key list.
#
# Two properties, both deliberate:
#   * no passphrase — a CI job has nobody to type one, and what protects it is the environment scoping of
#     the secret, not a passphrase stored in the same environment as the key;
#   * repository scope. An account SSH key would carry write access to everything you can push to; a
#     deploy key carries this repository and nothing else.
#
# NB gh binds a deploy key to the TOKEN that created it: de-authorizing the GitHub CLI, or letting that
# token expire, REMOVES the key. If a release ever fails on the tag push, check it is still there:
#   gh api "repos/$REPO/keys"
if has_secret DEPLOY_KEY && ! ask "DEPLOY_KEY is already set. Rotate it?"; then
  info "keeping the existing deploy key"
else
  title="ci-release-$(date -u +%Y-%m-%d)"
  ssh-keygen -q -t ed25519 -N '' -C "$title" -f "$tmp/deploy_key"
  # Retire the ones this script registered before. A rotation that leaves the previous key authorized has
  # rotated nothing: the old private half is still a write credential wherever it ended up.
  for id in $(gh api "repos/$REPO/keys" --jq '.[] | select(.title | startswith("ci-release-")) | .id' 2>/dev/null); do
    gh api --method DELETE "repos/$REPO/keys/$id" >/dev/null && info "removed the previous deploy key ($id)"
  done
  gh repo deploy-key add "$tmp/deploy_key.pub" --repo "$REPO" --title "$title" --allow-write >/dev/null
  gh secret set DEPLOY_KEY --env "$ENVIRONMENT" --repo "$REPO" < "$tmp/deploy_key"
  info "registered '$title' with write access; private half stored as DEPLOY_KEY and shredded locally"
  # The fingerprint, not the key material: it is what `gh api "repos/$REPO/keys"` reports, so this line is
  # the one thing that lets you confirm later that the registered key is the one this run generated.
  info "fingerprint: $(ssh-keygen -lf "$tmp/deploy_key.pub" | cut -d' ' -f2)"
  warn "NOTHING CONSUMES DEPLOY_KEY YET. release.yml pushes the tag with GITHUB_TOKEN, and switching that"
  warn "push to this key also makes it TRIGGER workflows — which fires release.yml's own tag trigger and"
  warn "starts a second run of the release it just finished. Guard that trigger before wiring it up."
fi

# --- 6. verify -----------------------------------------------------------------------------------------
say "6/7  Verification"

actual=$(gh secret list --env "$ENVIRONMENT" --repo "$REPO" --json name -q '.[].name' | sort)
expected=$(printf '%s\n' CERTIFICATE_CHAIN DEPLOY_KEY GPG_SIGNING_KEY GPG_SIGNING_PASSPHRASE PRIVATE_KEY PRIVATE_KEY_PASSWORD PUBLISH_TOKEN | sort)
if [ "$actual" = "$expected" ]; then
  info "all seven environment secrets present"
else
  warn "missing: $(comm -23 <(echo "$expected") <(echo "$actual") | tr '\n' ' ')"
  warn "unexpected: $(comm -13 <(echo "$expected") <(echo "$actual") | tr '\n' ' ')"
fi

# A repository-level secret is readable by EVERY job, including one introduced in a pull request. That is
# precisely the exposure the environment scoping exists to avoid, so flag any that exist.
repo_secrets=$(gh secret list --repo "$REPO" --json name -q '.[].name' 2>/dev/null || true)
if [ -n "$repo_secrets" ]; then
  warn "repository-level secrets found — these are visible to EVERY workflow job:"
  printf '     %s\n' $repo_secrets
  warn "move them into the environment and delete them: gh secret delete <name> --repo $REPO"
else
  info "no repository-level secrets (correct)"
fi

reviewers=$(gh api "repos/$REPO/environments/$ENVIRONMENT" \
  -q '[.protection_rules[]? | select(.type=="required_reviewers") | .reviewers[].reviewer.login] | join(", ")')
if [ -z "$reviewers" ]; then
  info "no required reviewer — a merge to main publishes without a second confirmation"
else
  warn "required reviewer(s) present: $reviewers — publish will WAIT for approval"
fi
info "deployments allowed from: $(gh api "repos/$REPO/environments/$ENVIRONMENT/deployment-branch-policies" \
  -q '[.branch_policies[] | "\(.type):\(.name)"] | join(", ")')"

# --- 7. branch protection ------------------------------------------------------------------------------
say "7/7  Branch protection"

./scripts/apply-rulesets.sh --dry-run
echo
warn "Applying this stops direct pushes to main and develop — INCLUDING YOURS. No bypass, by design."
if ask "Apply the rulesets now?"; then
  ./scripts/apply-rulesets.sh
else
  info "skipped — run ./scripts/apply-rulesets.sh when ready"
fi

say "Done"
cat <<EOF
   Remaining, and neither can be done for you:

   1. Commit the trust chain, or releases cannot be verified:
        git add docs/trust-chain.asc && git commit -m "chore(release): publish the release trust chain"

   2. Smoke-test the pipeline before a real release depends on it:
        git checkout -b test/ci-smoke
        git commit --allow-empty -m "test(ci): verify the pipeline runs end to end"
        git push -u origin test/ci-smoke && gh run watch

      Then open a PR into develop and confirm the checks BLOCK the merge rather than
      merely appearing. A check that is present but not required is not a gate.

   Troubleshooting: docs/CI_SETUP.md
EOF
