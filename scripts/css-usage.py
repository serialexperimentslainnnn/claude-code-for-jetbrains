#!/usr/bin/env python3
"""Which CSS classes in the stylesheet are never named by the web app.

A reporting aid for the periodic CSS sweep, not a gate: `src/test/frontend/css-contract.test.js` is the
gate, and it checks the opposite direction (a class the JS emits with no rule). This one answers "is this
rule still reachable?", which is what lets dead CSS be deleted instead of accumulating.

The stylesheet is not one file: it is the parts of `jcef/css/`, concatenated in CASCADE ORDER by
`JcefHost.CSS_PARTS`. That list is READ OUT OF THE KOTLIN, never copied here — this script used to open a
single `app.css` that the split deleted, and it had been dead ever since. `readdirSync`-style discovery
would be the same mistake in a slower form: a part added to the host but not seen here would go unreported.

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
JCEF_HOST = ROOT / "src" / "main" / "kotlin" / "dev" / "lain" / "claudejb" / "ui" / "jcef" / "JcefHost.kt"

COMMENT = re.compile(r"/\*.*?\*/", re.S)
CLASS_IN_CSS = re.compile(r"\.([a-zA-Z][\w-]*)")
WORD = re.compile(r"[a-zA-Z][\w-]*")
CSS_PART = re.compile(r'"([\w-]+\.css)"')


def css_parts() -> list[str]:
    """The stylesheet's parts, in cascade order, as `JcefHost` concatenates them.

    The DECLARATION, not the first mention: `CSS_PARTS` is also read higher up, where the page is built.
    """
    source = JCEF_HOST.read_text(encoding="utf-8")
    start = source.find("val CSS_PARTS = listOf(")
    if start < 0:
        raise SystemExit(f"css-usage: could not find CSS_PARTS in {JCEF_HOST}")
    block = source[start : source.index(")", start)]
    parts = CSS_PART.findall(block)
    if not parts:
        raise SystemExit(f"css-usage: CSS_PARTS in {JCEF_HOST} listed no .css files")
    return parts


def main() -> int:
    # Which part defines it, so a candidate can be opened rather than hunted for. First definition wins,
    # which is the one the cascade starts from.
    origin: dict[str, str] = {}
    for part in css_parts():
        path = JCEF / "css" / part
        if not path.exists():
            raise SystemExit(f"css-usage: {path} is in CSS_PARTS but does not exist")
        for name in CLASS_IN_CSS.findall(COMMENT.sub("", path.read_text(encoding="utf-8"))):
            origin.setdefault(name, part)

    sources = sorted(JCEF.glob("app-*.js")) + [JCEF / "shell.html"]
    words = set()
    for path in sources:
        words.update(WORD.findall(path.read_text(encoding="utf-8")))

    dead = sorted(name for name in origin if name not in words)
    print(f"{len(origin)} classes defined, {len(dead)} never named by the app:")
    for name in dead:
        print(f"  {origin[name]:16} {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
