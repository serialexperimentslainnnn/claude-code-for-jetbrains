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
