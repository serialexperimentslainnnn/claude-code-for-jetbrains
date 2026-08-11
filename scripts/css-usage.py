#!/usr/bin/env python3
"""Which CSS classes in app.css are never named by the web app.

A reporting aid for the periodic CSS sweep, not a gate: `src/test/frontend/css-contract.test.js` is the
gate, and it checks the opposite direction (a class the JS emits with no rule). This one answers "is this
rule still reachable?", which is what lets dead CSS be deleted instead of accumulating.

It is deliberately CONSERVATIVE about "used": any occurrence of the bare name anywhere in the JS or the
shell counts, because classes are also built by concatenation (`'pill-dot ' + status`). A name reported
here is a CANDIDATE to delete — read the rule before removing it.

Known-good residue, so nobody re-derives it: `hljs-*` plus `class_` and `function_` are the syntax theme,
emitted by the vendored highlight.js and not by our code; `stopped` is one of the status words the host
sends and the page concatenates.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JCEF = ROOT / "src" / "main" / "resources" / "jcef"

COMMENT = re.compile(r"/\*.*?\*/", re.S)
CLASS_IN_CSS = re.compile(r"\.([a-zA-Z][\w-]*)")
WORD = re.compile(r"[a-zA-Z][\w-]*")


def main() -> int:
    css = COMMENT.sub("", (JCEF / "app.css").read_text(encoding="utf-8"))
    defined = set(CLASS_IN_CSS.findall(css))

    sources = sorted(JCEF.glob("app-*.js")) + [JCEF / "shell.html"]
    words = set()
    for path in sources:
        words.update(WORD.findall(path.read_text(encoding="utf-8")))

    dead = sorted(name for name in defined if name not in words)
    print(f"{len(defined)} classes defined, {len(dead)} never named by the app:")
    for name in dead:
        print(f"  {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
