#!/usr/bin/env bash
#
# probe-binary.sh — sanity-check the local `claude` binary against the set of
# stream-json event `type`s that the plugin's ProtocolParser knows how to handle.
#
# Usage:
#   bash scripts/probe-binary.sh [path-to-claude] [fixture.jsonl]
#
# Defaults:
#   path-to-claude = $HOME/.local/bin/claude
#   fixture.jsonl  = a single canned user message ("Say hi and exit.")
#
# Exit codes:
#   0  -> only expected `type` values seen
#   1  -> usage / environment error (binary missing, timeout with no output, ...)
#   2  -> at least one UNKNOWN_EVENT_TYPE was observed (drift signal for CI)
#
# This is meant to be cheap and offline-friendly enough for CI: it does NOT
# require an API key to be valid (we only care about what `type`s the binary
# emits; even an auth error surfaces as a `result`/`system` event we recognise).

set -euo pipefail

BINARY="${1:-$HOME/.local/bin/claude}"
FIXTURE="${2:-}"

if [[ ! -x "$BINARY" ]]; then
    echo "ERROR: claude binary not found or not executable at: $BINARY" >&2
    exit 1
fi

TS="$(date +%s)"
OUT="/tmp/probe-${TS}.jsonl"

# Hardcoded inline expected types — keep in sync with
# src/main/kotlin/dev/lain/claudejb/protocol/ProtocolParser.kt
EXPECTED_TYPES=(system assistant user result stream_event control_request control_response rate_limit_event)

is_expected() {
    local t="$1"
    local e
    for e in "${EXPECTED_TYPES[@]}"; do
        [[ "$t" == "$e" ]] && return 0
    done
    return 1
}

# Build the stdin payload: either user-supplied fixture or a canned one-shot.
build_stdin() {
    if [[ -n "$FIXTURE" ]]; then
        if [[ ! -r "$FIXTURE" ]]; then
            echo "ERROR: fixture not readable: $FIXTURE" >&2
            exit 1
        fi
        cat "$FIXTURE"
    else
        printf '%s\n' '{"type":"user","message":{"role":"user","content":"Say hi and exit."},"parent_tool_use_id":null}'
    fi
}

echo "probe-binary: binary=$BINARY"
echo "probe-binary: capturing stdout -> $OUT"

# Run the binary with the same flags the plugin uses to talk to it.
# `timeout 60` caps the whole exchange; we don't care about its exit code,
# only about what landed on stdout.
set +e
build_stdin | timeout 60 "$BINARY" \
    --print \
    --output-format stream-json \
    --input-format stream-json \
    --verbose \
    --permission-prompt-tool stdio \
    >"$OUT" 2>/tmp/probe-${TS}.stderr
RC=$?
set -e

if [[ ! -s "$OUT" ]]; then
    echo "ERROR: binary produced no stdout (rc=$RC). stderr follows:" >&2
    cat "/tmp/probe-${TS}.stderr" >&2 || true
    exit 1
fi

# Extract unique `type` values from the NDJSON stream.
# We use a small Python pass to stay robust against malformed lines (the lenient
# codec in production swallows those silently — that's exactly the thing we want
# to surface here).
mapfile -t SEEN_TYPES < <(python3 - "$OUT" <<'PY'
import json, sys, collections
counts = collections.Counter()
with open(sys.argv[1], "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            counts["<malformed>"] += 1
            continue
        t = obj.get("type", "<missing>")
        counts[t] += 1
for t, n in sorted(counts.items()):
    print(f"{t}\t{n}")
PY
)

unknown=0
echo
echo "Event types observed:"
printf '  %-24s %s\n' "TYPE" "COUNT"
printf '  %-24s %s\n' "----" "-----"
for row in "${SEEN_TYPES[@]}"; do
    t="${row%%	*}"
    n="${row##*	}"
    if is_expected "$t"; then
        marker="ok"
    else
        marker="UNKNOWN"
        unknown=1
        echo "UNKNOWN_EVENT_TYPE: $t" >&2
    fi
    printf '  %-24s %-6s %s\n' "$t" "$n" "$marker"
done

echo
if [[ $unknown -ne 0 ]]; then
    echo "probe-binary: drift detected — at least one unexpected event type was emitted." >&2
    echo "probe-binary: full capture preserved at $OUT" >&2
    exit 2
fi

echo "probe-binary: ok — all observed event types are known to the plugin."
echo "probe-binary: capture: $OUT"
exit 0
