// The code of a source, without its prose.
//
// WHY IT EXISTS. `css-contract.test.js`, `bridge-contract.test.js` and `bridge-inbound.test.js` all answer
// their question by scanning source text for a shape: a class literal, a `window.cc.<name>`, a `type:` key.
// This repository documents by QUOTING the code a comment is about — `CLAUDE.md` says so about itself — so
// every one of those shapes appears in prose as often as in code, and a scan that cannot tell them apart is
// wrong in whichever direction that gate happens to read:
//
//   · `css-contract` gains a class the page never paints → it demands a rule for nothing. A false RED.
//   · `bridge-contract` gains a call the host never makes → it demands an implementation for nothing.
//   · `bridge-inbound` counts a prose mention as PROOF that something is called or sent → the dead method and
//     the unsent message type it was written to find both go quiet. A false GREEN, which is the bad one.
//
// ONE COPY, and that is the point of the file rather than a tidiness preference. The rule below has a known
// hole (see `startsARegex`), and a hole documented in one gate and forgotten in the other two is how a check
// silently becomes weaker than its neighbour.
//
// WHY NOT `replace(/\/\*[\s\S]*?\*\//g, '')`. Because a regex has no idea it is inside quotes, and it would
// eat the inside of a STRING. That is not a nicety here: the host's calls into the page are written as Kotlin
// string literals — `exec("window.cc.trimRows && …")` — and the page's sources carry URLs (`https://…`) and
// paths. A strip that swallowed those would turn every false positive above into a false NEGATIVE, and a gate
// that stays green while protecting nothing is worse than the noise it was cleaning up.
//
// So this walks the source as tokens: strings and regex literals are copied through VERBATIM and only
// comments are replaced. It covers both languages the gates read — JavaScript (template literals, regex
// literals) and Kotlin (`"""` raw strings, KDoc) — because the same call sites are read out of both.
//
// LINE STRUCTURE IS PRESERVED. A block comment leaves its newlines behind, so `text.split('\n')` still lands
// on the same line numbers: `bridge-contract` reports a call as `file:line`, and a lexer that silently
// renumbered a file would make every one of those citations point at the wrong place.

/**
 * Whether a `/` here opens a regex literal rather than dividing — answered by what came before it.
 *
 * The two mistakes are NOT symmetric, and that is what this set is sized against. Reading a division as a
 * regex costs nothing: the span is emitted verbatim either way, so at worst a comment inside it survives, and
 * a false positive is loud. Reading a regex as a division is the one that can delete a line, so the set is
 * generous. What it leaves out is the shape valid JS almost never has: a literal immediately after `)` or
 * after an identifier. Widening it to those would buy that case by mis-reading ordinary arithmetic, which is
 * how the false positives come back. Kotlin has no regex literals, so none of this can fire on a `.kt`.
 */
const BEFORE_A_REGEX = new Set('(,=:[!&|?{};+-*%^~<>'.split(''));
const KEYWORD_BEFORE_A_REGEX = /\b(return|typeof|case|in|of|new|delete|void|instanceof|yield|await)$/;

function startsARegex(last, out) {
  return last === '' || BEFORE_A_REGEX.has(last) || KEYWORD_BEFORE_A_REGEX.test(out.slice(-12));
}

/** Where the literal opened at [at] ends, escapes honoured. A quoted string never crosses a line break. */
function endOfString(src, at) {
  const quote = src[at];
  for (let i = at + 1; i < src.length; i++) {
    if (src[i] === '\\') i++;
    else if (src[i] === quote) return i + 1;
    else if (src[i] === '\n' && quote !== '`') return i;
  }
  return src.length;
}

/** Where the regex literal opened at [at] ends: `\/` is escaped and a `/` inside a character class is not. */
function endOfRegex(src, at) {
  let inClass = false;
  for (let i = at + 1; i < src.length; i++) {
    const c = src[i];
    if (c === '\\') i++;
    else if (c === '\n')
      return i; // it was not a regex after all; a literal never crosses a line
    else if (c === '[') inClass = true;
    else if (c === ']') inClass = false;
    else if (c === '/' && !inClass) return i + 1;
  }
  return src.length;
}

/**
 * The source with its comments replaced by blanks, and everything else — strings included — left alone.
 *
 * Memoised on the exact text, because the gates ask the same file the same question many times: `uncalled`
 * asks every app module whether it calls each registered method, once per method. The answer depends on
 * nothing but the string, so the cache cannot go stale, and it is what lets every reader take RAW text and
 * strip it itself — one contract everywhere instead of some callers passing code and some passing prose.
 *
 * It is also IDEMPOTENT: code with no comments in it walks out unchanged, so a caller that strips defensively
 * over an input a reader already stripped costs a map lookup and changes nothing.
 */
const stripped = new Map();

function stripComments(src) {
  const hit = stripped.get(src);
  if (hit !== undefined) return hit;
  const out = walk(src);
  stripped.set(src, out);
  return out;
}

function walk(src) {
  let out = '';
  let last = ''; // the last significant character kept — what tells a regex literal from a division
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const d = src[i + 1];
    // Comments FIRST, and that is the language's own rule rather than an ordering choice: a regex literal may
    // be neither empty nor start with `*`, so `//` and `/*` are never anything else.
    if (c === '/' && d === '/') {
      while (i < src.length && src[i] !== '\n') i++;
      out += ' ';
    } else if (c === '/' && d === '*') {
      const end = src.indexOf('*/', i + 2);
      const body = src.slice(i, end < 0 ? src.length : end + 2);
      // The newlines stay so the line numbering does not move (see the header). Kotlin nests block comments
      // and this does not: the tail of a nested one is left as code, which can only leave prose un-stripped.
      out += ' ' + '\n'.repeat((body.match(/\n/g) || []).length);
      i = end < 0 ? src.length : end + 2;
    } else if (src.startsWith('"""', i)) {
      // Kotlin's raw string: it spans lines and holds anything, `//` included. Treated as one opaque literal,
      // which is also why it is tested before the single-quote branch below.
      const end = src.indexOf('"""', i + 3);
      const stop = end < 0 ? src.length : end + 3;
      out += src.slice(i, stop);
      last = '"';
      i = stop;
    } else if (c === '"' || c === "'" || c === '`') {
      const end = endOfString(src, i);
      out += src.slice(i, end);
      last = src[end - 1];
      i = end;
    } else if (c === '/' && startsARegex(last, out)) {
      const end = endOfRegex(src, i);
      out += src.slice(i, end);
      last = '/';
      i = end;
    } else {
      out += c;
      if (!/\s/.test(c)) last = c;
      i++;
    }
  }
  return out;
}

module.exports = { stripComments };
