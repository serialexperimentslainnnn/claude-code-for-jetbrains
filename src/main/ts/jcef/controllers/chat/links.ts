(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const el = TX.el;
  const safeSend = TX.safeSend;
  const rows = TX.rows;

  const PATH_RE = new RegExp(
    '(?:' +
      '(?:~\\/|\\.{1,2}\\/|\\/)[\\w.-]+(?:\\/[\\w.-]+)*\\/?' +
      '|' +
      '[\\w.-]+\\/(?:[\\w.-]+\\/?)*' +
      '|' +
      '[\\w.-]+\\.[A-Za-z][\\w]{0,9}' +
      ')(?::\\d+)?',
    'g'
  );
  const SYMBOL_RE = /\b([A-Z][A-Za-z0-9]{2,}|[a-z][A-Za-z0-9]{2,}(?=\(\)))\b/g;

  function insideLinkOrPre(n: Node, root: Node): boolean {
    let p: Node | null = n.parentNode;
    while (p && p !== root) {
      const t = (p as Element).tagName;
      if (t === 'A' || t === 'PRE') {
        return true;
      }
      p = p.parentNode;
    }
    return false;
  }

  function collectCandidates(root: Node): { paths: string[]; symbols: string[] } {
    const paths: Record<string, true> = {};
    const symbols: Record<string, true> = {};
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n: Node) {
        return insideLinkOrPre(n, root) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
    let n: Node | null;
    let m: RegExpExecArray | null;
    while ((n = walker.nextNode())) {
      const txt = n.nodeValue || '';
      PATH_RE.lastIndex = 0;
      while ((m = PATH_RE.exec(txt))) {
        paths[m[0]] = true;
      }
      const inCode = !!n.parentNode && (n.parentNode as Element).tagName === 'CODE';
      if (inCode) {
        SYMBOL_RE.lastIndex = 0;
        while ((m = SYMBOL_RE.exec(txt))) {
          symbols[m[1]] = true;
        }
      }
    }
    return { paths: Object.keys(paths), symbols: Object.keys(symbols) };
  }

  TX.requestLinks = function (rec: RowRec, entry: TranscriptEntry): void {
    if (!rec || !rec.bodyNode) {
      return;
    }
    const c = collectCandidates(rec.bodyNode);
    if (!c.paths.length && !c.symbols.length) {
      return;
    }
    safeSend({ type: 'resolveLinks', rowId: entry.id, paths: c.paths, symbols: c.symbols });
  };

  function applyLinks(input: unknown): void {
    const payload = input as { rowId?: unknown; links?: unknown } | null | undefined;
    if (!payload || payload.rowId == null) {
      return;
    }
    const rec = rows.get(payload.rowId);
    if (!rec || !rec.bodyNode) {
      return;
    }
    const links: LinkHit[] = Array.isArray(payload.links) ? (payload.links as LinkHit[]) : [];
    if (!links.length) {
      return;
    }
    links.sort(function (a, b) {
      return String(b.token).length - String(a.token).length;
    });
    for (let i = 0; i < links.length; i++) {
      linkifyToken(rec.bodyNode, links[i]);
    }
  }

  const TOKEN_LEFT = /[\w.\-/~]/;
  const TOKEN_RIGHT = /[\w.\-/]/;
  function atTokenBoundary(txt: string, at: number, token: string): boolean {
    if (at > 0 && TOKEN_LEFT.test(txt.charAt(at - 1))) {
      return false;
    }
    const after = txt.charAt(at + token.length);
    return !(after && TOKEN_RIGHT.test(after));
  }

  function linkifyToken(root: Node, link: LinkHit): void {
    const token = String(link.token || '');
    if (!token) {
      return;
    }
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n: Node) {
        if (insideLinkOrPre(n, root)) return NodeFilter.FILTER_REJECT;
        return n.nodeValue && n.nodeValue.indexOf(token) >= 0
          ? NodeFilter.FILTER_ACCEPT
          : NodeFilter.FILTER_REJECT;
      },
    });
    const targets: Node[] = [];
    let n: Node | null;
    while ((n = walker.nextNode())) {
      targets.push(n);
    }
    for (let i = 0; i < targets.length; i++) {
      const node = targets[i];
      const txt = node.nodeValue || '';
      const frag = document.createDocumentFragment();
      let from = 0;
      let hit = false;
      for (let at = txt.indexOf(token); at >= 0; at = txt.indexOf(token, at + token.length)) {
        if (!atTokenBoundary(txt, at, token)) {
          continue;
        }
        frag.appendChild(document.createTextNode(txt.slice(from, at)));
        frag.appendChild(
          el('a', {
            class: 'jb-link',
            text: token,
            attrs: {
              href: TX.jbHref(link.path, link.line),
              title: 'Open ' + link.path + (link.line ? ':' + link.line : ''),
            },
          })
        );
        from = at + token.length;
        hit = true;
      }
      if (!hit) {
        continue;
      }
      frag.appendChild(document.createTextNode(txt.slice(from)));
      if (node.parentNode) node.parentNode.replaceChild(frag, node);
    }
  }

  cc.links = function (payload?: unknown): void {
    try {
      applyLinks(payload);
    } catch (e) {}
  };
})();
