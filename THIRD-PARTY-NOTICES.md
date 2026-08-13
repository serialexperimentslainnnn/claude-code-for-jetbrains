# Third-party notices

Claude Code Native is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

This file lists the third-party components **redistributed inside the published plugin artifact**
(`claude-code-native-<version>.zip`) and the notices their licenses require to be preserved on
redistribution. It covers what is actually shipped — not the project's development dependencies,
which are never distributed.

Every entry below was verified by reading the upstream `LICENSE` file of the **exact version that
ships** — not the package manifest, not a badge, and **not the copyright banner in the minified
file**. The banner is a marketing line the bundler writes; the `LICENSE` is the grant. Where the two
disagree, the `LICENSE` wins, and for marked they do disagree (see the note on it).

One caveat that rule does not cover, and DOMPurify is the live case: **a `LICENSE` that asserts no
copyright at all does not override a banner that does**. At the version that ships, DOMPurify's
`LICENSE` is the bare Apache-2.0 text with no copyright header, so the banner is the only copyright
notice upstream still asserts and it is the one reproduced here. "Read the `LICENSE`, not the banner"
means the banner does not *outrank* the grant; it does not mean the banner is worthless when the
grant is silent.

The vendored versions below are the ones read out of the shipped files themselves (`marked`'s and
`highlight.js`'s embedded version strings, DOMPurify's `version` constant), not the ones named in a
banner. `marked.min.js` and `purify.min.js` were additionally confirmed **byte-identical** to the
upstream published `dist` for their stated version, which is what substantiates "redistributed
verbatim, unmodified" below; `highlight.min.js` is a curated subset build and so matches no upstream
artifact by construction.

The license texts referenced as `LICENSES/…` live at the repository root during development and are
packaged into the artifact under `META-INF/licenses/` (see `build.gradle.kts`), alongside this file
at `META-INF/THIRD-PARTY-NOTICES.md` and the project's own license at `META-INF/LICENSE`.

The inventory is **complete against the artifact, not against the source tree**: the only files in
`claude-code-native-<version>.zip` under `claude-code-native/lib/` are the plugin's own jar (which
carries the vendored web assets), the two kotlinx.serialization jars listed below, and the generated
`searchableOptions` jar. Everything with a third-party license in that list has an entry here.

Last verified: 2026-08-11, against the upstream `LICENSE` files at the pinned tags
(`markedjs/marked@v12.0.0`, `cure53/DOMPurify@3.4.13`, `highlightjs/highlight.js@11.11.2`,
`Kotlin/kotlinx.serialization@v1.7.3`).

---

## Bundled inside `lib/claude-code-native-<version>.jar`

These are vendored into the plugin's embedded web UI under `jcef/` and are served to the JCEF
browser at runtime. Each is redistributed exactly as published upstream, with its license banner
intact and no edit of our own — verbatim for marked and DOMPurify, and for highlight.js the upstream
subset build as generated (see its entry).

### marked — 12.0.0
- **License:** `MIT AND BSD-3-Clause` — marked itself is MIT; its `LICENSE.md` additionally
  reproduces the notice of the original **Markdown** (John Gruber, 2004), which is a BSD-3-Clause
  form license. Both are conditions of redistributing the file, so both are reproduced here.
- **Copyright:**
  - Copyright (c) 2018+, MarkedJS (https://github.com/markedjs/)
  - Copyright (c) 2011-2018, Christopher Jeffrey (https://github.com/chjj/)
  - Copyright © 2004, John Gruber (the Markdown notice)
- **Project:** https://github.com/markedjs/marked
- **Full text:** `LICENSES/MIT.txt` (marked) and `LICENSES/BSD-3-Clause-Markdown.txt` (Markdown)
- **Note:** the banner inside `marked.min.js` reads *"Copyright (c) 2011-2024, Christopher Jeffrey"*
  — a single line that names neither MarkedJS nor Gruber and states a date range that does not
  appear in the license. `LICENSE.md` at `v12.0.0` is the grant and is what is reproduced above.

### DOMPurify — 3.4.13
- **License:** `MPL-2.0 OR Apache-2.0` — dual-licensed, as upstream's own `package.json` states it
  verbatim at this tag. At `3.4.13` the two grants live in two files: `LICENSE` carries the bare
  Apache-2.0 text and `LICENSE-MPL` carries the MPL-2.0 text.
- **License chosen by this project: Apache-2.0.**
  A dual `OR` license is a choice the redistributor must make and record; leaving it unstated is an
  unmade decision. Apache-2.0 is selected because it is already the license of another component in
  this artifact (kotlinx.serialization), so the artifact carries one fewer distinct license text, and
  because Apache-2.0 grants patent rights explicitly whereas MPL-2.0's grant is narrower in scope.
  MPL-2.0's per-file copyleft would also attach obligations if the file were ever modified — it is
  not, but choosing Apache-2.0 removes the question entirely. Because the choice is Apache-2.0, the
  MPL-2.0 text is deliberately **not** carried in `LICENSES/`.
- **Copyright:** Copyright (c) Cure53 and other contributors
  — and, for the releases that asserted it in the license header: Copyright 2023 Dr.-Ing. Mario
  Heiderich, Cure53.
- **Project:** https://github.com/cure53/DOMPurify
- **Full text:** `LICENSES/Apache-2.0.txt`
- **Note — this entry is the exception to the "read the `LICENSE`, not the banner" rule, and it is
  worth stating why.** Up to and including `3.0.11`, DOMPurify's `LICENSE` opened with a header
  naming the author (*"DOMPurify / Copyright 2023 Dr.-Ing. Mario Heiderich, Cure53"*) followed by the
  dual-license statement, and that named individual was the notice to preserve. At `3.4.13` that
  header is **gone**: `LICENSE` is the unmodified Apache-2.0 boilerplate, whose only copyright line
  is the appendix's unfilled `Copyright {yyyy} {name of copyright owner}` placeholder. So the sole
  copyright notice upstream still asserts is the banner inside `purify.min.js` — *"(c) Cure53 and
  other contributors"* — which the vendored file carries intact, as Apache-2.0 §4(c) requires. Both
  forms are reproduced above rather than picking one, because dropping the named form would discard a
  notice that upstream did assert, and it costs nothing to keep.
- **Verified:** the DOMPurify repository publishes **no `NOTICE` file** at tag `3.4.13`, so having
  chosen Apache-2.0 there is nothing further to propagate under Apache-2.0 §4(d).

### highlight.js — 11.11.2
- **License:** BSD-3-Clause (`SPDX-License-Identifier: BSD-3-Clause`)
- **Copyright:** Copyright (c) 2006, Ivan Sagalaev. All rights reserved.
- **Project:** https://github.com/highlightjs/highlight.js
- **Full text:** `LICENSES/BSD-3-Clause.txt` — byte-identical to the upstream `LICENSE` at this tag.
- **Note:** a curated subset build (37 bundled grammars), redistributed as built. The banner inside
  `highlight.min.js` reads *"(c) 2006-2026 Josh Goebel &lt;hello@joshgoebel.com&gt; and other
  contributors"*, but the `LICENSE` at this tag still names only Ivan Sagalaev — so the copyright
  above is the license's, not the banner's, and it has not changed across the versions vendored here.

---

## Shipped as separate jars in `lib/`

### kotlinx.serialization (`kotlinx-serialization-core-jvm`, `kotlinx-serialization-json-jvm`) — 1.7.3
- **License:** Apache-2.0 (`SPDX-License-Identifier: Apache-2.0`)
- **Copyright:** Copyright 2017-2024 JetBrains s.r.o.
- **Project:** https://github.com/Kotlin/kotlinx.serialization
- **Full text:** `LICENSES/Apache-2.0.txt`
- **Verified:** the published jars carry no `META-INF/LICENSE` **and no `META-INF/NOTICE`** (checked
  in `kotlinx-serialization-core-jvm-1.7.3.jar` and `-json-jvm-1.7.3.jar`), so the license was read
  from the project's `LICENSE.txt` at tag `v1.7.3` rather than inferred from the artifact. That file
  is the bare Apache-2.0 text with no copyright line appended; the copyright above is the one the
  project's own source headers carry (`Copyright 2017-<year> JetBrains s.r.o.`, latest year 2024).
  The repository publishes **no `NOTICE` file**, so Apache-2.0 §4(d) adds no obligation here.

---

## Not redistributed

The following are used during development or referenced as documentation and are **not** part of the
published artifact, so they create no redistribution obligation here:

- **`@anthropic-ai/claude-agent-sdk`** — kept as a protocol reference only (`node_modules/`), never
  bundled. The plugin speaks the `claude` binary's wire protocol directly.
- **vitest, jsdom, commitlint** — development and test tooling.
- **The `claude` CLI itself** — a separate program the user installs and licenses independently. The
  plugin executes it; it does not redistribute it.
- **The IntelliJ Platform** — provided by the host IDE at runtime, not bundled.
