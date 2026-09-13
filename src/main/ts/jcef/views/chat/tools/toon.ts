(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const el = TX.el;

  type Row = Record<string, unknown>;

  const POSITION = ['line', 'column'];

  function isRow(value: unknown): value is Row {
    return !!value && typeof value === 'object' && !Array.isArray(value);
  }

  function uniformKeys(list: unknown[]): string[] | null {
    if (list.length === 0 || !list.every(isRow)) return null;
    const keys = Object.keys(list[0] as Row);
    for (let i = 1; i < list.length; i++) {
      const own = Object.keys(list[i] as Row);
      if (own.length !== keys.length || own.some((k, at) => k !== keys[at])) return null;
    }
    return keys;
  }

  function scalar(value: unknown): string {
    if (value === true) return '✓';
    if (value === false) return '✗';
    if (value == null) return '';
    return String(value);
  }

  function fileLink(file: string, line: unknown): HTMLElement {
    const text = line ? file + ':' + line : file;
    const a = el('a', {
      class: 'jb-link',
      text: text,
      attrs: { href: TX.jbHref(file, line), title: 'Open ' + text },
    });
    a.addEventListener('click', function (e) {
      e.stopPropagation();
    });
    return a;
  }

  const DIFF_LINE = /^(diff --git|--- |\+\+\+ |@@ )/;

  function isDiff(text: string): boolean {
    return DIFF_LINE.test(text);
  }

  function diffLineClass(line: string): string {
    if (line.startsWith('@@')) return 'hunk';
    if (
      line.startsWith('+++') ||
      line.startsWith('---') ||
      line.startsWith('diff ') ||
      line.startsWith('index ')
    )
      return 'meta';
    if (line.startsWith('+')) return 'add';
    if (line.startsWith('-')) return 'del';
    return 'ctx';
  }

  function diffBlock(text: string): HTMLElement {
    const node = el('pre', { class: 'toon-diff' });
    text.split('\n').forEach(function (line) {
      node.appendChild(el('span', { class: 'toon-diff-' + diffLineClass(line), text: line + '\n' }));
    });
    return node;
  }

  function multiline(text: string): HTMLElement {
    return isDiff(text) ? diffBlock(text) : el('pre', { class: 'toon-block', text: text });
  }

  function cell(row: Row, key: string, tag: string): HTMLElement {
    const value = row[key];
    const node = el(tag, { class: 'toon-' + key });
    if (key === 'file' && typeof value === 'string' && value) {
      node.appendChild(fileLink(value, row.line));
    } else if (Array.isArray(value) || isRow(value)) {
      node.appendChild(render(value));
    } else if (typeof value === 'string' && value.indexOf('\n') >= 0) {
      node.appendChild(multiline(value));
    } else {
      node.textContent = scalar(value);
    }
    return node;
  }

  function columns(keys: string[], rows: Row[]): string[] {
    const merged = keys.indexOf('file') >= 0;
    return keys.filter(function (key) {
      if (merged && POSITION.indexOf(key) >= 0) return false;
      return rows.some(function (row) {
        return scalar(row[key]) !== '';
      });
    });
  }

  function table(keys: string[], rows: Row[]): HTMLElement {
    const shown = columns(keys, rows);
    const head = el('tr', {});
    shown.forEach(function (key) {
      head.appendChild(el('th', { text: key }));
    });
    const body = el('tbody', {});
    rows.forEach(function (row) {
      const tr = el('tr', {});
      shown.forEach(function (key) {
        tr.appendChild(cell(row, key, 'td'));
      });
      body.appendChild(tr);
    });
    const node = el('table', { class: 'toon-table' });
    node.appendChild(el('thead', {})).appendChild(head);
    node.appendChild(body);
    return node;
  }

  function list(items: unknown[]): HTMLElement {
    const node = el('ul', { class: 'toon-list' });
    items.forEach(function (item) {
      const li = el('li', {});
      if (Array.isArray(item) || isRow(item)) li.appendChild(render(item));
      else li.textContent = scalar(item);
      node.appendChild(li);
    });
    return node;
  }

  const IDENTITY = ['path', 'file', 'query', 'name', 'hash'];

  function identityOf(item: Row): string | null {
    for (let i = 0; i < IDENTITY.length; i++) {
      const v = item[IDENTITY[i]];
      if (typeof v === 'string' && v) return IDENTITY[i];
    }
    return null;
  }

  function codeBlock(text: string, path: string): HTMLElement {
    const pre = el('pre', { class: 'toon-block toon-code' });
    const code = el('code', { text: text });
    const lang = typeof CC.languageForPath === 'function' ? CC.languageForPath(path) : null;
    if (lang) code.className = 'language-' + lang;
    pre.appendChild(code);
    if (typeof CC.decorateOneCodeBlock === 'function') CC.decorateOneCodeBlock(code);
    return pre;
  }

  function itemBody(item: Row, key: string | null): HTMLElement {
    const rest: Row = {};
    Object.keys(item).forEach(function (k) {
      if (k !== key) rest[k] = item[k];
    });
    const path = key === 'path' || key === 'file' ? String(item[key]) : '';
    if (path && typeof rest.text === 'string' && rest.text.indexOf('\n') >= 0) {
      const node = el('div', {});
      delete rest.text;
      node.appendChild(fields(rest));
      node.appendChild(codeBlock(String(item.text), path));
      return node;
    }
    return render(rest);
  }

  function items(list: Row[]): HTMLElement {
    const node = el('div', { class: 'toon-items' });
    list.forEach(function (item) {
      const key = identityOf(item);
      const details = el('details', { class: 'toon-item' + ('error' in item ? ' toon-item-error' : '') });
      const summary = el('summary', {});
      if (key === 'path' || key === 'file') summary.appendChild(fileLink(String(item[key]), item.line));
      else summary.appendChild(el('span', { class: 'toon-scalar', text: key ? String(item[key]) : '' }));
      if (typeof item.error === 'string')
        summary.appendChild(el('span', { class: 'toon-item-err', text: String(item.error) }));
      details.appendChild(summary);
      details.appendChild(itemBody(item, key));
      node.appendChild(details);
    });
    return node;
  }

  function fields(row: Row): HTMLElement {
    const node = el('div', { class: 'toon-fields' });
    Object.keys(row).forEach(function (key) {
      const value = row[key];
      if (key === 'items' && Array.isArray(value) && value.length > 0 && value.every(isRow)) {
        node.appendChild(items(value as Row[]));
        return;
      }
      const nested =
        Array.isArray(value) || isRow(value) || (typeof value === 'string' && value.indexOf('\n') >= 0);
      const field = el('div', { class: 'toon-field' + (nested ? ' toon-nested' : '') });
      field.appendChild(el('span', { class: 'toon-key', text: key }));
      field.appendChild(cell(row, key, 'span'));
      node.appendChild(field);
    });
    return node;
  }

  function render(value: unknown): HTMLElement {
    if (Array.isArray(value)) {
      const keys = uniformKeys(value);
      return keys ? table(keys, value as Row[]) : list(value);
    }
    if (isRow(value)) return fields(value);
    return el('span', { class: 'toon-scalar', text: scalar(value) });
  }

  TX.renderToon = function (root: HTMLElement, json: string): unknown {
    root.innerHTML = '';
    let value: unknown;
    try {
      value = JSON.parse(json);
    } catch (e) {
      root.appendChild(el('pre', { text: json }));
      return undefined;
    }
    root.appendChild(render(value));
    return value;
  };
})();
