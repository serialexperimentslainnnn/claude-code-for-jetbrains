#!/usr/bin/env bash
# Apply the branch protection rulesets in .github/rulesets/ to the GitHub repository.
#
# Branch protection is the one part of the CI/CD setup that does NOT live in the repository by default —
# it lives in a settings page, where it is invisible in review, undiffable, and silently editable. That
# is a bad place for a control whose entire job is to be non-bypassable, so the rulesets are versioned
# here and this script pushes them.
#
# Idempotent: a ruleset with a matching name is UPDATED, not duplicated. Safe to re-run.
#
#   ./scripts/apply-rulesets.sh            # apply
#   ./scripts/apply-rulesets.sh --dry-run  # show what would change and exit
#
# Requires: gh (authenticated with admin rights on the repo) and jq.
set -euo pipefail

cd "$(dirname "$0")/.."

dry_run=false
[ "${1:-}" = "--dry-run" ] && dry_run=true

command -v gh >/dev/null || { echo "gh is required: https://cli.github.com" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)
echo "repository: $repo"

existing=$(gh api "repos/$repo/rulesets" --paginate 2>/dev/null || echo '[]')

for file in .github/rulesets/*.json; do
  name=$(jq -r .name "$file")
  id=$(echo "$existing" | jq -r --arg n "$name" 'map(select(.name == $n)) | .[0].id // empty')

  if $dry_run; then
    if [ -n "$id" ]; then
      echo "would UPDATE ruleset '$name' (id $id) from $file"
    else
      echo "would CREATE ruleset '$name' from $file"
    fi
    continue
  fi

  # The JSON files carry `_comment` keys explaining the non-obvious choices — chiefly why the required
  # approval count is 0 on a single-maintainer repo. Those keys are documentation, not API fields, so
  # they are stripped here rather than risking a 422 on an unrecognised property.
  #
  # Every key with the `_comment` PREFIX, not just the exact name. Two comments cannot share one object
  # under the exact-match version, so the moment a second annotation is needed in the same block the
  # obvious move is to call it `_comment_<something>` — which then sails through this filter and gets
  # rejected by the API as an unrecognised property. The 422 does not name the offending key, so the
  # failure reads as "the ruleset is wrong" rather than "the comment leaked". Observed, not hypothetical.
  body=$(jq 'walk(if type == "object"
                  then with_entries(select(.key | startswith("_comment") | not))
                  else . end)' "$file")

  if [ -n "$id" ]; then
    echo "updating '$name' (id $id)…"
    printf '%s' "$body" | gh api --method PUT "repos/$repo/rulesets/$id" --input - >/dev/null
  else
    echo "creating '$name'…"
    printf '%s' "$body" | gh api --method POST "repos/$repo/rulesets" --input - >/dev/null
  fi
done

$dry_run && exit 0

# Read back what is ACTUALLY in place rather than trusting that the writes above worked. The listing is
# retried because the API is eventually consistent: read immediately after creating and it can come back
# empty, which looks exactly like a failure and is not one.
echo
echo "Active rulesets:"
for attempt in 1 2 3; do
  out=$(gh api "repos/$repo/rulesets" -q '.[] | "  \(.name)  [\(.enforcement)]  bypass_actors=\(.bypass_actors|length)"' 2>/dev/null || true)
  [ -n "$out" ] && break
  sleep 2
done
if [ -n "$out" ]; then
  printf '%s\n' "$out"
else
  echo "  (none returned — check manually: gh api repos/$repo/rulesets)"
fi

cat <<'EOF'

Two things to check that this script cannot check for you:

  1. THE APPROVAL COUNT IS 0, deliberately. GitHub does not let an author approve their own pull
     request, so on a single-maintainer repository "require 1 approval" plus no bypass actors means
     nothing can ever be merged — not by PR, not by push, not by admin. What is enforced instead is
     mechanical and cannot be argued with: a pull request, an up-to-date branch, and every status check
     green. Raise it to 1 (and re-enable code-owner review) the day a second maintainer has write access.

  2. The required status check NAMES have to resolve. A ruleset references a check by the job's DISPLAY
     name; rename a job and the gate does not fail — it silently stops applying. After the first CI run
     on a pull request, confirm every name in .github/rulesets/*.json appears in that PR's check list.
EOF
