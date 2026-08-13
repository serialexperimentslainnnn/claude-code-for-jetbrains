/*
 * app-core-markdown.js — Markdown rendering and code-block chrome.
 *
 * One subject: turning untrusted model text into safe HTML (marked → DOMPurify) and giving every code
 * block the same head bar (language label + Copy) and syntax highlighting. Split out of app-core.js;
 * loads right after it and only extends window.CC.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});

  // ---------------------------------------------------------------------------
  // markdown(text): marked.parse → DOMPurify.sanitize → decorate code blocks.
  // Returns a safe HTML string. Code-block decoration (.code-head + Copy +
  // hljs) is applied to a detached fragment, then serialized back out.
  // ---------------------------------------------------------------------------
  CC.markdown = function (text) {
    if (text === null || text === undefined) return '';
    var src = String(text);
    var raw;
    try {
      // breaks:true → single newlines render as <br>, so multi-line prompts/replies keep their
      // line breaks instead of being collapsed into one run by standard Markdown.
      var mdOpts = { breaks: true, gfm: true };
      raw =
        typeof window.marked !== 'undefined' && window.marked
          ? typeof window.marked.parse === 'function'
            ? window.marked.parse(src, mdOpts)
            : window.marked(src, mdOpts)
          : CC.escape(src);
    } catch (e) {
      raw = CC.escape(src);
    }

    var clean;
    try {
      clean =
        typeof window.DOMPurify !== 'undefined' && window.DOMPurify
          ? window.DOMPurify.sanitize(raw, {
              ADD_ATTR: ['target'],
              FORBID_ATTR: ['style'],
              // Default safe schemes + our internal jb: jump-to-code links + data:image/ (inline images;
              // data:text/html stays blocked). Anything else (file:/javascript:/data:text…) is stripped.
              ALLOWED_URI_REGEXP:
                /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|jb):|data:image\/|[^a-z]|[a-z+.-]+(?:[^a-z+.:-]|$))/i,
            })
          : raw;
    } catch (e2) {
      clean = raw;
    }

    // Decorate code blocks in a detached container.
    try {
      var holder = document.createElement('div');
      holder.innerHTML = clean;
      decorateCodeBlocks(holder);
      return holder.innerHTML;
    } catch (e3) {
      return clean;
    }
  };

  // Shared code-block decoration so callers can re-run it on live DOM if needed.
  function decorateCodeBlocks(root) {
    if (!root) return;
    var blocks = root.querySelectorAll('pre > code');
    for (var i = 0; i < blocks.length; i++) {
      decorateOneCodeBlock(blocks[i]);
    }
  }

  // Decorates a SINGLE `pre > code` pair with the code-head bar (language label + Copy button) and
  // syntax highlighting. Extracted from decorateCodeBlocks so a caller that already has one specific
  // <code> in hand (e.g. a tool-output block built by hand, not parsed from markdown) can reuse the exact
  // same chrome — including the Copy button, which needs no per-node listener since the click/keyboard is
  // handled by the single delegated handler in app-core.js (works on any live `.code-head .copy`, wherever
  // it came from).
  // Idempotent: a `pre` already decorated (data-cc-decorated="1") is left untouched on a later call.
  function decorateOneCodeBlock(code) {
    var pre = code && code.parentNode;
    if (!pre || pre.getAttribute('data-cc-decorated') === '1') return;
    pre.setAttribute('data-cc-decorated', '1');

    // Derive language from the `language-xxx` class hljs/marked emit; absent one, label stays generic.
    var lang = '';
    var cls = (code.className || '').split(/\s+/);
    for (var c = 0; c < cls.length; c++) {
      if (cls[c].indexOf('language-') === 0) {
        lang = cls[c].slice('language-'.length);
        break;
      }
    }

    var head = document.createElement('div');
    head.className = 'code-head';

    var label = document.createElement('span');
    label.className = 'code-lang';
    label.textContent = lang || 'text';
    head.appendChild(label);

    var copy = document.createElement('span');
    copy.className = 'copy';
    copy.setAttribute('role', 'button');
    copy.setAttribute('tabindex', '0');
    copy.textContent = 'Copy';
    head.appendChild(copy);

    pre.insertBefore(head, code);

    // Syntax-highlight the code element.
    try {
      if (
        typeof window.hljs !== 'undefined' &&
        window.hljs &&
        typeof window.hljs.highlightElement === 'function'
      ) {
        window.hljs.highlightElement(code);
      }
    } catch (e) {
      // Highlighting is best-effort.
    }
  }
  CC.decorateOneCodeBlock = decorateOneCodeBlock;

  // Extension → hljs language alias, restricted to languages actually registered in the vendored bundle
  // (highlight.min.js is a curated subset, not the full hljs distribution). An unmapped/unknown extension
  // returns null — decorateOneCodeBlock then leaves the `language-xxx` class unset, and hljs.highlightElement
  // still runs its own autodetection, so the block is never left unhighlighted, just less precisely labelled.
  var EXT_LANG = {
    kt: 'kotlin',
    kts: 'kotlin',
    java: 'java',
    js: 'javascript',
    mjs: 'javascript',
    cjs: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    json: 'json',
    xml: 'xml',
    html: 'xml',
    htm: 'xml',
    svg: 'xml',
    xsd: 'xml',
    xsl: 'xml',
    plist: 'xml',
    yml: 'yaml',
    yaml: 'yaml',
    sh: 'bash',
    bash: 'bash',
    zsh: 'bash',
    py: 'python',
    rb: 'ruby',
    go: 'go',
    rs: 'rust',
    c: 'c',
    h: 'c',
    cpp: 'cpp',
    cc: 'cpp',
    cxx: 'cpp',
    hpp: 'cpp',
    hh: 'cpp',
    cs: 'csharp',
    php: 'php',
    pl: 'perl',
    pm: 'perl',
    lua: 'lua',
    sql: 'sql',
    css: 'css',
    scss: 'scss',
    less: 'less',
    md: 'markdown',
    markdown: 'markdown',
    ini: 'ini',
    cfg: 'ini',
    conf: 'ini',
    properties: 'ini',
    swift: 'swift',
    r: 'r',
    graphql: 'graphql',
    gql: 'graphql',
    vb: 'vbnet',
    wasm: 'wasm',
    wat: 'wasm',
    m: 'objectivec',
    mm: 'objectivec',
    txt: 'plaintext',
  };
  CC.languageForPath = function (path) {
    var p = String(path || '');
    var base = p.split(/[\\/]/).pop() || '';
    if (/^makefile$/i.test(base)) {
      return 'makefile';
    }
    var dot = base.lastIndexOf('.');
    if (dot < 0 || dot === base.length - 1) {
      return null;
    }
    var ext = base.slice(dot + 1).toLowerCase();
    return EXT_LANG[ext] || null;
  };
})();
