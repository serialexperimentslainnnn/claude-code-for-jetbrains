import js from '@eslint/js';
import globals from 'globals';
import prettier from 'eslint-config-prettier';

/**
 * ESLint for the JCEF web app — the ~3.6k lines of JavaScript that SHIP INSIDE the plugin jar.
 *
 * Until 5.0.0 this code had no linter at all, which mattered more here than it would in most projects: it runs
 * inside an embedded Chromium under a hash-pinned CSP, where a mistake surfaces as a blank panel in a user's
 * IDE rather than as a stack trace anyone sees.
 *
 * The vendored libraries (marked, DOMPurify, highlight.js) are deliberately NOT linted: they are third-party
 * minified bundles we redistribute unmodified, so a finding in them is not ours to fix and fixing it would fork
 * a dependency. Their licences ride along in META-INF (see THIRD-PARTY-NOTICES.md).
 */
export default [
  {
    // Never lint what we did not write, or build output.
    ignores: [
      'src/main/resources/jcef/marked.min.js',
      'src/main/resources/jcef/purify.min.js',
      'src/main/resources/jcef/highlight.min.js',
      'build/**',
      'node_modules/**',
      // `runIde` unpacks a sandbox IDE here, and the bundled Database plugin ships its data extractors as
      // `.js` files that are not JavaScript we own — they run inside the IDE's own scripting host against
      // injected globals (`com`, `OUT`, `COLUMNS`, `ROWS`, …), so `no-undef` fires 12 times on them. The
      // directory is gitignored, so CI never sees it; without this line `npm run lint` is red on every
      // workstation that has ever run `runIde`, which is every workstation.
      '.intellijPlatform/**',
    ],
  },

  js.configs.recommended,

  {
    // The shipped web app. Classic scripts (no bundler, no modules): shell.html loads each file with a
    // <script> tag whose sha256 is pinned in the CSP, and they communicate through `window.cc`.
    files: ['src/main/resources/jcef/*.js'],
    languageOptions: {
      ecmaVersion: 2019,
      sourceType: 'script',
      globals: {
        ...globals.browser,
        // The Kotlin↔JS bridge surface. `cc` is the method registry the host calls into; `CC` is the helper
        // namespace the modules share; `__ccSend` is the JBCefJSQuery callback the host injects.
        cc: 'writable',
        CC: 'writable',
        __ccSend: 'readonly',
        // Vendored libraries, loaded before the app modules by shell.html.
        marked: 'readonly',
        DOMPurify: 'readonly',
        hljs: 'readonly',
      },
    },
    rules: {
      // ── The rules that exist because of the CSP ────────────────────────────────────────────────────────
      // shell.html ships `default-src 'none'` with hash-pinned scripts and NO 'unsafe-eval'. Chromium will
      // refuse these at runtime — in a user's IDE, silently, as a panel that stops updating. The linter is
      // where that becomes a build failure instead of a bug report.
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-script-url': 'error',

      // ── Correctness ───────────────────────────────────────────────────────────────────────────────────
      eqeqeq: ['error', 'smart'],
      'no-var': 'off', // ES2019 target, and the existing code is written with `var` throughout
      'no-implicit-globals': 'error',

      // caughtErrors: 'none' — deliberate, and it mirrors the Kotlin side rather than being a concession.
      // ESLint 9 began counting an unused `catch (e)` binding as an unused variable. But a vestigial binding is
      // not a defect: `try { … } catch (e) { fallback() }` handles the failure perfectly well and simply has no
      // use for the object.
      'no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' },
      ],
      // allowEmptyCatch: true. This asked for content in a catch block, and a comment counted as content — so
      // the ~22 deliberate best-effort catches in the page satisfied it with `/* ignore */` and nothing else.
      // With the comments gone the rule fires on all of them at once, and the choice is to say so here rather
      // than to plant a statement with no effect at each site to keep the gate quiet. What the rule no longer
      // asks, review has to: an empty catch is still a swallowed failure, and it is only correct where the
      // fallback IS "carry on".
      'no-empty': ['error', { allowEmptyCatch: true }],
    },
  },

  {
    // The vitest suite: Node + jsdom, ES modules, and it deliberately reaches into browser globals.
    files: ['src/test/frontend/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        ...globals.node,
        ...globals.browser,
        // vitest injects these (the suite runs with `globals: true`), so they are not imported.
        describe: 'readonly',
        it: 'readonly',
        test: 'readonly',
        expect: 'readonly',
        beforeEach: 'readonly',
        afterEach: 'readonly',
        beforeAll: 'readonly',
        afterAll: 'readonly',
        vi: 'readonly',
      },
    },
  },

  {
    // Build-time config at the repo root: CommonJS on Node.
    files: ['*.config.js', '*.config.cjs'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'commonjs',
      globals: { ...globals.node },
    },
  },

  // Last: turns off every rule that would fight the formatter.
  prettier,
];
