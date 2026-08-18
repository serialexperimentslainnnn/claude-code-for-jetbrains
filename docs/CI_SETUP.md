# CI/CD setup — one-time configuration

Everything the pipeline needs that is **not** in the repository: the deployment environment, its six
secrets, and the branch protections. Follow this once; afterwards a release is a merge into `main` (see
[`RELEASE_PROCEDURE.md`](RELEASE_PROCEDURE.md) — the workflow cuts the tag itself, and nobody tags by hand).

All of it uses `gh` rather than the web UI, for one reason that matters: three of the six secrets are
**multi-line PEM / armoured blocks** (`PRIVATE_KEY`, `CERTIFICATE_CHAIN`, `GPG_SIGNING_KEY`), and pasting
those into a browser form is where a stray newline or a truncated line ends up in a secret that then fails at
3 a.m. with an error that does not say why. Reading them from a file or stdin cannot do that.

Prerequisites: `gh` authenticated with admin rights on the repository, plus `jq`, `gpg` and `openssl`.

## The short version

```sh
./scripts/bootstrap-ci.sh
```

It does everything below: creates the environment with **no required reviewer** (see §1), restricts
deployments to `main` and `v*.*.*` tags, generates and certifies the CI signing key, sets all six secrets,
checks that none leaked to repository level, and offers to apply the branch protections. It asks you only for what you actually
hold — the Marketplace token, and the JetBrains signing key. Idempotent: existing secrets are reported
and skipped unless you say to replace them.

Have your YubiKey plugged in; it is needed once, to certify the CI key.

The rest of this document is what the script does, step by step, for when you need to do one part by hand
or work out why something failed.

---

## Step 1 — Create the `marketplace` environment

This environment is where every credential that can reach a user lives — the Marketplace token, the three
parts of the JetBrains upload key, and the CI artifact signing key with its passphrase — which means they
exist for **no other job** in the repository.

**There is deliberately no required reviewer.** The environment carries one protection rule: the
deployment-branch policy below. So a merge into `main` that bumps the
version **publishes unattended** — the human act is opening and merging the pull request, and nothing after
it (the rulesets require no approval; see [`BRANCHING.md`](BRANCHING.md)). On a
single-maintainer repository an approval prompt is the same person clicking twice; it reads as a control and
is not one. Verified against the API on 2026-08-11, and it is what `scripts/bootstrap-ci.sh` sets on purpose
(`reviewers: []`, logging *"publish runs without a manual approval"*).

```sh
jq -n '{
  wait_timer: 0,
  prevent_self_review: false,
  reviewers: [],
  deployment_branch_policy: { protected_branches: false, custom_branch_policies: true }
}' | gh api --method PUT "repos/$REPO/environments/marketplace" --input -
```

> Build the body as JSON rather than from `-f`/`-F` flags. `gh api -f` sends **strings**, so
> `-f wait_timer=0` is rejected with `Invalid property /wait_timer: "0" is not of type integer`; `-F`
> guesses the type instead; and the bracket syntax for an array of objects (`reviewers[][type]=`) is
> ambiguous enough not to rely on. A JSON document has exactly one meaning.

**If a second maintainer ever exists, add them here** — `reviewers: [{ type: "User", id: <their id> }]` — and
update `SECURITY.md`, `BRANCHING.md` and ADR 0001 §5 in the same change, since all three currently state that
publication is *not* approval-gated. Keep `prevent_self_review: false` regardless: whoever merges is the
deployment creator, so setting it would forbid the only available approver and nothing would ever publish.

Then restrict the environment to what may deploy from it — **both** entries, and both are needed:

```sh
gh api --method POST "repos/$REPO/environments/marketplace/deployment-branch-policies" \
  -f name='main' -f type=branch
gh api --method POST "repos/$REPO/environments/marketplace/deployment-branch-policies" \
  -f name='v*.*.*' -f type=tag
```

`main` is the **primary** release path — `release.yml` triggers on the push that a merge creates, and cuts
the tag itself from inside the gated job — so a tag-only policy would block every ordinary release. The tag
entry covers the escape hatch (re-running after a failed publish).

That is a second, independent lock on top of the workflow's own lineage guard. The guard checks the commit
came from `main`; this checks the environment is only ever reachable from `main` or a version tag at all.

Verify — expect an empty `reviewers` list and both policies:

```sh
gh api "repos/$REPO/environments/marketplace" \
  -q '[.protection_rules[]? | select(.type=="required_reviewers") | .reviewers[].reviewer.login]'
gh api "repos/$REPO/environments/marketplace/deployment-branch-policies" \
  -q '[.branch_policies[] | "\(.type):\(.name)"] | join(", ")'   # → branch:main, tag:v*.*.*
```

---

## Step 2 — The JetBrains publishing token

1. Go to <https://plugins.jetbrains.com/author/me/tokens>.
2. Create a permanent token, name it something like `github-actions-release`.
3. Copy it — it is shown once.

```sh
gh secret set PUBLISH_TOKEN --env marketplace --repo "$REPO"
# paste the token, then press Ctrl-D
```

Reading from stdin instead of `--body` keeps the token out of your shell history and out of the process
list, where any other user on the machine could have read it.

---

## Step 3 — The JetBrains plugin signing key

This is an **X.509** key. It is *not* GPG and it is unrelated to the key in step 4.

**What it actually is, because the name misleads.** The Marketplace **re-signs every plugin with
JetBrains' own key** (AWS KMS) before serving it — *"the file will be signed twice: first by the plugin
author, then by JetBrains Marketplace"*. Yours is therefore an **upload key**, the same idea as Google
Play's: the signature an end user's IDE verifies is JetBrains', not yours.

Two consequences, both the opposite of what the name suggests:

- **Rotating it is invisible to users** *and* to the Marketplace. There is no public key pinned to a
  vendor profile to keep in step — that half of the design is still listed as "not available yet" in the
  plugin-signing docs — so there is nothing to upload anywhere after a rotation, and no reason to keep a
  copy on disk. The bootstrap script issues it, pushes it to GitHub, and forgets it.
- **It still cannot be dropped.** An unsigned upload is accepted, and then every user who installs the
  plugin gets a warning dialog. The trade is one software key in an environment secret against a dialog
  in front of everyone.

**It is issued by the local PKI, not self-signed.** The certificate is a `codeSigning` leaf under *The
Oracle Intermediate CA*, which chains to *The Architect Root CA* — the same trust chain the release
signatures hang off, so the upload credential is not a stray anchor nobody can place.

**There is nothing to hand over and nothing to prepare.** The step asks no question: plug both CA
YubiKeys in and it issues, verifies and uploads. The two halves of the CA come from deliberately
different places, and neither is written down anywhere in the script:

- **The CA certificates come off the YubiKeys, PIV slot `F9`**, which is where this PKI keeps them. Both
  cards are read and neither is assumed to be either CA — the card whose `F9` certificate is *self-issued*
  is the root, the other is the intermediate. No serial, no DN and no fingerprint is hardcoded, so a
  reissued PKI is picked up rather than contradicted.
- **The CA private key comes from `gpgsm`**, GnuPG's X.509 store. It cannot come from `F9`: that slot is
  storage in this PKI, never a signer, and a PIV certificate object has no private half to offer. `gpgsm`
  can export a key but cannot *issue* one — GnuPG is not a CA — so it lands in the temp dir for exactly
  one `openssl` call and is shredded immediately, not at the exit trap. The name it is asked for is read
  off the certificate the card just handed over, so the two halves cannot drift apart.

The PKI's own working directory is neither read nor written, and `build-matrix-pki.sh` is never run: the
script asks the PKI for one leaf, it never creates, resets or reissues anything.

```sh
ykman --device "$serial" piv certificates export f9 -      # per card; public read, no PIN, no touch
gpgsm --armor --export-secret-key-p8 "$intermediate_cn"    # shredded immediately after the sign

openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:secp384r1 -aes-256-cbc -out leaf.key
openssl req -new -key leaf.key -sha384 -out leaf.csr \
  -subj "/C=US/O=Zion/OU=Nebuchadnezzar/CN=Claude Code Native plugin upload key"
openssl x509 -req -in leaf.csr -sha384 -CA int.crt -CAkey int.key \
  -set_serial "0x$(openssl rand -hex 16)" -days 3650 -extfile leaf.ext -out leaf.crt
cat leaf.crt int.crt > fullchain.crt
openssl verify -CAfile root.crt -untrusted int.crt leaf.crt
```

Four details there are decisions rather than defaults:

- **EC P-384 / SHA-384**, the shape this PKI issues in, and one `marketplace-zip-signer` supports
  natively (`SignatureAlgorithm.ECDSA_WITH_SHA384`) — no RSA detour to keep a tool happy that does not
  need one.
- **`extendedKeyUsage = codeSigning`**, written here rather than borrowed: the PKI's own leaf profile is
  `clientAuth,serverAuth`, i.e. a TLS certificate, which is the wrong claim for one that signs an
  artifact.
- **`openssl verify` runs before any secret is set**, so a wrong CA — a stale card, a half-reissued PKI —
  fails loudly instead of publishing.
- **Ten years**, rather than JetBrains' example one or the PKI's own 1095 days: an expiring upload key
  breaks publishing on a date nobody has in a calendar, and expiry protects nothing here, since the
  certificate is not a trust anchor for any user.

`PRIVATE_KEY` is stored **encrypted**, with `PRIVATE_KEY_PASSWORD` as the matching passphrase — a random
32 bytes that is never displayed, because its only consumer is the CI job that reads it from the secret.

`CERTIFICATE_CHAIN` is **leaf then issuer**, with the root deliberately left out: a self-signed anchor in
the chain adds nothing a verifier can use, since it either already trusts that root or must not be told
to.

---

## Step 4 — The CI artifact signing key (GPG)

This key signs the `.zip.asc` and `.sha256.asc` attached to each GitHub Release. It is **not** the
maintainer key, deliberately — see [`../SECURITY.md`](../SECURITY.md) for what each signature claims and
why they must stay distinguishable.

```sh
./scripts/gen-ci-signing-key.sh
```

The script builds the key in a throwaway keyring (deleted on exit), never touches your own, and prints
three things: the private block, the passphrase, and the public key.

```sh
# Paste the block between the GPG_SIGNING_KEY markers, including both BEGIN/END lines, then Ctrl-D:
gh secret set GPG_SIGNING_KEY --env marketplace --repo "$REPO"

# Paste the generated passphrase, then Ctrl-D:
gh secret set GPG_SIGNING_PASSPHRASE --env marketplace --repo "$REPO"
```

Then **certify it with the two hardware CAs**, and publish the whole chain in one file:

```sh
CI_FPR=<fingerprint printed by the script>
ROOT_FPR=E70A886589AB9AB9DC2D2CA3B746AD2C841D5CE3
INT_FPR=318BBEFF6E5DD5A03A8280518DAB773C3796B834
gpg --import public.asc                                     # PUBLIC half only
gpg --local-user "$ROOT_FPR" --quick-sign-key "$CI_FPR"     # Root, YubiKey 27263482
gpg --local-user "$INT_FPR"  --quick-sign-key "$CI_FPR"     # Intermediate, YubiKey 32861026
{ gpg --armor --export "$ROOT_FPR" "$INT_FPR"               # export AFTER signing
  gpg --armor --export "$CI_FPR"; } > docs/trust-chain.asc  # CI key LAST — see below
git add docs/trust-chain.asc
git commit -m "chore(release): publish the release trust chain"
```

`--quick-sign-key`, never `--quick-lsign-key`: a **local** certification is stripped on export, so the
bundle would carry the CAs and no endorsement at all — and it looks identical to a correct one until
someone else imports it. `./scripts/bootstrap-ci.sh` does all of the above and then re-imports its own
output into a throwaway keyring to check the signatures survived, which is the only way to find out.

The CI key goes **last** in the file on purpose: it is the leaf, so anything reading the bundle for "the
key that signed this release" takes the last public block, and `release.yml` does exactly that.

The certification is not ceremony. Without it, a user is asked to trust a fingerprint printed in a file
**inside the repository an attacker who could swap the key would also control** — which is not a trust
anchor, it is a tautology. With it, the chain terminates in hardware. And it is the only revocation lever
you have: if the CI key leaks you revoke the endorsement from the YubiKey, which no one holding the leaked
key can undo. The procedure is in [`../SECURITY.md`](../SECURITY.md).

Never import the **private** half into your keyring. It belongs in exactly one place — the environment
secret. Keeping it out is what stops it quietly becoming a second maintainer identity.

---

## Step 5 — Check all six are set

```sh
gh secret list --env marketplace --repo "$REPO"
```

Expect exactly these, and nothing in **repository** secrets:

```
CERTIFICATE_CHAIN
GPG_SIGNING_KEY
GPG_SIGNING_PASSPHRASE
PRIVATE_KEY
PRIVATE_KEY_PASSWORD
PUBLISH_TOKEN
```

```sh
gh secret list --repo "$REPO"   # should be empty
```

A secret at repository level is readable by **every** workflow job, including one added in a pull request.
That is the difference this step is checking for.

---

## Step 6 — Apply the branch protections

```sh
./scripts/apply-rulesets.sh --dry-run   # read-only: shows what would change
./scripts/apply-rulesets.sh
```

**After this, `main` and `develop` stop accepting direct pushes — including yours.** There are no bypass
actors, by design (see [`BRANCHING.md`](BRANCHING.md)). From here on the flow is: branch → PR → checks
green → merge. No approval is required (and none can be given on a single-maintainer repository).

The required status checks are referenced by **job display name**. They will show as pending until the
first CI run has reported them once; that is expected, not a misconfiguration.

---

## Step 7 — Prove it works before you need it

Do not let the first exercise of this machinery be a real release.

```sh
git checkout -b test/ci-smoke
git commit --allow-empty -m "test(ci): verify the pipeline runs end to end"
git push -u origin test/ci-smoke
gh pr create --base develop --title "test(ci): pipeline smoke" --body "Delete after checking."
gh run watch
```

**Open the pull request — pushing the branch on its own runs nothing.** `ci.yml` has no `push` trigger; a
branch with no PR gets no checks, by design. On a PR into `develop` expect *JVM tests*, *Frontend tests* and
both CodeQL analyses; the rest of the jobs only run on a PR into `main`. Confirm the checks are **required**
rather than merely present — the merge button should stay blocked until they are green.

Delete the branch afterwards.

The release path itself cannot be smoke-tested without publishing, so the first real release is where the
`guard` job earns its keep: if the commit is not reachable from `main`, or a hand-pushed tag does not match
the version in `build.gradle.kts`, it fails in seconds and before any secret is in scope.

---

## If something goes wrong

| Symptom | Cause |
|---|---|
| `publish` starts without asking for approval | expected — there is no required reviewer, by design (§1) |
| `publish` never starts, waiting forever | someone added a reviewer *and* `prevent_self_review: true`; the only approver is the person who merged |
| Deployment rejected: branch not allowed | the deployment-branch policy is missing `main` or `v*.*.*` — both entries are required (step 1) |
| `gpg: no default secret key` | `GPG_SIGNING_KEY` is truncated — re-set it from a file, not by pasting |
| `signPlugin` fails on the key | `PRIVATE_KEY` is the *encrypted* PEM; it must be the output of `openssl rsa` |
| A required check is stuck pending forever | a job was renamed and no longer matches the name in `.github/rulesets/` |
