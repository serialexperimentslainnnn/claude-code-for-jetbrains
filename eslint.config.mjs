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
      // use for the object. What IS a defect is a catch that does nothing, and that has its own rule below.
      // detekt draws the same line for Kotlin (EmptyCatchBlock flags the empty block, not the parameter name),
      // so the two languages in this repo now answer "did you swallow it?" the same way.
      'no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' },
      ],
      'no-empty': ['error', { allowEmptyCatch: false }],
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
