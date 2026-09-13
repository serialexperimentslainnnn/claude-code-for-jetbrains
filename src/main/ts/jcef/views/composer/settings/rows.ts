(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const ST = (CX.settings = CX.settings || ({} as SettingsNs));

  const h = CX.h;

  let groupSeq = 0;

  function settingRow(it: SettingItem, group: string): SettingsRow {
    const radio = ST.isRadio(it);
    const on = !!it.on;
    const label = ST.labelOf(it);
    const row = h(
      'button',
      {
        class: 'menu-item settings-item' + (on ? ' selected' : ''),
        title: label,
        attrs: {
          type: 'button',
          role: radio ? 'menuitemradio' : 'menuitemcheckbox',
          'aria-checked': on ? 'true' : 'false',
          tabindex: '-1',
        },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            choose(it, row, radio);
          },
        },
      },
      h('span', { class: 'menu-item-label', text: label })
    ) as SettingsRow;
    row.__ccKey = String(it.key);
    row.__ccGroup = group;
    row.__ccFocusId = 'k' + ST.SEP + row.__ccKey;
    return row;
  }

  function choose(it: SettingItem, row: SettingsRow, radio: boolean): void {
    const already = row.getAttribute('aria-checked') === 'true';
    if (radio) {
      if (already) return;
      clearGroup(row.__ccGroup, row.__ccKey);
      it.on = true;
      ST.applyState(row, true);
      CC.send({ type: 'settingsToggle', key: String(it.key), on: true });
      if (CC.announce) CC.announce(ST.labelOf(it) + ' selected');
      return;
    }
    if (it.hostOwned) {
      CC.send({ type: 'settingsToggle', key: String(it.key), on: !already });
      return;
    }
    it.on = !already;
    ST.applyState(row, !already);
    CC.send({ type: 'settingsToggle', key: String(it.key), on: !already });
    if (CC.announce) CC.announce(ST.labelOf(it) + (already ? ' off' : ' on'));
  }

  function clearGroup(group: string | undefined, keptKey: string | undefined): void {
    ST.allRows().forEach(function (r) {
      if (r.getAttribute('role') !== 'menuitemradio') return;
      if (r.__ccGroup === group && r.__ccKey !== keptKey) ST.applyState(r, false);
    });
    ST.items().forEach(function (o) {
      if (ST.groupOf(o) === group && ST.isRadio(o) && String(o.key) !== keptKey) o.on = false;
    });
  }

  ST.applyState = function (row: HTMLElement, on: boolean): void {
    row.setAttribute('aria-checked', on ? 'true' : 'false');
    row.classList.toggle('selected', !!on);
  };

  function navEntry(path: string, label: string, list: SettingItem[]): SettingsRow {
    const name = label || 'Settings';
    const row = h(
      'button',
      {
        class: 'menu-item settings-item settings-group-entry',
        title: name,
        attrs: {
          type: 'button',
          role: 'menuitem',
          tabindex: '-1',
          'aria-haspopup': 'menu',
        },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            ST.enterGroup(path);
          },
        },
      },
      h('span', { class: 'menu-item-label', text: name })
    ) as SettingsRow;
    row.appendChild(h('span', { class: 'menu-group-caret', attrs: { 'aria-hidden': 'true' } }));
    row.__ccFocusId = 'g' + ST.SEP + path;
    return row;
  }

  function groupHead(name: string): HTMLElement {
    const back = h(
      'button',
      {
        class: 'attach-back',
        title: 'Back',
        attrs: { type: 'button', role: 'menuitem', tabindex: '-1', 'aria-label': 'Back to all settings' },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            ST.leaveGroup();
          },
        },
      },
      h('span', { text: '←', attrs: { 'aria-hidden': 'true' } })
    ) as SettingsRow;
    back.__ccFocusId = 'b';
    return h(
      'div',
      { class: 'attach-head' },
      back,
      h('span', { class: 'attach-title', text: name || 'Settings' })
    );
  }

  function openSettingsRow(): SettingsRow {
    const row = h(
      'button',
      {
        class: 'menu-item settings-item',
        attrs: { type: 'button', role: 'menuitem', tabindex: '-1' },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            ST.close(false);
            CC.send({ type: 'openSettings' });
          },
        },
      },
      h('span', { class: 'menu-item-label', text: 'Open Plugin Settings' })
    ) as SettingsRow;
    row.__ccFocusId = 'x';
    return row;
  }

  ST.buildBody = function (): DocumentFragment {
    const frag = document.createDocumentFragment();
    const panel = ST.view != null ? ST.panelFor(ST.view) : null;
    if (panel) {
      frag.appendChild(groupHead(panel.title));
      if (panel.rows.length) {
        const body = h('div', {
          class: 'settings-section-items',
          attrs: {
            role: 'group',
            'aria-label': panel.title || 'Settings',
            id: 'settings-group-' + ++groupSeq,
          },
        });
        panel.rows.forEach(function (it) {
          body.appendChild(settingRow(it, panel.group));
        });
        frag.appendChild(body);
      }
      panel.subs.forEach(function (s) {
        frag.appendChild(navEntry(panel.path + ST.SEP + s.name, s.name, s.list));
      });
      return frag;
    }
    const all = ST.groups();
    if (!all.length) {
      frag.appendChild(
        h('div', {
          class: 'menu-item settings-empty',
          text: 'No quick settings yet.',
          attrs: { role: 'menuitem', 'aria-disabled': 'true', tabindex: '-1' },
        })
      );
    } else {
      all.forEach(function (g) {
        frag.appendChild(navEntry(g.name, g.name, g.list));
      });
    }
    frag.appendChild(h('div', { class: 'menu-sep', attrs: { role: 'separator' } }));
    frag.appendChild(openSettingsRow());
    return frag;
  };
})();
