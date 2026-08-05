# Third-party notices

Claude Code Native is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

This file lists the third-party components **redistributed inside the published plugin artifact**
(`claude-code-native-<version>.zip`) and the notices their licenses require to be preserved on
redistribution. It covers what is actually shipped — not the project's development dependencies,
which are never distributed.

Every entry below was verified by reading the license text of the **exact version that ships**, not
the package manifest or a badge (see `docs/adr/0002-third-party-attribution.md` for why that
distinction matters).

Last verified: 2026-08-05.

---

## Bundled inside `lib/claude-code-native-<version>.jar`

These are vendored into the plugin's embedded web UI under `jcef/` and are served to the JCEF
browser at runtime. They are redistributed verbatim, unmodified.

### marked — 12.0.0
- **License:** MIT (`SPDX-License-Identifier: MIT`)
- **Copyright:** Copyright (c) 2011-2024, Christopher Jeffrey (https://github.com/chjj/)
- **Project:** https://github.com/markedjs/marked
- **Full text:** `LICENSES/MIT.txt`

### DOMPurify — 3.0.11
- **License:** `Apache-2.0 OR MPL-2.0` — dual-licensed.
- **License chosen by this project: Apache-2.0.**
  A dual `OR` license is a choice the redistributor must make and record; leaving it unstated is an
  unmade decision. Apache-2.0 is selected because it is already the license of another component in
  this artifact (kotlinx.serialization), so the artifact carries one fewer distinct license text, and
  because Apache-2.0 grants patent rights explicitly whereas MPL-2.0's grant is narrower in scope.
  MPL-2.0's per-file copyleft would also attach obligations if the file were ever modified — it is
  not, but choosing Apache-2.0 removes the question entirely.
- **Copyright:** Copyright (c) Cure53 and other contributors
- **Project:** https://github.com/cure53/DOMPurify
- **Full text:** `LICENSES/Apache-2.0.txt`

### highlight.js — 11.9.0
- **License:** BSD-3-Clause (`SPDX-License-Identifier: BSD-3-Clause`)
- **Copyright:** Copyright (c) 2006, Ivan Sagalaev. All rights reserved.
- **Project:** https://github.com/highlightjs/highlight.js
- **Full text:** `LICENSES/BSD-3-Clause.txt`
- **Note:** a curated subset build (~35 languages), redistributed unmodified.

---

## Shipped as separate jars in `lib/`

### kotlinx.serialization (`kotlinx-serialization-core-jvm`, `kotlinx-serialization-json-jvm`) — 1.7.3
- **License:** Apache-2.0 (`SPDX-License-Identifier: Apache-2.0`)
- **Copyright:** Copyright 2017-2024 JetBrains s.r.o. and Kotlin Programming Language contributors
- **Project:** https://github.com/Kotlin/kotlinx.serialization
- **Full text:** `LICENSES/Apache-2.0.txt`
- **Verified:** the published jars carry no `META-INF/LICENSE`, so the license was read from the
  project's `LICENSE.txt` at source rather than inferred from the artifact.

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
