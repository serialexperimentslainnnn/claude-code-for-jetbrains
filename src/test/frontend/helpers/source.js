const BEFORE_A_REGEX = new Set('(,=:[!&|?{};+-*%^~<>'.split(''));
const KEYWORD_BEFORE_A_REGEX = /\b(return|typeof|case|in|of|new|delete|void|instanceof|yield|await)$/;

function startsARegex(last, out) {
  return last === '' || BEFORE_A_REGEX.has(last) || KEYWORD_BEFORE_A_REGEX.test(out.slice(-12));
}

function endOfString(src, at) {
  const quote = src[at];
  for (let i = at + 1; i < src.length; i++) {
    if (src[i] === '\\') i++;
    else if (src[i] === quote) return i + 1;
    else if (src[i] === '\n' && quote !== '`') return i;
  }
  return src.length;
}

function endOfRegex(src, at) {
  let inClass = false;
  for (let i = at + 1; i < src.length; i++) {
    const c = src[i];
    if (c === '\\') i++;
    else if (c === '\n') return i;
    else if (c === '[') inClass = true;
    else if (c === ']') inClass = false;
    else if (c === '/' && !inClass) return i + 1;
  }
  return src.length;
}

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
  let last = '';
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const d = src[i + 1];
    if (c === '/' && d === '/') {
      while (i < src.length && src[i] !== '\n') i++;
      out += ' ';
    } else if (c === '/' && d === '*') {
      const end = src.indexOf('*/', i + 2);
      const body = src.slice(i, end < 0 ? src.length : end + 2);
      out += ' ' + '\n'.repeat((body.match(/\n/g) || []).length);
      i = end < 0 ? src.length : end + 2;
    } else if (src.startsWith('"""', i)) {
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
