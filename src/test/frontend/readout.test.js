// The composer readout: a zero is a measurement, an absence is not.
//
// These items used to be hidden until they were non-zero, which made a fresh tab show a lone "Idle" with no
// numbers — indistinguishable from a readout that failed to load. That ambiguity cost real debugging time, so
// the settled-at-zero behaviour is pinned here rather than left as a style someone tidies away.
const { loadFrontend } = require('./helpers/load');

describe('composer readout', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const readoutText = () => win.document.querySelector('.readout').textContent;

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

// The plan-limit bars: their own row, under the readout.
//
// They were dots inline in the readout, i.e. at the end of a wrapping row of unrelated metrics — so the windows
// nearest their cap were the ones most likely to wrap out of sight. The separation is the point of the row, and
// it is what these pin: the bars are a SIBLING of .readout, not inside it.
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
    // Sibling, not descendant: the readout must be able to grow without displacing them.
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
    // A window the server reports over its cap is exactly the figure the user needs to read; what must not
    // happen is the fill overflowing its track.
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Over', pct: 103 }] });
    expect(items()[0].querySelector('.ub-track > i').style.width).toBe('100%');
    expect(items()[0].querySelector('.ub-pct').textContent).toBe('103.0%');
  });

  it('shows how long each window has left, compactly, with the sentence in the tooltip', () => {
    // A percentage alone does not say whether it is urgent: 90% with eight minutes to go and 90% with six
    // hours to go are different situations, and only the dashboard was answering that.
    const in90min = new Date(Date.now() + 90 * 60000).toISOString();
    win.cc.state({
      ...base,
      usage: [
        { key: 'five_hour', label: 'Current session', pct: 90, resetsAt: in90min },
        { key: 'seven_day', label: 'All models', pct: 9 },
      ],
    });
    expect(items()[0].querySelector('.ub-reset').textContent).toBe('Reset time: 1h 30m');
    // Its own line under the bar row, not a fourth item squeezed into it.
    expect(items()[0].querySelector('.ub-row .ub-reset')).toBeNull();
    expect(items()[0].querySelector('.ub-row .ub-pct')).not.toBeNull();
    expect(items()[0].getAttribute('title')).toContain('Resets in 1h 30m');
    // A window with no reset time gets no element at all — an empty slot would read as "resets now".
    expect(items()[1].querySelector('.ub-reset')).toBeNull();
    expect(items()[1].getAttribute('title')).not.toContain('Resets');
  });

  it('says "soon" once the reset time has passed rather than a negative countdown', () => {
    const past = new Date(Date.now() - 60000).toISOString();
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Over', pct: 100, resetsAt: past }] });
    expect(items()[0].querySelector('.ub-reset').textContent).toBe('Reset time: soon');
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
});
