(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const el = TX.el;
  const safeSend = TX.safeSend;

  const TOOL_ICONS: Record<string, string> = {
    bash: '<rect x="1.75" y="2.75" width="12.5" height="10.5" rx="1.75"/><path d="m4.5 6.25 2 1.75-2 1.75"/><path d="M8 10.25h3"/>',
    read: '<path d="M8 4.25C6.75 3.4 5.4 3 3.5 3H2v8.5h1.75c1.6 0 3 .35 4.25 1.25"/><path d="M8 4.25C9.25 3.4 10.6 3 12.5 3H14v8.5h-1.75c-1.6 0-3 .35-4.25 1.25"/><path d="M8 4.25v8.5"/>',
    edit: '<path d="M10.75 2.5 13.5 5.25 6 12.75 2.75 13.5l.75-3.25z"/><path d="M9.25 4 12 6.75"/>',
    search: '<circle cx="7" cy="7" r="4.25"/><path d="m10.25 10.25 3 3"/>',
    web: '<circle cx="8" cy="8" r="6"/><path d="M2 8h12"/><path d="M8 2c1.75 1.6 2.75 3.7 2.75 6S9.75 12.4 8 14C6.25 12.4 5.25 10.3 5.25 8S6.25 3.6 8 2z"/>',
    task: '<circle cx="8" cy="8" r="6"/><path d="M6.5 5.5 11 8l-4.5 2.5z" fill="currentColor"/>',
    generic:
      '<path d="M6.25 2.75h3.5v1.5a1.25 1.25 0 1 0 2.5 0v-1.5h.75v3.5h1.5a1.25 1.25 0 1 1 0 2.5h-1.5v3.5H9.5v-1.5a1.25 1.25 0 1 0-2.5 0v1.5H3.5v-3.5H2a1.25 1.25 0 1 1 0-2.5h1.5v-3.5h2.75z"/>',
  };
  const OWN_RUN = /^mcp__[a-z]+__run$/;

  function toolIconKey(meta: unknown): string {
    const m = meta == null ? '' : String(meta);
    if (m === 'Bash') return 'bash';
    if (m === 'Read' || m === 'NotebookRead') return 'read';
    if (m === 'Edit' || m === 'Write' || m === 'MultiEdit' || m === 'NotebookEdit') return 'edit';
    if (m === 'Grep' || m === 'Glob' || m === 'Search') return 'search';
    if (m === 'WebFetch' || m === 'WebSearch') return 'web';
    if (m === 'Task' || m === 'Agent') return 'task';
    return 'generic';
  }
  TX.toolIconSvg = function (meta: unknown): string {
    const inner = TOOL_ICONS[toolIconKey(meta)] || TOOL_ICONS.generic;
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      inner +
      '</svg>'
    );
  };

  TX.buildTool = function (entry: TranscriptEntry | null | undefined): RowRec {
    const node = el('div', { class: 'tool' }) as RowEl;
    const head = el('div', { class: 'tool-head' });
    head.appendChild(el('span', { class: 'ic', text: '▸' }));
    const name = el('span', { class: 'name' }) as BodyEl;
    head.appendChild(name);
    const elapsed = el('span', { class: 'tool-elapsed' });
    elapsed.hidden = true;
    head.appendChild(elapsed);
    const diffBtn = el('button', { class: 'tool-diff', text: 'View diff', attrs: { type: 'button' } });
    diffBtn.hidden = true;
    diffBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (node.__toolUseId) {
        safeSend({ type: 'viewDiffByTool', toolUseId: node.__toolUseId });
      }
    });
    head.appendChild(diffBtn);
    const restoreBtn = el('button', {
      class: 'tool-restore',
      text: 'Restore',
      attrs: { type: 'button' },
      title: 'Revert this edit',
    });
    restoreBtn.hidden = true;
    restoreBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (node.__toolUseId) {
        safeSend({ type: 'revertEdit', toolUseId: node.__toolUseId });
      }
    });
    head.appendChild(restoreBtn);
    node.__restoreBtn = restoreBtn;
    head.appendChild(el('span', { class: 'chev', text: '▾' }));
    const cmd = el('div', { class: 'tool-cmd' });
    const msg = el('div', { class: 'tool-msg' });
    const out = el('div', { class: 'tool-out' });
    const children = el('div', { class: 'tool-children' });
    head.addEventListener('click', function () {
      if (node.__isAgentCard && node.__toolUseId) {
        safeSend({ type: 'revealAgent', toolUseId: node.__toolUseId });
        return;
      }
      node.classList.toggle('open');
      if (node.classList.contains('open')) TX.scrollLiveToEnd(node);
    });
    const places = el('div', { class: 'tool-places' });
    places.hidden = true;
    node.appendChild(head);
    node.appendChild(places);
    node.appendChild(msg);
    node.appendChild(cmd);
    node.appendChild(out);
    node.appendChild(children);
    if (entry && entry.message) {
      renderMessageBlock(msg, entry.message, OWN_RUN.test(String(entry.meta || '')) ? 'args' : 'message');
      node.classList.add('msg-tool');
    }
    node.__nameNode = name;
    node.__msgNode = msg;
    node.__cmdNode = cmd;
    node.__outNode = out;
    node.__childrenNode = children;
    node.__elapsedNode = elapsed;
    node.__diffBtn = diffBtn;
    node.__placesNode = places;
    return { el: node, bodyNode: name, kind: 'tool', outNode: out };
  };

  TX.renderPlaces = function (node: RowEl, places: CardPlace[] | null | undefined): void {
    const host = node.__placesNode;
    if (!host) return;
    const list = Array.isArray(places) ? places.filter((p) => p && p.href && p.label) : [];
    const key = JSON.stringify(list);
    if (node.__placesKey === key) return;
    node.__placesKey = key;
    host.innerHTML = '';
    list.forEach(function (place) {
      host.appendChild(
        el('a', {
          class: 'jb-link tool-place',
          text: String(place.label),
          attrs: { href: String(place.href) },
        })
      );
    });
    host.hidden = list.length === 0;
  };

  function formatElapsed(secs: unknown): string {
    const n = Number(secs);
    if (!isFinite(n) || n <= 0) {
      return '';
    }
    return '· ' + n.toFixed(1) + 's';
  }

  TX.applyToolElapsed = function (node: RowEl, state: string | null | undefined, elapsedSecs: unknown): void {
    const badge = node.__elapsedNode || node.querySelector<HTMLElement>('.tool-elapsed');
    if (!badge) {
      return;
    }
    const running = state === 'RUNNING' || state === 'LOADING';
    const label = running ? formatElapsed(elapsedSecs) : '';
    if (label) {
      badge.textContent = label;
      badge.hidden = false;
    } else {
      badge.textContent = '';
      badge.hidden = true;
    }
  };

  TX.applyToolState = function (
    node: RowEl,
    state: string | null | undefined,
    meta: string | null | undefined
  ): void {
    node.classList.remove('loading', 'running', 'done', 'failed');
    if (state === 'ERROR' || meta === 'error') {
      node.classList.add('failed');
      if (!node.__autoOpenedOnError && !node.__isAgentCard) {
        node.__autoOpenedOnError = true;
        node.classList.add('open');
      }
    } else if (state === 'LOADING') {
      node.classList.add('loading');
    } else if (state === 'RUNNING') {
      node.classList.add('running');
    } else if (state === 'FINISHED') {
      node.classList.add('done');
    }
  };

  function carriedBlock(
    node: HTMLElement | null | undefined,
    text: unknown,
    blockClass: string,
    label: string
  ): void {
    if (!node) {
      return;
    }
    const block = el('pre', { class: blockClass });
    const code = el('code', {});
    code.textContent = String(text);
    block.appendChild(code);
    node.appendChild(block);
    if (typeof CC.decorateOneCodeBlock === 'function') {
      CC.decorateOneCodeBlock(code);
      const langLabel = block.querySelector('.code-lang');
      if (langLabel) {
        langLabel.textContent = label;
      }
    }
  }

  TX.renderCommandBlock = function (cmdNode: HTMLElement | null | undefined, commandText: unknown): void {
    carriedBlock(cmdNode, commandText, 'command-src', 'shell');
  };

  function renderMessageBlock(msgNode: HTMLElement, messageText: unknown, label: string): void {
    carriedBlock(msgNode, messageText, 'message-src', label);
  }

  TX.jbHref = function (relPath: unknown, line?: unknown): string {
    let u = 'jb://open?file=' + encodeURIComponent(String(relPath));
    if (line) {
      u += '&line=' + encodeURIComponent(String(line));
    }
    return u;
  };

  TX.renderToolLabel = function (nameEl: HTMLElement | null, text: unknown, filePath: unknown): void {
    if (!nameEl) {
      return;
    }
    const label = text == null ? '' : String(text);
    const p = String(filePath);
    const at = label.indexOf(p);
    if (at < 0) {
      nameEl.textContent = label;
      return;
    }
    while (nameEl.firstChild) {
      nameEl.removeChild(nameEl.firstChild);
    }
    nameEl.appendChild(document.createTextNode(label.slice(0, at)));
    const a = el('a', { class: 'jb-link', text: p, attrs: { href: TX.jbHref(p), title: 'Open ' + p } });
    a.addEventListener('click', function (e) {
      e.stopPropagation();
    });
    nameEl.appendChild(a);
    nameEl.appendChild(document.createTextNode(label.slice(at + p.length)));
  };
})();
