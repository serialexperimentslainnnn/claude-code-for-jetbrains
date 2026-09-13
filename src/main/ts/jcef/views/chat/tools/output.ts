(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const el = TX.el;
  const toolCards = TX.toolCards;

  const MAX_JSON_CHARS = 200000;

  function prettyJson(text: string): string | null {
    if (!text || text.length > MAX_JSON_CHARS) {
      return null;
    }
    const s = text.trim();
    const head = s.charAt(0);
    const tail = s.charAt(s.length - 1);
    if ((head !== '{' || tail !== '}') && (head !== '[' || tail !== ']')) {
      return null;
    }
    try {
      return JSON.stringify(JSON.parse(s), null, 2);
    } catch (e) {
      return null;
    }
  }

  function decorate(codeEl: HTMLElement, language: string | null): void {
    if (typeof CC.decorateOneCodeBlock !== 'function') return;
    if (language) codeEl.className = 'language-' + language;
    CC.decorateOneCodeBlock(codeEl);
  }

  TX.routeToolOutput = function (entry: TranscriptEntry, cards?: Map<string, RowEl>): boolean {
    const known = cards || toolCards;
    const tid = entry.toolUseId;
    if (!tid) {
      return false;
    }
    const card = known.get(tid);
    if (!card) {
      return false;
    }
    const out = card.__outNode || card.querySelector<HTMLElement>('.tool-out');
    if (!out) {
      return false;
    }
    const pid = 'to-' + entry.id;
    let block = out.querySelector<HTMLElement>('[data-out-id="' + pid + '"]');
    if (entry.meta === 'toon') {
      if (!block) {
        block = el('div', { class: 'toon' });
        block.setAttribute('data-out-id', pid);
        out.appendChild(block);
      }
      const value = TX.renderToon(block, entry.text == null ? '' : String(entry.text));
      setFoot(card, foot(value));
      return true;
    }
    if (!block) {
      block = el('pre', {});
      block.setAttribute('data-out-id', pid);
      block.appendChild(el('code', {}));
      out.appendChild(block);
    }
    const codeEl = block.querySelector<HTMLElement>('code');
    if (codeEl) {
      const tags = ' ' + (entry.meta || '') + ' ';
      const fileLang = typeof CC.languageForPath === 'function' ? CC.languageForPath(card.__filePath) : null;
      const raw = entry.text == null ? '' : String(entry.text);
      if (entry.meta === 'diff') {
        renderDiff(codeEl, raw, fileLang);
        block.classList.add('diff');
        block.classList.remove('command');
        block.classList.remove('flow');
      } else if (tags.indexOf(' command ') >= 0) {
        block.classList.remove('diff');
        block.classList.remove('flow');
        block.classList.add('command');
        codeEl.textContent = raw;
        if (typeof CC.decorateOneCodeBlock === 'function') {
          CC.decorateOneCodeBlock(codeEl);
          const langLabel = block.querySelector('.code-lang');
          if (langLabel) {
            langLabel.textContent = 'shell';
          }
        }
      } else if (entry.meta === 'live') {
        block.classList.remove('diff');
        block.classList.remove('command');
        block.classList.add('flow');
        block.classList.add('live');
        codeEl.textContent = raw;
        codeEl.scrollTop = codeEl.scrollHeight;
        setLiveTail(card, raw);
      } else {
        block.classList.remove('diff');
        block.classList.remove('command');
        const json = prettyJson(raw);
        codeEl.textContent = json == null ? raw : json;
        if (card.__filePath) {
          block.classList.remove('flow');
          decorate(codeEl, fileLang);
        } else {
          block.classList.add('flow');
          if (json != null) decorate(codeEl, 'json');
        }
      }
    }
    return true;
  };

  function tailRow(card: RowEl): HTMLElement {
    let tail = card.__liveTail || null;
    if (!tail) {
      tail = el('div', { class: 'tool-live-tail' });
      tail.appendChild(el('span', { class: 'tool-foot' }));
      tail.appendChild(el('span', { class: 'tool-last' }));
      const out = card.__outNode || card.querySelector<HTMLElement>('.tool-out');
      if (out && out.parentNode) out.parentNode.insertBefore(tail, out);
      else card.appendChild(tail);
      card.__liveTail = tail;
    }
    return tail;
  }

  function setTailPart(card: RowEl, part: string, text: string): void {
    const tail = tailRow(card);
    const span = tail.querySelector<HTMLElement>('.' + part);
    if (span) span.textContent = text;
    tail.hidden = !tail.textContent;
  }

  function setLiveTail(card: RowEl, raw: string): void {
    const lines = raw.split('\n');
    let last = '';
    for (let i = lines.length - 1; i >= 0 && !last; i--) last = lines[i].trim();
    setTailPart(card, 'tool-last', last);
  }

  function setFoot(card: RowEl, text: string): void {
    if (text || card.__liveTail) setTailPart(card, 'tool-foot', text);
  }

  const FOOT_KEYS = [
    'status',
    'exit_code',
    'passed',
    'failed',
    'ignored',
    'errors_count',
    'warnings_count',
    'lines',
  ];

  function foot(value: unknown): string {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return '';
    const row = value as Record<string, unknown>;
    const parts: string[] = [];
    if (Array.isArray(row.items) && typeof row.count === 'number') parts.push(row.count + ' items');
    FOOT_KEYS.forEach(function (key) {
      const v = row[key];
      if (v == null || v === '' || (key !== 'status' && typeof v !== 'number')) return;
      if (key === 'status') parts.push(String(v));
      else if (key === 'exit_code') parts.push('exit ' + v);
      else parts.push(v + ' ' + key.replace('_count', '').replace('_', ' '));
    });
    return parts.join(' · ');
  }

  TX.scrollLiveToEnd = function (card: HTMLElement): void {
    const codes = card.querySelectorAll<HTMLElement>('.tool-out pre.live code');
    for (let i = 0; i < codes.length; i++) codes[i].scrollTop = codes[i].scrollHeight;
  };

  function renderDiff(codeEl: HTMLElement, text: string, lang: string | null): void {
    codeEl.innerHTML = '';
    const lines = String(text).split('\n');
    const highlighter = window.hljs;
    const canHighlight = !!(
      lang &&
      highlighter &&
      typeof highlighter.getLanguage === 'function' &&
      highlighter.getLanguage(lang) &&
      typeof highlighter.highlight === 'function'
    );
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const c0 = line.charAt(0);
      const isHunk = line.indexOf('@@') === 0;
      let cls = 'dl-ctx';
      if (isHunk) {
        cls = 'dl-hunk';
      } else if (c0 === '+') {
        cls = 'dl-add';
      } else if (c0 === '-') {
        cls = 'dl-del';
      }
      const span = el('span', { class: 'diff-line ' + cls });
      const trailingNl = i < lines.length - 1;
      let highlighted = false;
      if (!isHunk && canHighlight && highlighter && lang && line.length > 0) {
        try {
          const hi = highlighter.highlight(line.slice(1), { language: lang, ignoreIllegals: true }).value;
          span.appendChild(document.createTextNode(c0));
          span.insertAdjacentHTML('beforeend', hi);
          if (trailingNl) {
            span.appendChild(document.createTextNode('\n'));
          }
          highlighted = true;
        } catch (e) {}
      }
      if (!highlighted) {
        span.textContent = trailingNl ? line + '\n' : line;
      }
      codeEl.appendChild(span);
    }
  }
})();
