(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const PA = (CX.palette = CX.palette || ({} as PaletteNs));

  let commands: PaletteCommand[] = [];

  const COMMAND_TOKEN = /^\/(\S*)$/;
  const SEPARATORS = '-_:./';

  PA.queryOf = function (value: unknown): string | null {
    const match = COMMAND_TOKEN.exec(value == null ? '' : String(value));
    return match ? match[1].toLowerCase() : null;
  };

  PA.setCommands = function (list: PaletteCommand[]): void {
    commands = list;
  };

  function startsAtSegment(name: string, q: string): boolean {
    for (let i = 0; i < name.length - 1; i++) {
      if (SEPARATORS.indexOf(name.charAt(i)) !== -1 && name.indexOf(q, i + 1) === i + 1) return true;
    }
    return false;
  }

  function startsAtWord(text: string, q: string): boolean {
    let at = text.indexOf(q);
    while (at !== -1) {
      if (at === 0 || !/[a-z0-9]/.test(text.charAt(at - 1))) return true;
      at = text.indexOf(q, at + 1);
    }
    return false;
  }

  function score(name: string, description: string, q: string): number {
    if (!q) return 1;
    const n = name.toLowerCase();
    const d = description.toLowerCase();
    if (n === q) return 100;
    if (n.indexOf(q) === 0) return 80;
    if (startsAtSegment(n, q)) return 60;
    if (n.indexOf(q) !== -1) return 40;
    if (startsAtWord(d, q)) return 20;
    if (d.indexOf(q) !== -1) return 10;
    return 0;
  }

  PA.rank = function (q: string): PaletteItem[] {
    const scored: PaletteItem[] = [];
    for (let i = 0; i < commands.length; i++) {
      const c = commands[i];
      const name = c && c.name != null ? String(c.name) : '';
      const description = c && c.description != null ? String(c.description) : '';
      const points = score(name, description, q);
      if (points > 0) scored.push({ name: name, description: description, score: points });
    }
    scored.sort(function (a, b) {
      if (q && a.score !== b.score) return b.score - a.score;
      if (q && a.name.length !== b.name.length) return a.name.length - b.name.length;
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
    });
    return scored;
  };
})();
