const { loadFrontend } = require('../helpers/load');

describe('the Copied flash', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  function copyButton(win) {
    const btn = win.document.createElement('button');
    btn.textContent = 'Copy';
    win.document.body.appendChild(btn);
    return btn;
  }

  it('restores the original label after the flash', () => {
    const win = loadFrontend([], { vendor: false });
    const btn = copyButton(win);
    win.CC.flashCopied(btn);
    expect(btn.textContent).toBe('Copied');
    vi.advanceTimersByTime(1200);
    expect(btn.textContent).toBe('Copy');
    expect(btn.classList.contains('copied')).toBe(false);
  });

  it('a second click during the flash still ends on the original label, not on "Copied"', () => {
    const win = loadFrontend([], { vendor: false });
    const btn = copyButton(win);
    win.CC.flashCopied(btn);
    vi.advanceTimersByTime(600);
    win.CC.flashCopied(btn);
    vi.advanceTimersByTime(1200);
    expect(btn.textContent).toBe('Copy');
    expect(btn.classList.contains('copied')).toBe(false);
  });

  it('the second click extends the flash instead of cutting it short', () => {
    const win = loadFrontend([], { vendor: false });
    const btn = copyButton(win);
    win.CC.flashCopied(btn);
    vi.advanceTimersByTime(1000);
    win.CC.flashCopied(btn);
    vi.advanceTimersByTime(300);
    expect(btn.textContent).toBe('Copied');
    vi.advanceTimersByTime(900);
    expect(btn.textContent).toBe('Copy');
  });
});
