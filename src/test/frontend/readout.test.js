const { loadFrontend, readCss } = require('./helpers/load');

const sheet = readCss().replace(/\/\*[\s\S]*?\*\//g, '');

function bodyAt(from) {
  if (from < 0) throw new Error('no such block');
  let depth = 0;
  for (let i = sheet.indexOf('{', from); i < sheet.length; i++) {
    if (sheet[i] === '{') depth++;
    else if (sheet[i] === '}' && --depth === 0) return sheet.slice(sheet.indexOf('{', from) + 1, i);
  }
  throw new Error('unterminated block at ' + from);
}

const rule = (selector) => bodyAt(sheet.indexOf('\n' + selector + ' {'));

const foldMedia = () => bodyAt(sheet.indexOf('\n@media (max-width: 640px) {'));

describe('composer readout', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const readoutText = () => win.document.querySelector('.readout').textContent;

  it('the separators are decoration, and the digits do not shuffle', () => {
    expect(rule('.readout .ro-item')).toMatch(/font-variant-numeric:\s*tabular-nums/);
    expect(rule('.strip-cell::after')).toMatch(/content:\s*'·'\s*\/\s*''/);
    expect(readoutText()).not.toContain('·');
  });

  it('shows context, output and reasoning at 0 before any data arrives', () => {
    win.cc.state({ running: true, starting: false });
    const text = readoutText();
    expect(text).toContain('Context 0%');
    expect(text).toContain('0 out');
    expect(text).toContain('0 reasoning');
  });

  it('renders real values once they arrive', () => {
    win.cc.state({
      running: true,
      starting: false,
      context: { pct: 42 },
      tokensOut: 1500,
      reasoningTokens: 2400,
    });
    const text = readoutText();
    expect(text).toContain('Context 42%');
    expect(text).not.toContain('Context 0%');
    expect(text).toContain('reasoning');
  });

  it('keeps cost gated — a currency amount of zero is noise, not information', () => {
    win.cc.state({ running: true, starting: false });
    expect(readoutText()).not.toContain('$');
    win.cc.state({ running: true, starting: false, costUsd: 0.25 });
    expect(readoutText()).toContain('$0.25');
  });

  it('reports idle vs running honestly', () => {
    win.cc.state({ running: true, starting: false, turnActive: false });
    expect(readoutText()).toContain('Idle');
    win.cc.state({ running: true, starting: false, turnActive: true });
    expect(readoutText()).toContain('Running');
  });
});

describe('plan-limit bars', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const bars = () => win.document.querySelector('.usage-bars');
  const items = () => Array.from(bars().querySelectorAll('.ub-item'));
  const base = { running: true, starting: false };

  it('renders one labelled bar per window, outside the readout', () => {
    win.cc.state({
      ...base,
      usage: [
        { key: 'five_hour', label: 'Current session', pct: 13 },
        { key: 'seven_day', label: 'All models', pct: 9 },
        { key: 'model_scoped:Fable', label: 'Fable', pct: 71.25 },
      ],
    });
    expect(items()).toHaveLength(3);
    expect(items().map((el) => el.querySelector('.ub-label').textContent)).toEqual([
      'Current session',
      'All models',
      'Fable',
    ]);
    expect(items()[2].querySelector('.ub-pct').textContent).toBe('71.3%');
    expect(win.document.querySelector('.readout .ub-item')).toBeNull();
  });

  it('sets the fill width from the percentage and its colour from the level', () => {
    win.cc.state({
      ...base,
      usage: [
        { key: 'a', label: 'Low', pct: 10 },
        { key: 'b', label: 'Mid', pct: 70 },
        { key: 'c', label: 'High', pct: 90 },
      ],
    });
    const fills = items().map((el) => el.querySelector('.ub-track > i'));
    expect(fills.map((f) => f.style.width)).toEqual(['10%', '70%', '90%']);
    expect(fills.map((f) => f.className)).toEqual(['lvl-low', 'lvl-mid', 'lvl-high']);
  });

  it('clamps the BAR past 100% but never the number', () => {
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Over', pct: 103 }] });
    expect(items()[0].querySelector('.ub-track > i').style.width).toBe('100%');
    expect(items()[0].querySelector('.ub-pct').textContent).toBe('103.0%');
  });

  it('shows how long each window has left, compactly, with the sentence in the tooltip', () => {
    const in90min = new Date(Date.now() + 90 * 60000).toISOString();
    win.cc.state({
      ...base,
      usage: [
        { key: 'five_hour', label: 'Current session', pct: 90, resetsAt: in90min },
        { key: 'seven_day', label: 'All models', pct: 9 },
      ],
    });
    expect(items()[0].querySelector('.ub-reset').textContent).toBe('1h 30m');
    expect(items()[0].querySelector('.ub-row .ub-reset')).not.toBeNull();
    expect(items()[0].querySelector('.ub-row .ub-pct')).not.toBeNull();
    expect(items()[0].querySelector('.ub-row').lastElementChild.className).toBe('ub-reset');
    expect(items()[0].getAttribute('title')).toContain('Resets in 1h 30m');
    expect(items()[1].querySelector('.ub-reset').textContent).toBe('');
    expect(items()[1].getAttribute('title')).not.toContain('Resets');
  });

  it('says "soon" once the reset time has passed rather than a negative countdown', () => {
    const past = new Date(Date.now() - 60000).toISOString();
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Over', pct: 100, resetsAt: past }] });
    expect(items()[0].querySelector('.ub-reset').textContent).toBe('soon');
    expect(items()[0].getAttribute('title')).toContain('Resets shortly');
  });

  it('hides the row entirely when no window carries a percentage', () => {
    win.cc.state({ ...base });
    expect(bars().hasAttribute('hidden')).toBe(true);
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Unknown', pct: null }] });
    expect(bars().hasAttribute('hidden')).toBe(true);
    expect(items()).toHaveLength(0);
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Known', pct: 5 }] });
    expect(bars().hasAttribute('hidden')).toBe(false);
  });

  it('draws a window MEASURED at zero — a zero is a measurement, an absence is not', () => {
    win.cc.state({ ...base, usage: [{ key: 'model_scoped:Fable', label: 'Fable', pct: 0 }] });
    expect(bars().hasAttribute('hidden')).toBe(false);
    expect(items()).toHaveLength(1);
    expect(items()[0].querySelector('.ub-label').textContent).toBe('Fable');
    expect(items()[0].querySelector('.ub-pct').textContent).toBe('0.0%');
    expect(items()[0].querySelector('.ub-track > i').style.width).toBe('0%');
  });
});

describe('mini session view', () => {
  const payload = {
    model: 'opus[1m]',
    cwd: '/home/dev/project',
    version: '2.1.226',
    usage: { plan: 'Max', windows: [{ key: 'five_hour', label: 'Current session', pct: 13 }] },
    context: { used: 40000, max: 200000, pct: 20, categories: [{ name: 'System prompt', tokens: 4000 }] },
    cost: { input: 100, output: 200, usd: 0.42 },
    account: { email: 'dev@example.com', plan: 'Max' },
  };

  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    win.cc.state({ running: true, starting: false });
  });

  const fold = () => win.document.querySelector('.dash-mini');
  const lines = () =>
    Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-line')).map((line) =>
      Array.from(line.querySelectorAll('.mini-fact')).map((f) => [
        f.querySelector('.mini-key').textContent,
        f.querySelector('.mini-val').textContent,
      ])
    );
  const table = () => lines().reduce((all, l) => all.concat(l), []);

  it('stays out of the way until there is a session to describe', () => {
    expect(fold().hasAttribute('hidden')).toBe(true);
    win.cc.session(payload);
    expect(fold().hasAttribute('hidden')).toBe(false);
  });

  it('is lines of label-and-value with no disclosure of its own', () => {
    win.cc.session(payload);
    expect(lines()).toEqual([
      [
        ['Model:', 'opus[1m]'],
        ['Working dir:', '/home/dev/project'],
      ],
      [
        ['Account:', 'dev@example.com'],
        ['Plan:', 'Max'],
      ],
    ]);
    expect(win.document.querySelector('.dash-mini-btn')).toBeNull();
    expect(win.document.querySelector('.dash-mini-body')).toBeNull();
    expect(sheet).not.toMatch(/\.dash-mini-body/);
  });

  it('lays both lines on one grid instead of distributing each line on its own', () => {
    expect(rule('.dash-mini-grid')).toMatch(/display:\s*grid/);
    expect(rule('.dash-mini-grid')).toMatch(/grid-template-columns:\s*var\(--strip-cols\)/);
    expect(rule('.mini-line')).toMatch(/display:\s*contents/);
    expect(rule('.mini-line')).not.toMatch(/justify-content/);
    expect(rule('.mini-line')).not.toMatch(/display:\s*flex/);
  });

  it('never widens a cell to fill a short line, whatever else it lets one cell do', () => {
    win.cc.session(payload);
    const drawn = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-line'));
    expect(drawn.map((l) => l.className)).toEqual(['mini-line', 'mini-line']);
    expect(sheet).not.toMatch(/\.mini-wide/);
    expect(rule('.mini-fact')).not.toMatch(/grid-column/);
    expect(rule('.mini-line > .mini-fact:first-child')).not.toMatch(/span/);
    expect(lines()[0]).toHaveLength(2);
    expect(rule('.mini-line > .mini-fact:first-child')).toMatch(/grid-column-start:\s*1/);
  });

  it('writes the working directory against the home, and only against the real one', () => {
    const shownFor = (cwd, home) => {
      win.cc.session({ ...payload, cwd, home });
      return table().find((pair) => pair[0] === 'Working dir:')[1];
    };
    expect(shownFor('/home/dexperiments/pki/matrix-ca', '/home/dexperiments')).toBe('~/pki/matrix-ca');
    expect(shownFor('/home/dexperiments', '/home/dexperiments')).toBe('~');
    expect(shownFor('/home/someone-else/pki', '/home/dexperiments')).toBe('/home/someone-else/pki');
    expect(shownFor('/home/developer/x', '/home/dev')).toBe('/home/developer/x');
    expect(shownFor('C:\\Users\\bob\\proj', 'C:\\Users\\bob')).toBe('~\\proj');
    expect(shownFor('/home/dexperiments/pki', undefined)).toBe('/home/dexperiments/pki');
    expect(shownFor('/home/dexperiments/pki', '')).toBe('/home/dexperiments/pki');
    expect(shownFor('/home/dexperiments/pki', null)).toBe('/home/dexperiments/pki');
  });

  it('keeps the absolute path whole, because `~` is a convention and not a path', () => {
    win.cc.session({ ...payload, cwd: '/home/dexperiments/pki/matrix-ca', home: '/home/dexperiments' });
    const dir = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-fact')).find((f) =>
      f.textContent.startsWith('Working dir:')
    );
    expect(dir.getAttribute('title')).toBe('Working dir: /home/dexperiments/pki/matrix-ca');
    expect(dir.querySelector('.mini-val').textContent).toBe('~/pki/matrix-ca');
    expect(dir.querySelector('.mini-key').textContent).toBe('Working dir:');
  });

  it('lets the working directory use the empty columns beside it without moving a gridline', () => {
    win.cc.session(payload);
    const dir = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-fact')).find((f) =>
      f.textContent.startsWith('Working dir:')
    );
    expect(dir.classList.contains('mini-fill')).toBe(true);
    expect(win.document.querySelectorAll('.dash-mini-grid .mini-fill')).toHaveLength(1);
    const fill = rule('.mini-line > .mini-fact.mini-fill:nth-child(2):last-child');
    expect(fill).toMatch(/grid-column:\s*2 \/ -1/);
    expect(fill).not.toMatch(/grid-template-columns/);
    expect(sheet).toMatch(/\.mini-line > \.mini-fact:first-child \{/);
    expect(sheet).toMatch(/\.mini-fact\.mini-fill:nth-child\(2\):last-child \{/);
    expect(rule('#composer')).toMatch(/--strip-cols:\s*repeat\(4, minmax\(0, 1fr\)\)/);
    ['.readout', '.dash-mini-grid'].forEach((row) => {
      expect(rule(row)).toMatch(/grid-template-columns:\s*var\(--strip-cols\)/);
    });
    expect(rule('.usage-bars')).toMatch(/grid-template-columns:\s*var\(--strip-cols-bars\)/);
  });

  it('drops an organization that is only the account name again, and keeps one somebody named', () => {
    const orgKeys = (org) => {
      win.cc.session({ ...payload, account: { email: 'dev@example.com', org, plan: 'Max' } });
      return table().map((r) => r[0]);
    };
    expect(orgKeys("dev@example.com's Organization")).not.toContain('Organization:');
    expect(orgKeys('  DEV@EXAMPLE.COM’s organisation ')).not.toContain('Organization:');
    expect(orgKeys('dev@example.com')).not.toContain('Organization:');
    expect(orgKeys('Acme Corp')).toContain('Organization:');
    expect(orgKeys('dev@example.com Platform Team')).toContain('Organization:');
    expect(orgKeys('Dev Example Ltd')).toContain('Organization:');
  });

  it('leaves the freed column free rather than re-flowing the line that lost a fact', () => {
    win.cc.session({
      ...payload,
      account: {
        email: 'dev@example.com',
        org: "dev@example.com's Organization",
        plan: 'Max',
        provider: 'firstParty',
      },
    });
    const drawn = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-line'));
    expect(drawn.map((l) => l.className)).toEqual(['mini-line', 'mini-line']);
    expect(lines()[1].map((pair) => pair[0])).toEqual(['Account:', 'Plan:', 'Provider:']);
  });

  it('is two facts on the first line and three on the second, leaving the spare columns empty', () => {
    win.cc.session({
      ...payload,
      account: { email: 'dev@example.com', plan: 'Max', provider: 'firstParty' },
    });
    expect(lines().map((l) => l.length)).toEqual([2, 3]);
  });

  it('keeps a fourth fact inside the same grid instead of starting another one', () => {
    win.cc.session({
      ...payload,
      account: { email: 'dev@example.com', org: 'Acme Corp', plan: 'Max', provider: 'firstParty' },
    });
    expect(lines().map((l) => l.length)).toEqual([2, 4]);
    const narrow = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-line'))[1];
    expect(narrow.className).toBe('mini-line');
    expect(rule('.dash-mini-grid')).toMatch(/grid-template-columns:\s*var\(--strip-cols\)/);
  });

  it('has one column system, one gap, one type size and one inset, declared once for every row', () => {
    const rows = ['.readout', '.usage-bars', '.dash-mini-grid'];
    rows.forEach((selector) => {
      expect(rule(selector)).not.toMatch(/font-size:\s*[\d.]+px/);
      expect(rule(selector)).toMatch(/font-size:\s*var\(--strip-font\)/);
    });
    expect(rule('.usage-bars .ub-reset')).not.toMatch(/font-size/);
    expect(rule('.mini-empty')).not.toMatch(/font-size/);
    rows.forEach((selector) => {
      expect(rule(selector)).toMatch(/gap:\s*var\(--strip-row\) var\(--strip-gap\)/);
    });
    ['.readout', '.dash-mini-grid'].forEach((selector) => {
      expect(rule(selector)).toMatch(/grid-template-columns:\s*var\(--strip-cols\)/);
    });
    expect(rule('.usage-bars')).toMatch(/grid-template-columns:\s*var\(--strip-cols-bars\)/);
    rows.forEach((selector) => {
      expect(rule(selector)).not.toMatch(/grid-template-columns:\s*repeat/);
    });
    ['.readout', '.usage-bars', '.dash-mini', '.strip-more'].forEach((selector) => {
      expect(rule(selector)).toMatch(/margin:[^;]*var\(--strip-inset\)/);
    });
    const strip = rule('#composer');
    expect(strip).toMatch(/--strip-cols:\s*repeat\(4, minmax\(0, 1fr\)\)/);
    expect(strip).toMatch(/--strip-cols-bars:\s*repeat\(3, minmax\(0, 1fr\)\)/);
    expect(strip).toMatch(/--strip-font:\s*11px/);
    expect(strip).toMatch(/--strip-gap:\s*16px/);
    expect(strip).toMatch(/--strip-inset:\s*4px/);
    expect(strip).toMatch(/--strip-row:\s*1px/);
    expect(strip).toMatch(/--strip-line:\s*1\.5/);
  });

  it('has one distance between rows and one line height, declared once for every row', () => {
    ['.readout', '.usage-bars', '.dash-mini-grid'].forEach((selector) => {
      expect(rule(selector)).toMatch(/gap:\s*var\(--strip-row\) var\(--strip-gap\)/);
      expect(rule(selector)).toMatch(/line-height:\s*var\(--strip-line\)/);
      expect(rule(selector)).not.toMatch(/line-height:\s*[\d.]+;/);
    });
    expect(rule('.usage-bars .ub-item')).not.toMatch(/gap:/);
    expect(rule('.usage-bars .ub-item')).not.toMatch(/flex-direction:\s*column/);
    ['.usage-bars', '.dash-mini'].forEach((selector) => {
      expect(rule(selector)).toMatch(/margin:\s*0 var\(--strip-inset\) var\(--strip-row\)/);
    });
    expect(rule('.readout')).toMatch(/margin:\s*0 var\(--strip-inset\) 8px/);
  });

  it('is four rows in one order, and a short row stops instead of filling the width', () => {
    const in90min = new Date(Date.now() + 90 * 60000).toISOString();
    win.cc.state({
      running: true,
      starting: false,
      usage: [{ key: 'five_hour', label: 'Current session', pct: 13, resetsAt: in90min }],
    });
    win.cc.session(payload);
    const strip = win.document.getElementById('composer');
    const rowOf = (selector) => Array.prototype.indexOf.call(strip.children, strip.querySelector(selector));
    const order = ['.usage-bars', '.dash-mini', '.readout'].map(rowOf);
    order.forEach((at) => expect(at).toBeGreaterThanOrEqual(0));
    expect(order).toEqual([...order].sort((a, b) => a - b));
    expect(rowOf('.strip-more')).toBeGreaterThan(rowOf('.readout'));
    expect(lines().map((l) => l.map((pair) => pair[0]))).toEqual([
      ['Model:', 'Working dir:'],
      ['Account:', 'Plan:'],
    ]);
    const bars = win.document.querySelector('.usage-bars');
    expect(bars.hasAttribute('hidden')).toBe(false);
    const bar = bars.querySelector('.ub-item');
    expect(bar.querySelector('.ub-row .ub-track')).not.toBeNull();
    expect(bar.querySelector('.ub-row .ub-reset').textContent).toBe('1h 30m');
    expect(bar.children.length).toBe(1);
    expect(lines()[0]).toHaveLength(2);
    expect(rule('.mini-fact')).not.toMatch(/grid-column/);
    expect(sheet).not.toMatch(/\.mini-wide/);
  });

  it('clips every cell instead of reflowing, and clips the value rather than its key', () => {
    expect(rule('#composer')).toMatch(/--strip-cols:\s*repeat\(4, minmax\(0, 1fr\)\)/);
    expect(rule('#composer')).not.toMatch(/25%/);
    ['.mini-fact', '.usage-bars .ub-item'].forEach((cell) => {
      expect(rule(cell)).toMatch(/min-width:\s*0/);
      expect(rule(cell)).toMatch(/overflow:\s*hidden/);
    });
    expect(rule('.mini-val')).toMatch(/min-width:\s*0/);
    expect(rule('.mini-val')).toMatch(/overflow:\s*hidden/);
    expect(rule('.mini-val')).toMatch(/text-overflow:\s*ellipsis/);
    expect(rule('.mini-val')).toMatch(/white-space:\s*nowrap/);
    expect(rule('.mini-key')).toMatch(/flex:\s*0 0 auto/);
    expect(rule('.usage-bars .ub-label')).toMatch(/text-overflow:\s*ellipsis/);
    expect(rule('.usage-bars .ub-pct')).toMatch(/flex:\s*0 0 auto/);
  });

  it('separates two pairs by more than it separates a key from its own value', () => {
    const between = Number(/--strip-gap:\s*([\d.]+)px/.exec(rule('#composer'))[1]);
    const within = Number(/gap:\s*([\d.]+)px/.exec(rule('.mini-fact'))[1]);
    expect(between).toBeGreaterThanOrEqual(within * 3);
  });

  it('never repeats what the strip above it already shows', () => {
    win.cc.session(payload);
    const keys = table().map((r) => r[0]);
    expect(keys).not.toContain('Current session:');
    expect(keys).not.toContain('Context:');
    expect(keys).not.toContain('Cost:');
    expect(win.document.querySelectorAll('.dash-mini-grid .usage-track')).toHaveLength(0);
    expect(win.document.querySelectorAll('.dash-mini-grid .seg-bar')).toHaveLength(0);
    expect(win.document.querySelectorAll('.dash-mini-grid .dash-card')).toHaveLength(0);
  });

  it('shows the LAST payload, however many arrive', () => {
    win.cc.session(payload);
    win.cc.session({ ...payload, model: 'sonnet' });
    win.cc.session({ ...payload, model: 'haiku' });
    expect(table()).toContainEqual(['Model:', 'haiku']);
    expect(table()).not.toContainEqual(['Model:', 'opus[1m]']);
  });

  it('omits a fact the host did not send rather than drawing a dash', () => {
    win.cc.session({ model: 'opus[1m]' });
    expect(lines()).toEqual([[['Model:', 'opus[1m]']]]);
  });

  it('carries the full value in a tooltip, because the value itself is clipped', () => {
    const long = '/home/dev/PycharmProjects/claude-code-for-jetbrains/src/main/resources/jcef/css';
    win.cc.session({ ...payload, cwd: long });
    const dir = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-fact')).find((f) =>
      f.textContent.startsWith('Working dir:')
    );
    expect(dir.getAttribute('title')).toBe('Working dir: ' + long);
    expect(dir.querySelector('.mini-val').textContent).toBe(long);
    expect(rule('.mini-val')).toMatch(/text-overflow:\s*ellipsis/);
  });

  it('is a row of the dock and cannot cover anything', () => {
    win.cc.session(payload);
    expect(fold().previousSibling).toBe(win.document.querySelector('.usage-bars'));
    expect(fold().nextSibling).toBe(win.document.querySelector('.readout'));
    expect(rule('.dash-mini')).not.toMatch(/position:\s*(fixed|absolute)/);
    expect(rule('.dash-mini')).not.toMatch(/z-index/);
  });

  it('is bounded by construction, and its value column cannot widen the dock', () => {
    expect(rule('.mini-line')).toMatch(/display:\s*contents/);
    expect(rule('.mini-val')).toMatch(/text-overflow:\s*ellipsis/);
    expect(rule('.mini-val')).toMatch(/min-width:\s*0/);
  });
});

describe('strip separators', () => {
  let win;
  const base = { running: true, starting: false };
  const payload = {
    model: 'opus[1m]',
    cwd: '/home/dev/project',
    account: { email: 'dev@example.com', plan: 'Max', provider: 'firstParty' },
  };

  beforeEach(() => {
    win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    const soon = new Date(Date.now() + 6e5).toISOString();
    win.cc.state({
      ...base,
      usage: [
        { key: 'five_hour', label: 'Current session', pct: 13, resetsAt: soon },
        { key: 'seven_day', label: 'All models', pct: 9 },
        { key: 'model_scoped:Fable', label: 'Fable', pct: 71 },
      ],
    });
    win.cc.session(payload);
  });

  const cellFlags = (selector) =>
    Array.from(win.document.querySelectorAll(selector)).map((el) => el.classList.contains('strip-cell'));

  it('marks every cell of every row as a cell, and nothing else', () => {
    expect(cellFlags('.readout .ro-item')).toEqual([true, true, true, true]);
    expect(cellFlags('.dash-mini-grid .mini-fact')).toEqual([true, true, true, true, true]);
    expect(cellFlags('.usage-bars .ub-item')).toEqual([true, true, true]);
    expect(cellFlags('.usage-bars .ub-reset')).toEqual([false, false, false]);
    expect(cellFlags('.dash-mini-grid .mini-val')).toEqual([false, false, false, false, false]);
  });

  it('is painted in the gap without taking a pixel from the cell', () => {
    expect(rule('.strip-cell')).toMatch(/padding-right:\s*var\(--strip-gap\)/);
    expect(rule('.strip-cell')).toMatch(/margin-right:\s*calc\(-1 \* var\(--strip-gap\)\)/);
    expect(rule('.strip-cell::after')).toMatch(/position:\s*absolute/);
    expect(rule('.strip-cell::after')).toMatch(/right:\s*0/);
    expect(rule('.strip-cell::after')).toMatch(/width:\s*var\(--strip-gap\)/);
    expect(rule('.strip-cell::after')).not.toMatch(/right:\s*-/);
    expect(rule('.strip-cell::after')).not.toMatch(/calc\(-/);
    expect(rule('.strip-cell')).toMatch(/position:\s*relative/);
    expect(rule('.usage-bars .ub-item')).toMatch(/overflow:\s*hidden/);
    expect(rule('.mini-fact')).toMatch(/overflow:\s*hidden/);
  });

  it('never dangles: no separator after the last cell with content, nor in the last column', () => {
    const given = bodyAt(sheet.indexOf('\n.strip-cell:last-child,'));
    expect(given).toMatch(/padding-right:\s*0/);
    expect(given).toMatch(/margin-right:\s*0/);
    expect(bodyAt(sheet.indexOf('\n.strip-cell:last-child::after,'))).toMatch(/content:\s*none/);
    expect(sheet).toMatch(/\.strip-cell:nth-child\(4n\)\s*\{/);
    expect(sheet).toMatch(/\.strip-cell:nth-child\(4n\)::after/);
  });

  it('drops the separator that pointed at a folded column, by the same rule', () => {
    expect(foldMedia()).toMatch(/\.strip-cell:nth-child\(2n\)::after \{\s*content: none;/);
    expect(foldMedia()).toMatch(/\.strip-cell:nth-child\(2n\) \{[^}]*padding-right:\s*0/);
  });

  it('keys every positional rule on a row, never on the strip, so moving a row breaks nothing', () => {
    expect(sheet).not.toMatch(/#composer\s*>[^{,]*:(nth-child|first-child|last-child)/);
    [
      '.strip-cell:last-child',
      '.strip-cell:nth-child(4n)',
      '.mini-line > .mini-fact:first-child',
      '.mini-line > .mini-fact.mini-fill:nth-child(2):last-child',
      '.readout .ro-item:first-child',
    ].forEach((selector) => expect(sheet).toContain(selector));
    expect(foldMedia()).toMatch(/\.strip-cell:nth-child\(n\s*\+\s*3\)/);
  });

  it('is decoration and stays out of the accessible name', () => {
    expect(rule('.strip-cell::after')).toMatch(/content:\s*'·'\s*\/\s*''/);
    ['.readout', '.dash-mini', '.usage-bars'].forEach((row) => {
      expect(win.document.querySelector(row).textContent).not.toContain('·');
    });
    expect(rule('.strip-cell::after')).toMatch(/pointer-events:\s*none/);
  });

  it('keeps a plan window whole: its bar and its countdown are one cell', () => {
    const items = Array.from(win.document.querySelectorAll('.usage-bars .ub-item'));
    expect(items).toHaveLength(3);
    items.forEach((item) => {
      expect(item.querySelector('.ub-row .ub-track')).not.toBeNull();
      expect(item.querySelector('.ub-reset')).not.toBeNull();
      expect(item.classList.contains('strip-cell')).toBe(true);
    });
    expect(sheet).not.toMatch(/\.ub-reset[^{]*\{[^}]*display:\s*none/);
    expect(sheet).not.toMatch(/\.ub-row[^{]*\{[^}]*display:\s*none/);
    expect(foldMedia()).toMatch(/:not\(\.strip-open\) \.strip-cell:nth-child\(n\s*\+\s*3\)/);
    expect(foldMedia()).not.toMatch(/\.ub-|\.ro-item|\.mini-fact/);
  });
});

describe('strip fold', () => {
  let win;
  const base = { running: true, starting: false };

  beforeEach(() => {
    win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
  });

  const btn = () => win.document.querySelector('.strip-more');
  const push = () => {
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Current session', pct: 13 }] });
    win.cc.session({ model: 'opus[1m]', cwd: '/home/dev/project', account: { email: 'dev@example.com' } });
  };

  it('does not exist while there is nothing behind it', () => {
    expect(btn()).toBeNull();
    push();
    expect(btn()).not.toBeNull();
  });

  it('is only rendered where four columns do not fit', () => {
    expect(rule('.strip-more')).toMatch(/display:\s*none/);
    expect(rule('.strip-more')).not.toMatch(/visibility/);
    expect(rule('.strip-more')).not.toMatch(/opacity/);
    expect(foldMedia()).toMatch(/\.strip-more \{\s*display: flex;/);
    expect(rule('.strip-more')).toMatch(/width:\s*fit-content/);
  });

  it('opens and shuts, and says which it is in text as well as programmatically', () => {
    push();
    const composer = win.document.getElementById('composer');
    expect(btn().getAttribute('aria-expanded')).toBe('false');
    expect(btn().textContent).toBe('Show more');
    expect(composer.classList.contains('strip-open')).toBe(false);

    btn().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(btn().getAttribute('aria-expanded')).toBe('true');
    expect(btn().textContent).toBe('Show less');
    expect(composer.classList.contains('strip-open')).toBe(true);

    btn().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(btn().getAttribute('aria-expanded')).toBe('false');
    expect(composer.classList.contains('strip-open')).toBe(false);
  });

  it('survives a repush of the same payload, and never grows a second button', () => {
    push();
    btn().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    push();
    push();
    expect(win.document.querySelectorAll('.strip-more')).toHaveLength(1);
    expect(btn().getAttribute('aria-expanded')).toBe('true');
    expect(btn().textContent).toBe('Show less');
    expect(win.document.getElementById('composer').classList.contains('strip-open')).toBe(true);
  });

  it('hides whole columns, out of sight and out of the tab order', () => {
    expect(foldMedia()).toMatch(/\.strip-cell:nth-child\(n\s*\+\s*3\) \{\s*display: none;/);
    expect(foldMedia()).not.toMatch(/visibility:\s*hidden/);
    expect(foldMedia()).not.toMatch(/opacity:\s*0/);
    expect(foldMedia()).toMatch(/--strip-cols:\s*repeat\(2, minmax\(0, 1fr\)\)/);
  });

  it('names three containers that exist, so nothing it controls can be a dangling reference', () => {
    push();
    const ids = btn().getAttribute('aria-controls').split(/\s+/);
    expect(ids).toHaveLength(3);
    ids.forEach((id) => expect(win.document.getElementById(id)).not.toBeNull());
    expect(btn().getAttribute('type')).toBe('button');
  });

  it('cannot animate past the reduced-motion switch', () => {
    const universal = bodyAt(sheet.indexOf('\nbody.reduced-motion *,'));
    expect(universal).toMatch(/transition-duration:\s*0\.001ms\s*!important/);
    expect(universal).toMatch(/animation-duration:\s*0\.001ms\s*!important/);
    expect(rule('.strip-more')).not.toMatch(/animation/);
    expect(foldMedia()).not.toMatch(/animation|transition/);
  });
});
