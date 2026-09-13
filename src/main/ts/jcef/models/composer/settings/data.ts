(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const ST = (CX.settings = CX.settings || ({} as SettingsNs));

  ST.SEP = String.fromCharCode(31);
  ST.payload = null;
  ST.view = null;

  ST.items = function (): SettingItem[] {
    const list = ST.payload && Array.isArray(ST.payload.items) ? (ST.payload.items as SettingItem[]) : [];
    return list.filter(function (it) {
      return it && it.key != null;
    });
  };

  ST.labelOf = function (it: SettingItem): string {
    return it.label != null ? String(it.label) : String(it.key);
  };

  ST.groupOf = function (it: SettingItem): string {
    return it.group != null ? String(it.group) : '';
  };

  function subOf(it: SettingItem): string {
    return it.sub != null ? String(it.sub) : '';
  }

  ST.isRadio = function (it: SettingItem): boolean {
    return String(it.type || 'check') === 'radio';
  };

  function bucket(list: SettingItem[], keyOf: (it: SettingItem) => string): SettingsSub[] {
    const order: string[] = [];
    const byKey: Record<string, SettingItem[]> = {};
    list.forEach(function (it) {
      const name = keyOf(it);
      if (!Object.prototype.hasOwnProperty.call(byKey, name)) {
        byKey[name] = [];
        order.push(name);
      }
      byKey[name].push(it);
    });
    return order.map(function (name) {
      return { name: name, list: byKey[name] };
    });
  }

  ST.groups = function (): SettingsGroup[] {
    return bucket(ST.items(), ST.groupOf).map(function (g) {
      const bySub = bucket(g.list, subOf);
      let direct: SettingItem[] = [];
      const subs: SettingsSub[] = [];
      bySub.forEach(function (s) {
        if (s.name === '') direct = s.list;
        else subs.push(s);
      });
      return { name: g.name, list: g.list, direct: direct, subs: subs };
    });
  };

  ST.panelFor = function (path: string): SettingsPanel | null {
    const parts = String(path).split(ST.SEP);
    let g: SettingsGroup | null = null;
    ST.groups().forEach(function (x) {
      if (x.name === parts[0]) g = x;
    });
    if (!g) return null;
    const group: SettingsGroup = g;
    if (parts.length < 2) {
      return {
        group: group.name,
        title: group.name,
        path: group.name,
        rows: group.direct,
        subs: group.subs,
        list: group.list,
      };
    }
    let s: SettingsSub | null = null;
    group.subs.forEach(function (x) {
      if (x.name === parts[1]) s = x;
    });
    if (!s) return null;
    const sub: SettingsSub = s;
    return { group: group.name, title: sub.name, path: path, rows: sub.list, subs: [], list: sub.list };
  };

  ST.structureSig = function (): string {
    const list = ST.items();
    let sig = (ST.view == null ? '' : ST.view) + '|' + list.length + '|';
    for (let i = 0; i < list.length; i++) {
      const it = list[i];
      const f = [ST.groupOf(it), subOf(it), it.key, ST.labelOf(it), ST.isRadio(it) ? 'r' : 'c'];
      sig += f.join(ST.SEP) + '|';
    }
    return sig;
  };
})();
