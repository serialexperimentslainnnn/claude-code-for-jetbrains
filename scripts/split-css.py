#!/usr/bin/env python3
"""One-shot: split `app.css` into per-component files at its own section boundaries.

Mechanical by construction and verified as such: the parts are contiguous line ranges of the original, in
the original order, and the script refuses to write anything unless concatenating them reproduces the input
byte for byte. CSS order is semantics (later rules win), so a split that reorders is a rewrite, not a split.

Run once, from the repository root. Kept in the tree as the record of how the parts were cut.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JCEF = ROOT / "src" / "main" / "resources" / "jcef"
SOURCE = JCEF / "app.css"
OUT = JCEF / "css"

# (file, first line, last line) — 1-based and inclusive, cut at the file's own section banners.
PARTS = [
    ("base.css", 1, 193),
    ("transcript.css", 194, 953),
    ("composer.css", 954, 1584),
    ("permissions.css", 1585, 1930),
    ("dashboard.css", 1931, 2657),
    ("boot.css", 2658, 3009),
    ("tabs.css", 3010, None),
]


def main() -> int:
    lines = SOURCE.read_text(encoding="utf-8").splitlines(keepends=True)
    parts = []
    for name, start, end in PARTS:
        stop = len(lines) if end is None else end
        parts.append((name, "".join(lines[start - 1 : stop])))

    if "".join(body for _, body in parts) != "".join(lines):
        print("refusing to write: the parts do not reproduce app.css exactly", file=sys.stderr)
        return 1

    OUT.mkdir(exist_ok=True)
    for name, body in parts:
        (OUT / name).write_text(body, encoding="utf-8")
        print(f"{name}: {body.count(chr(10))} lines")
    SOURCE.unlink()
    return 0


if __name__ == "__main__":
    sys.exit(main())
