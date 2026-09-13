const { loadFrontend } = require('../helpers/load');

const LINE = (seq, over = {}) => ({
  seq,
  at: 1700000000000 + seq * 1000,
  level: 'info',
  category: 'session.SessionLifecycle',
  text: 'line ' + seq,
  ...over,
});

const PAYLOAD = (lines, over = {}) => ({
  debug: false,
  reset: true,
  ring: { max: 2000, dropped: 0 },
  lines,
  ...over,
});

describe('the Log view', () => {
  let win;
  let sent;

  const panel = () => win.document.querySelector('.dashboard');
  const openView = (name) => {
    const btn = win.document.querySelector('.dash-toggle[data-view="' + name + '"]');
    btn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    return btn;
  };
  const lines = () => Array.from(panel().querySelectorAll('.log-line'));
  const shown = () => lines().filter((l) => !l.hidden);
  const texts = () => shown().map((l) => l.querySelector('.log-text').textContent);
  const chip = (level) => panel().querySelector('.log-chip[data-log-level="' + level + '"]');
  const click = (el) => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  const requests = () => sent.filter((m) => m.type === 'logLines');

  beforeEach(() => {
    vi.useFakeTimers();
    win = loadFrontend(['app-session.js', 'app-transcript.js', 'app-composer.js'], { vendor: false });
    sent = [];
    win.__ccSend = (json) => sent.push(JSON.parse(json));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('has a button of its own in the views row, always', () => {
    const btn = win.document.querySelector('.dash-toggle[data-view="log"]');
    expect(btn).not.toBeNull();
    expect(btn.hidden).toBe(false);
    expect(btn.textContent).toBe('Log');
  });

  it('opening it asks the host for everything, with a cursor before the first line', () => {
    openView('log');
    expect(requests()).toEqual([{ type: 'logLines', since: -1 }]);
  });

  it('keeps asking while it is open, from the last line it has, and stops when it is hidden', () => {
    openView('log');
    win.cc.log(PAYLOAD([LINE(0), LINE(1)]));
    vi.advanceTimersByTime(1000);
    expect(requests().pop()).toEqual({ type: 'logLines', since: 1 });

    openView('session');
    const before = requests().length;
    vi.advanceTimersByTime(5000);
    expect(requests().length).toBe(before);
  });

  it('draws the lines the host sent, with their level, category and text', () => {
    openView('log');
    win.cc.log(PAYLOAD([LINE(0, { level: 'warn', text: 'claude stderr: boom' })]));
    const line = lines()[0];
    expect(line.classList.contains('warn')).toBe(true);
    expect(line.querySelector('.log-level').textContent).toBe('warn');
    expect(line.querySelector('.log-category').textContent).toBe('session.SessionLifecycle');
    expect(line.querySelector('.log-text').textContent).toBe('claude stderr: boom');
  });

  it('appends new lines without recreating the ones already drawn', () => {
    openView('log');
    win.cc.log(PAYLOAD([LINE(0), LINE(1)]));
    const first = lines()[0];
    win.cc.log(PAYLOAD([LINE(2)], { reset: false }));
    expect(texts()).toEqual(['line 0', 'line 1', 'line 2']);
    expect(lines()[0]).toBe(first);
  });

  it('a poll keeps the Lines card and its list in place, so the scroll position survives', () => {
    openView('log');
    win.cc.log(PAYLOAD([LINE(0), LINE(1)]));
    const cardBefore = panel().querySelector('[data-card="log-entries"]');
    const listBefore = panel().querySelector('.log-entries');
    win.cc.log(PAYLOAD([LINE(2)], { reset: false }));
    win.cc.log(PAYLOAD([], { reset: false }));
    expect(panel().querySelector('[data-card="log-entries"]')).toBe(cardBefore);
    expect(panel().querySelector('.log-entries')).toBe(listBefore);
    expect(listBefore.parentNode).toBe(cardBefore.querySelector('.log-entries').parentNode);
  });

  it('a reset empties the list before drawing, because the reader missed lines', () => {
    openView('log');
    win.cc.log(PAYLOAD([LINE(0), LINE(1)]));
    win.cc.log(PAYLOAD([LINE(7)], { reset: true }));
    expect(texts()).toEqual(['line 7']);
  });

  it('ignores a line it already has', () => {
    openView('log');
    win.cc.log(PAYLOAD([LINE(0), LINE(1)]));
    win.cc.log(PAYLOAD([LINE(1), LINE(2)], { reset: false }));
    expect(texts()).toEqual(['line 0', 'line 1', 'line 2']);
  });

  it('the level chips hide lines below the chosen floor and keep their nodes', () => {
    openView('log');
    win.cc.log(
      PAYLOAD([LINE(0, { level: 'warn' }), LINE(1, { level: 'info' }), LINE(2, { level: 'debug' })])
    );
    const debugNode = lines()[2];
    click(chip('info'));
    expect(texts()).toEqual(['line 0', 'line 1']);
    expect(chip('info').getAttribute('aria-pressed')).toBe('true');
    expect(chip('all').getAttribute('aria-pressed')).toBe('false');
    click(chip('all'));
    expect(lines()[2]).toBe(debugNode);
    expect(texts().length).toBe(3);
  });

  it('the Debug switch tells the host and asks again, and paints the state the host confirms', () => {
    openView('log');
    win.cc.log(PAYLOAD([]));
    const sw = panel().querySelector('.log-switch');
    expect(sw.getAttribute('role')).toBe('switch');
    expect(sw.getAttribute('aria-checked')).toBe('false');
    click(sw);
    expect(sent.some((m) => m.type === 'logDebug' && m.on === true)).toBe(true);
    expect(requests().length).toBe(2);
    win.cc.log(PAYLOAD([], { reset: false, debug: true }));
    expect(panel().querySelector('.log-switch').getAttribute('aria-checked')).toBe('true');
  });

  it('Copy asks the host for a report at the chosen level and flashes', () => {
    openView('log');
    win.cc.log(PAYLOAD([LINE(0)]));
    click(chip('warn'));
    const copy = panel().querySelector('.log-copy');
    click(copy);
    expect(sent.pop()).toEqual({ type: 'logCopy', level: 'warn' });
    expect(panel().querySelector('.log-copy').textContent).toBe('Copied');
  });

  it('says nothing has been logged rather than drawing an empty list', () => {
    openView('log');
    win.cc.log(PAYLOAD([]));
    expect(panel().querySelector('.log-empty')).not.toBeNull();
  });

  it('survives a transcript clear, because it is not a child of the transcript', () => {
    openView('log');
    win.cc.log(PAYLOAD([LINE(0)]));
    win.cc.clear();
    expect(texts()).toEqual(['line 0']);
  });

  it('treats a log line as text — it can quote anything the binary printed', () => {
    const nasty = '<img src=x onerror="window.__pwned=1">';
    openView('log');
    win.cc.log(PAYLOAD([LINE(0, { text: nasty })]));
    expect(lines()[0].querySelector('.log-text').textContent).toBe(nasty);
    expect(panel().querySelector('img')).toBeNull();
    expect(win.__pwned).toBeUndefined();
  });
});
