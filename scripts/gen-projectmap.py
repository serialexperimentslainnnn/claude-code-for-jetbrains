#!/usr/bin/env python3
"""The generated half of every PROJECTMAP.md: what lives where, derived from the sources themselves.

Each map is two documents in one file. OUTSIDE the `MAP:GENERATED` markers is prose a person wrote — why a
boundary exists, what is deliberate, what is a trap — and this script does not touch a byte of it. BETWEEN
them is the index: symbols and the line to go to, the web app's public registrations, one row per document.
That half is derived, which is the only version of it that stays true — a hand-written line number is fiction
after the next edit, and a map that lies is worse than no map, because nobody re-checks it.

There is ONE operation: ensure each target's generated block is present and current. A map that does not
exist yet, and a map that carries no block, are degenerate cases of it rather than features beside it — they
are written by the same call that rewrites a stale block, so adding a directory to `TARGETS` is the whole
bootstrap and no step has to be remembered. The single refusal is a MALFORMED marker pair — unpaired, out of
order or duplicated — because there the end of the hand-written prose is genuinely ambiguous and a guess
would eat it.

`--check` regenerates in memory and diffs against disk. That is the gate (`./gradlew checkProjectMap`, plus a
step in CI's `Static analysis` job), and it is what makes the index an invariant instead of documentation. A
missing map is an ordinary divergence there: every target is reported, so a run says which packages have no
map rather than stopping at the first one that does not.

Nothing MEASURED is ever emitted: no counts, no totals, no percentages. A measurement is stale on the next
commit and gets quoted as if it were not. There is no generation date or SHA in the block either, for the
same reason and a mechanical one on top — a stamp that moves on its own fails the gate every morning, and a
gate that cries wolf teaches everyone to regenerate without reading.

The Kotlin dialect is the one `ReachabilityContractTest` already proved against this codebase: a comment is
not a declaration, a string literal is not a declaration, `private` and `override` are not indexed, and the
scan reaches top-level declarations plus the members of top-level `object`s. Two parsers disagreeing about
the same sources is how a gate starts arguing with a test.

    python3 scripts/gen-projectmap.py            # rewrite every generated block
    python3 scripts/gen-projectmap.py --check    # diff against disk instead, and fail on any divergence
"""

import argparse
import difflib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAP_NAME = "PROJECTMAP.md"
BEGIN = "<!-- MAP:GENERATED BEGIN -->"
END = "<!-- MAP:GENERATED END -->"

KOTLIN = "kotlin"  # symbol table: name, kind, file:line, what it owns
JCEF = "jcef"      # the web app: load order, modules, public registrations, cascade order
FILES = "files"    # one row per document, from the file's own first heading

# Every directory that carries its own local map, and how its index is built. The list is the boundary: a
# directory absent from here gets no map, and a target whose SUBDIRECTORY is also listed stops at that
# subdirectory (so `ui` does not swallow `ui/jcef`).
TARGETS = [
    ("src/main/kotlin/dev/lain/claudejb/process", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/protocol", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/session", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/permission", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/diff", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/git", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/ui", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/ui/jcef", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/context", KOTLIN),
    ("src/main/kotlin/dev/lain/claudejb/settings", KOTLIN),
    ("src/main/resources/jcef", JCEF),
    ("src/test/kotlin/dev/lain/claudejb", KOTLIN),
    ("src/test/frontend", FILES),
    ("src/uiTest", KOTLIN),
    ("docs", FILES),
]

JCEF_HOST = ROOT / "src" / "main" / "kotlin" / "dev" / "lain" / "claudejb" / "ui" / "jcef" / "JcefHost.kt"

# --- Kotlin ------------------------------------------------------------------------------------------
# The same three patterns ReachabilityContractTest uses, in the same order of exclusions. Group 3 of each is
# a receiver dot: an extension is called on its receiver, not on its owner, so it is not indexed here either.
TOP_LEVEL_DECLARATION = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:internal |public |abstract |open |sealed |data |value |enum |annotation |inline |const )*"
    r"(class|object|interface|fun|val|var)\s+(?:<[^>]+>\s+)?([A-Za-z_]\w*)(\.?)"
)
MEMBER_DECLARATION = re.compile(
    r"^ {4}(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:internal |public |open |const |inline |suspend |operator |infix )*"
    r"(fun|val|var)\s+(?:<[^>]+>\s+)?([A-Za-z_]\w*)(\.?)"
)
SKIPPED_MODIFIER = re.compile(r"\b(private|override)\s")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')

# --- JavaScript --------------------------------------------------------------------------------------
# `var TX = (CC.transcript = CC.transcript || {})` — there is no module system in the page, so that object
# IS the interface between a family's files, and the alias is how every one of them spells it.
JS_NAMESPACE = re.compile(r"^\s*var\s+([A-Za-z_$][\w$]*)\s*=\s*\(\s*(CC\.[A-Za-z_$][\w$]*)\s*=")
# An assignment to a namespace member, at the start of a line. `=(?!=)` so a comparison is not an export; a
# commented-out one cannot match at all, since the line then starts with `/` or `*`.
JS_ASSIGNMENT = re.compile(r"^\s*([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\s*=(?!=)")
JS_APP_NAME = re.compile(r'"([\w-]+\.js)"')
CSS_PART = re.compile(r'"([\w-]+\.css)"')
JS_OWNS = re.compile(r"\bOwns:\s*(.+)")

MD_HEADING = re.compile(r"^#{1,6}\s+(.+)")
JS_HEADLINE = re.compile(r"^\s*(?://+|/\*+|\*+)\s*(.+)")

SENTENCE = re.compile(r"^(.*?[.!?])(?:\s|$)")
CELL_LIMIT = 120


# --- text helpers ------------------------------------------------------------------------------------


def cell(text: str) -> str:
    """One table cell: no newlines, no unescaped pipes, and short enough that the row still reads."""
    flat = " ".join(text.split()).replace("|", r"\|")
    if len(flat) <= CELL_LIMIT:
        return flat
    return flat[: CELL_LIMIT - 1].rsplit(" ", 1)[0] + " …"


def first_sentence(text: str) -> str:
    match = SENTENCE.match(" ".join(text.split()))
    return match.group(1) if match else text


def table(headers: list[str], rows: list[list[str]]) -> list[str]:
    if not rows:
        return ["_Nothing here yet._"]
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(row) + " |" for row in rows]
    return lines


# --- Kotlin ------------------------------------------------------------------------------------------


def code_of(raw: list[str]) -> list[str]:
    """The file's CODE, one entry per original line so line numbers survive: comment lines are blanked and
    single-line string literals are emptied.

    The reachability gate keeps a literal's template expressions, because an interpolated call really is a
    call. Here the question is narrower — a DECLARATION cannot live inside a string — so the literal goes
    entirely. The body of a multi-line raw string is left as it stands, exactly as that gate leaves it.
    """
    lines = []
    in_block_comment = False
    for line in raw:
        trimmed = line.lstrip()
        if in_block_comment:
            lines.append("")
            if "*/" in trimmed:
                in_block_comment = False
        elif trimmed.startswith("/*"):
            lines.append("")
            if "*/" not in trimmed:
                in_block_comment = True
        elif trimmed.startswith("*") or trimmed.startswith("//"):
            lines.append("")
        else:
            lines.append(STRING_LITERAL.sub('""', line).split("//")[0])
    return lines


def kdoc_summary(raw: list[str], index: int) -> str:
    """The first sentence of the KDoc attached to the declaration on line [index], or the empty string.

    Derived rather than written by hand: a column somebody types is a column that drifts from the symbol it
    describes, and this one is meant to say what the symbol OWNS, never how it works.
    """
    end = index - 1
    while end >= 0 and (not raw[end].strip() or raw[end].lstrip().startswith("@")):
        end -= 1
    if end < 0 or not raw[end].rstrip().endswith("*/"):
        return ""
    start = end
    while start >= 0 and not raw[start].lstrip().startswith("/*"):
        start -= 1
    if start < 0 or not raw[start].lstrip().startswith("/**"):
        return ""
    body = " ".join(strip_kdoc(line) for line in raw[start : end + 1])
    return first_sentence(body)


def strip_kdoc(line: str) -> str:
    text = line.strip()
    if text.startswith("/**"):
        text = text[3:]
    elif text.startswith("*"):
        text = text[1:]
    if text.endswith("*/"):
        text = text[:-2]
    return text.strip()


def top_level_declarations(code: list[str]) -> list[tuple[int, str, str]]:
    """Every top-level declaration as `(line index, kind, name)`, in source order."""
    found = []
    for index, line in enumerate(code):
        if not line or line[0].isspace() or line.startswith("private "):
            continue
        match = TOP_LEVEL_DECLARATION.match(line)
        if match and not match.group(3):
            found.append((index, match.group(1), match.group(2)))
    return found


def object_members(code: list[str], start: int, stop: int) -> list[tuple[int, str, str]]:
    """The members a top-level `object` declares between [start] and [stop], as `(line index, kind, name)`."""
    members = []
    for index in range(start + 1, stop):
        line = code[index]
        if SKIPPED_MODIFIER.search(line):
            continue
        match = MEMBER_DECLARATION.match(line)
        if match and not match.group(3):
            members.append((index, match.group(1), match.group(2)))
    return members


def kotlin_rows(target: Path, files: list[Path]) -> list[list[str]]:
    rows = []
    for path in files:
        raw = path.read_text(encoding="utf-8").splitlines()
        code = code_of(raw)
        where = path.relative_to(target).as_posix()
        found = top_level_declarations(code)
        for position, (index, kind, name) in enumerate(found):
            rows.append([f"`{name}`", kind, f"`{where}:{index + 1}`", cell(kdoc_summary(raw, index))])
            if kind != "object":
                continue
            stop = found[position + 1][0] if position + 1 < len(found) else len(code)
            for member_index, member_kind, member in object_members(code, index, stop):
                rows.append(
                    [
                        f"`{name}.{member}`",
                        member_kind,
                        f"`{where}:{member_index + 1}`",
                        cell(kdoc_summary(raw, member_index)),
                    ]
                )
    return rows


def kotlin_section(target: Path, files: list[Path]) -> list[str]:
    return [
        "## Symbols — go to the line, the code is the documentation",
        "",
        "Top-level declarations and the members of top-level `object`s. `private` and `override` are not",
        "indexed, and neither are extensions: they are called on their receiver, not on their owner.",
        "",
        *table(
            ["Symbol", "Kind", "Where", "Owns"],
            kotlin_rows(target, [f for f in files if f.suffix == ".kt"]),
        ),
    ]


# --- the JCEF web app --------------------------------------------------------------------------------


def host_list(declaration: str, entry: re.Pattern[str]) -> list[str]:
    """The entries of a `listOf(…)` in `JcefHost`, in the order it declares them.

    Both lists read this way are ORDERS, not sets — `appNames` is the load order the modules meet each other
    in, `CSS_PARTS` the cascade order the rules override each other in — so each is copied from the one place
    that decides it. Globbing the directory would return the same files in whatever order the filesystem
    offered, which means nothing and would look authoritative anyway, and it would go on listing a file the
    host had already dropped.
    """
    source = JCEF_HOST.read_text(encoding="utf-8")
    start = source.find(f"val {declaration} = listOf(")
    if start < 0:
        raise SystemExit(f"gen-projectmap: could not find {declaration} in {JCEF_HOST}")
    entries = entry.findall(source[start : source.index(")", start)])
    if not entries:
        raise SystemExit(f"gen-projectmap: {declaration} in {JCEF_HOST} listed nothing")
    return entries


def module_owns(raw: list[str]) -> str:
    """What a module says it owns: its `Owns:` header line, else the subject its header opens with."""
    header = []
    for line in raw:
        header.append(line)
        if "*/" in line:
            break
    for line in header:
        match = JS_OWNS.search(line)
        if match:
            return first_sentence(match.group(1).strip())
    for line in header:
        if "—" in line:
            return first_sentence(line.split("—", 1)[1].strip())
    return ""


def module_registrations(raw: list[str]) -> list[tuple[str, int]]:
    """Every assignment onto `cc`, `CC` or one of the family namespaces this module aliases, in source order.

    A family's namespace object is its interface, so the state it shares counts as much as the functions it
    exports. Names starting with `_` do not: those are the module's own scratch space.
    """
    aliases = {"cc": "cc", "CC": "CC"}
    for line in raw:
        match = JS_NAMESPACE.match(line)
        if match:
            aliases[match.group(1)] = match.group(2)
    found = []
    for index, line in enumerate(raw):
        match = JS_ASSIGNMENT.match(line)
        if match and match.group(1) in aliases and not match.group(2).startswith("_"):
            found.append((f"{aliases[match.group(1)]}.{match.group(2)}", index + 1))
    return found


def jcef_section(target: Path, _files: list[Path]) -> list[str]:
    names = host_list("appNames", JS_APP_NAME)
    parts = host_list("CSS_PARTS", CSS_PART)
    for part in parts:
        if not (target / "css" / part).exists():
            raise SystemExit(f"gen-projectmap: {part} is in JcefHost.CSS_PARTS but not in {target}/css")
    modules, registrations, seen = [], [], set()
    for name in names:
        path = target / name
        if not path.exists():
            raise SystemExit(f"gen-projectmap: {name} is in JcefHost.appNames but {path} does not exist")
        raw = path.read_text(encoding="utf-8").splitlines()
        modules.append([f"`{name}`", cell(module_owns(raw))])
        for registration, line in module_registrations(raw):
            if registration in seen:
                continue
            seen.add(registration)
            registrations.append([f"`{registration}`", f"`{name}:{line}`"])
    return [
        "## Load order — `JcefHost.appNames`, and it is a contract",
        "",
        "There is no module system in the page: each file is its own hash-pinned `<script>` and they meet",
        "through `window.cc` / `window.CC`, so a file that reads another's namespace at load time must come",
        "after it. Changing this order means changing `JcefHost.appNames`, which is where it is decided.",
        "",
        *[f"{position}. `{name}`" for position, name in enumerate(names, start=1)],
        "",
        "## Modules — one subject each",
        "",
        *table(["Module", "Owns"], modules),
        "",
        "## Registrations — the page's public surface",
        "",
        "`cc.*` is what Kotlin calls. `CC.*` is what the modules share. Listed at the line that creates it.",
        "",
        *table(["Registration", "Where"], registrations),
        "",
        "## Cascade order — `JcefHost.CSS_PARTS`, and it is a contract too",
        "",
        "`css/` is one stylesheet, split into parts and concatenated into a single `<style>` block in",
        "this order. Order decides which rule wins at equal specificity, so it is a contract exactly",
        "like the load order above, and it is changed in the same place: `JcefHost.CSS_PARTS`.",
        "",
        *[f"{position}. `css/{part}`" for position, part in enumerate(parts, start=1)],
    ]


# --- documents ---------------------------------------------------------------------------------------


def document_summary(path: Path) -> str:
    """A document's own first heading (Markdown) or the first line of its header comment (JavaScript)."""
    pattern = MD_HEADING if path.suffix == ".md" else JS_HEADLINE
    for line in path.read_text(encoding="utf-8").splitlines()[:20]:
        match = pattern.match(line)
        if match and match.group(1).strip():
            return first_sentence(match.group(1).strip())
    return ""


def files_section(target: Path, files: list[Path]) -> list[str]:
    rows = [
        [f"`{path.relative_to(target).as_posix()}`", cell(document_summary(path))]
        for path in files
        if path.suffix in (".md", ".js")
    ]
    return [
        "## Files — what each one holds, from its own first heading",
        "",
        *table(["File", "Holds"], rows),
    ]


SECTIONS = {KOTLIN: kotlin_section, JCEF: jcef_section, FILES: files_section}


# --- the maps themselves -----------------------------------------------------------------------------


def sources_of(target: Path, nested: list[Path]) -> list[Path]:
    """Every file the target owns: its own tree, minus whatever belongs to a target nested inside it."""
    found = [path for path in target.rglob("*") if path.is_file() and path.name != MAP_NAME]
    return sorted(
        (path for path in found if not any(path.is_relative_to(other) for other in nested)),
        key=lambda path: path.relative_to(target).as_posix(),
    )


def block_of(target: Path, kind: str, nested: list[Path]) -> str:
    body = [
        "<!-- Generated by scripts/gen-projectmap.py. Everything between these markers is overwritten on the",
        "     next run; the prose outside them is not. `./gradlew checkProjectMap` fails when they disagree. -->",
        "",
        *SECTIONS[kind](target, sources_of(target, nested)),
    ]
    return "\n" + "\n".join(body).rstrip() + "\n\n"


def skeleton(target: Path) -> str:
    """What an unwritten map looks like: the marker pair, and the sections nobody has filled in yet.

    Not a bootstrap of its own. [ensured] splices the generated block into this exactly as it splices one
    into a map that already carries prose, so a target with no file is one more divergence, never a case
    somebody has to remember to run.
    """
    root = Path(*[".."] * len(target.relative_to(ROOT).parts)) / MAP_NAME
    return "\n".join(
        [
            f"# Map of `{target.relative_to(ROOT).as_posix()}/`",
            "",
            f"> Part of the distributed map. **Root: `{root.as_posix()}`** — repository-wide commands,",
            "> invariants and the index of every other subdirectory map live there.",
            "",
            "## What lives here",
            "",
            "_Not written yet._",
            "",
            BEGIN,
            END,
            "",
            "## Conventions here",
            "",
            "_Not written yet._",
            "",
            "## Minefields here",
            "",
            "_Not written yet._",
            "",
            "## Neighbours",
            "",
            "_Not written yet._",
            "",
        ]
    )


def with_block(text: str, block: str, path: Path) -> str:
    """[text] with its generated block current: spliced between the markers, or appended when it has none.

    Every byte outside the markers is preserved either way. The one refusal is a MALFORMED pair — unpaired,
    out of order or duplicated — because there the end of the hand-written prose is genuinely ambiguous, and
    never guessing at that is this script's whole promise.
    """
    begins, ends = text.count(BEGIN), text.count(END)
    if begins == 0 and ends == 0:
        return text.rstrip("\n") + "\n\n" + BEGIN + block + END + "\n"
    if begins != 1 or ends != 1 or text.index(BEGIN) > text.index(END):
        raise SystemExit(
            f"gen-projectmap: {path} carries a malformed {BEGIN} … {END} pair — unpaired, out of order or "
            "duplicated. Refusing to touch it: the prose around those markers is hand-written and this "
            "script cannot tell where it ends."
        )
    head = text[: text.index(BEGIN) + len(BEGIN)]
    return head + block + text[text.index(END) :]


def ensured(target: Path, kind: str, nested: list[Path]) -> tuple[Path, str, str]:
    """The map's path, what is on disk (empty when there is no file yet) and what it should say.

    `current or skeleton(target)` is the entirety of the missing-file handling: a map with no bytes is a map
    whose prose has not been written, and it goes through the same splice as every other one.
    """
    path = target / MAP_NAME
    current = path.read_text(encoding="utf-8") if path.exists() else ""
    return path, current, with_block(current or skeleton(target), block_of(target, kind, nested), path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--check",
        action="store_true",
        help="diff against disk instead of writing, and exit 1 on any divergence",
    )
    args = parser.parse_args()

    targets = [(ROOT / path, kind) for path, kind in TARGETS]
    for target, _ in targets:
        if not target.is_dir():
            raise SystemExit(f"gen-projectmap: {target} is a target but does not exist")

    stale = []
    for target, kind in targets:
        nested = [other for other, _ in targets if other != target and other.is_relative_to(target)]
        path, current, wanted = ensured(target, kind, nested)
        name = path.relative_to(ROOT).as_posix()
        if current == wanted:
            print(f"{name}: unchanged")
            continue
        if not args.check:
            path.write_text(wanted, encoding="utf-8")
            print(f"{name}: {'updated' if current else 'created'}")
            continue
        stale.append(name)
        print("".join(difflib.unified_diff(
            current.splitlines(keepends=True),
            wanted.splitlines(keepends=True),
            fromfile=f"{name} (on disk)" if current else f"{name} (missing)",
            tofile=f"{name} (generated)",
        )), end="")

    if not stale:
        return 0
    print(
        f"gen-projectmap: {len(stale)} map(s) no longer match the sources they index "
        "— run `python3 scripts/gen-projectmap.py`",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
