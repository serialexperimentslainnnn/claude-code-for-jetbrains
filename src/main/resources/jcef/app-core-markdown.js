(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});

  CC.markdown = function (text) {
    if (text === null || text === undefined) return '';
    var src = String(text);
    var raw;
    try {
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
              ALLOWED_URI_REGEXP:
                /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|jb):|data:image\/|[^a-z]|[a-z+.-]+(?:[^a-z+.:-]|$))/i,
            })
          : CC.escape(src);
    } catch (e2) {
      clean = CC.escape(src);
    }

    try {
      var holder = document.createElement('div');
      holder.innerHTML = clean;
      decorateCodeBlocks(holder);
      return holder.innerHTML;
    } catch (e3) {
      return clean;
    }
  };

  function decorateCodeBlocks(root) {
    if (!root) return;
    var blocks = root.querySelectorAll('pre > code');
    for (var i = 0; i < blocks.length; i++) {
      decorateOneCodeBlock(blocks[i]);
    }
  }

  function decorateOneCodeBlock(code) {
    var pre = code && code.parentNode;
    if (!pre || pre.getAttribute('data-cc-decorated') === '1') return;
    pre.setAttribute('data-cc-decorated', '1');

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

    try {
      if (
        typeof window.hljs !== 'undefined' &&
        window.hljs &&
        typeof window.hljs.highlightElement === 'function'
      ) {
        window.hljs.highlightElement(code);
      }
    } catch (e) {}
  }
  CC.decorateOneCodeBlock = decorateOneCodeBlock;

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
