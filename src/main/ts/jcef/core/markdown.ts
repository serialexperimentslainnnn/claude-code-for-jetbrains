(function () {
  'use strict';

  const CC = window.CC || (window.CC = {} as CcShared);

  const WEB_LINKS =
    /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|data:image\/|[^a-z]|[a-z+.-]+(?:[^a-z+.:-]|$))/i;
  const HOST_LINKS =
    /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|jb):|data:image\/|[^a-z]|[a-z+.-]+(?:[^a-z+.:-]|$))/i;

  CC.markdown = function (text: unknown, opts?: MarkdownOptions): string {
    if (text === null || text === undefined) return '';
    const src = String(text);
    let raw: string;
    try {
      const mdOpts = { breaks: true, gfm: true };
      const md = window.marked;
      raw = md ? (typeof md.parse === 'function' ? md.parse(src, mdOpts) : md(src, mdOpts)) : CC.escape(src);
    } catch (e) {
      raw = CC.escape(src);
    }

    let clean: string;
    try {
      const purify = window.DOMPurify;
      clean = purify
        ? purify.sanitize(raw, {
            ADD_ATTR: ['target'],
            FORBID_ATTR: ['style'],
            ALLOWED_URI_REGEXP: opts && opts.hostLinks ? HOST_LINKS : WEB_LINKS,
          })
        : CC.escape(src);
    } catch (e2) {
      clean = CC.escape(src);
    }

    try {
      const holder = document.createElement('div');
      holder.innerHTML = clean;
      decorateCodeBlocks(holder);
      return holder.innerHTML;
    } catch (e3) {
      return clean;
    }
  };

  function decorateCodeBlocks(root: Element | null): void {
    if (!root) return;
    const blocks = root.querySelectorAll('pre > code');
    for (let i = 0; i < blocks.length; i++) {
      decorateOneCodeBlock(blocks[i]);
    }
  }

  function decorateOneCodeBlock(code: Element): void {
    const pre = code && (code.parentNode as Element | null);
    if (!pre || pre.getAttribute('data-cc-decorated') === '1') return;
    pre.setAttribute('data-cc-decorated', '1');

    let lang = '';
    const cls = (code.className || '').split(/\s+/);
    for (let c = 0; c < cls.length; c++) {
      if (cls[c].indexOf('language-') === 0) {
        lang = cls[c].slice('language-'.length);
        break;
      }
    }

    const head = document.createElement('div');
    head.className = 'code-head';

    const label = document.createElement('span');
    label.className = 'code-lang';
    label.textContent = lang || 'text';
    head.appendChild(label);

    const copy = document.createElement('span');
    copy.className = 'copy';
    copy.setAttribute('role', 'button');
    copy.setAttribute('tabindex', '0');
    copy.textContent = 'Copy';
    head.appendChild(copy);

    pre.insertBefore(head, code);

    try {
      const highlighter = window.hljs;
      if (highlighter && typeof highlighter.highlightElement === 'function') {
        highlighter.highlightElement(code);
      }
    } catch (e) {}
  }
  CC.decorateOneCodeBlock = decorateOneCodeBlock;

  const EXT_LANG: Record<string, string> = {
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
  CC.languageForPath = function (path: unknown): string | null {
    const p = String(path || '');
    const base = p.split(/[\\/]/).pop() || '';
    if (/^makefile$/i.test(base)) {
      return 'makefile';
    }
    const dot = base.lastIndexOf('.');
    if (dot < 0 || dot === base.length - 1) {
      return null;
    }
    const ext = base.slice(dot + 1).toLowerCase();
    return EXT_LANG[ext] || null;
  };
})();
