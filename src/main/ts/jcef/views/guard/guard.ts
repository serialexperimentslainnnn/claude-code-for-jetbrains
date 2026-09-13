(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const GL = (D.guardLog = D.guardLog || ({} as GuardLogNs));
  const h = D.h;
  const send = D.send;
  const card = D.card;

  const MAX_ROWS = 100;

  let open = false;

  function requestLog(): void {
    send({ type: 'guardLog' });
  }

  function buildStateCard(): HTMLElement | null {
    const p = GL.payload || {};
    const w = (p && p.window) || {};
    const rows: HTMLElement[] = [];

    if (p.recording === false) {
      rows.push(
        h('div', {
          class: 'guard-alarm',
          attrs: { role: 'alert' },
          text:
            'Alerts are NOT being written down. The guard is still deciding, but nothing it decides ' +
            'is reaching the log, and nothing said so until now. What you see below is whatever was ' +
            'stored before that started — it is not this session.',
        })
      );
    }

    rows.push(
      h('div', {
        class: 'guard-note',
        text:
          'Showing ' +
          GL.num(w.kept) +
          ' alert(s) from this chat. The log keeps the last ' +
          GL.num(w.max) +
          ' for the whole project, not per chat — a busy chat pushes another chat’s alerts out.',
      })
    );

    if (GL.num(w.dropped) > 0) {
      rows.push(
        h('div', {
          class: 'guard-alarm',
          attrs: { role: 'alert' },
          text:
            GL.num(w.dropped) +
            ' alert(s) from this chat were dropped on the way to the log and cannot be recovered.',
        })
      );
    }

    if (GL.num(w.missing) > 0) {
      rows.push(
        h('div', {
          class: 'guard-note',
          text:
            GL.num(w.missing) +
            ' alert(s) this chat recorded are not in this list — pushed out by newer ones, refused ' +
            'by the password safe, still being written, or filed under no session id.',
        })
      );
    }

    return card('Guard log', h('div', { class: 'guard-state' }, rows), true, 'guard-state');
  }

  function tabButton(spec: GuardLogTab): HTMLElement {
    const id = GL.text(spec.id, '');
    const active = id === GL.currentTab();
    return h('button', {
      class: 'guard-tab' + (active ? ' active' : ''),
      attrs: {
        type: 'button',
        'data-guard-tab': id,
        'aria-current': active ? 'true' : null,
      },
      text: GL.text(spec.label, id) + ' (' + GL.num(spec.count) + ')',
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          setTab(id);
        },
      },
    });
  }

  function tabStrip(): HTMLElement {
    return h(
      'div',
      { class: 'guard-tabs', attrs: { role: 'group', 'aria-label': 'Guard log' } },
      GL.tabs().map(tabButton)
    );
  }

  function buildEntriesCard(): HTMLElement | null {
    const id = GL.currentTab();
    const rows = GL.visibleEntries(id);
    const shown = rows.slice(0, MAX_ROWS);
    const body: HTMLElement[] = [tabStrip()];

    if (!shown.length) {
      body.push(
        h('div', {
          class: 'guard-empty',
          text: GL.filtering()
            ? 'Nothing here matches the search and filters.'
            : 'Nothing in this chat landed here.',
        })
      );
    } else {
      body.push(h('div', { class: 'guard-list' }, shown.map(GL.entryNode)));
    }

    if (rows.length > shown.length) {
      body.push(
        h('div', {
          class: 'guard-note',
          text: 'Showing the newest ' + shown.length + ' of ' + rows.length + '.',
        })
      );
    }

    return card('Decisions', body, true, 'guard-entries');
  }

  function setTab(id: string): void {
    if (GL.tab === id) return;
    GL.tab = id;
    GL.repaint();
    const c = D.core();
    if (c && typeof c.announce === 'function') c.announce(GL.text(id, 'guard') + ' guard entries');
  }

  D.buildGuardCards = function (): (HTMLElement | null)[] {
    if (!GL.payload) {
      return [
        card(
          'Guard log',
          h('div', { class: 'guard-note', text: 'Reading the guard log…' }),
          true,
          'guard-state'
        ),
      ];
    }
    return [buildStateCard(), GL.buildFiltersCard(), buildEntriesCard()];
  };

  D.guardTab = function (): string {
    return GL.currentTab();
  };

  D.guardVisible = function (visible: boolean): void {
    const next = !!visible;
    if (next === open) return;
    open = next;
    if (open) requestLog();
  };

  cc.guard = function (data?: unknown): void {
    GL.payload = data && typeof data === 'object' ? (data as GuardLogPayload) : null;
    GL.repaint();
  };
})();
