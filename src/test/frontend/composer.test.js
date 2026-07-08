// Composer send/stop button (app-composer.js). Covers the interrupt fix: the button reflects idle → stop →
// interrupting, so a running turn can be stopped and an in-flight interrupt shows a disabled "Interrupting…".
const { loadFrontend } = require('./helpers/load');

// Minimal state payload (shape mirrors JcefState.stateJson — only the fields renderState reads here).
function state(extra) {
  return {
    turnActive: false, interrupting: false, running: true,
    provider: { id: 'anthropic', label: 'Anthropic', options: [] },
    model: { label: 'Opus', options: [] },
    mode: { wire: 'default', label: 'Default', options: [] },
    effort: { label: 'Default', options: [] },
    thinking: { on: true, options: [] },
    queue: [],
    ...extra,
  };
}

describe('composer — send/stop/interrupting button', () => {
  let win;
  beforeEach(() => { win = loadFrontend(['app-composer.js']); });

  const sendBtn = () => win.CC.els.composer.querySelector('.send-btn');

  it('idle: the button is a plain Send (no stop/interrupting)', () => {
    win.cc.state(state({ turnActive: false }));
    const b = sendBtn();
    expect(b).not.toBeNull();
    expect(b.classList.contains('stop')).toBe(false);
    expect(b.classList.contains('interrupting')).toBe(false);
    expect(b.title).toBe('Send');
  });

  it('turn active: the button becomes Stop', () => {
    win.cc.state(state({ turnActive: true }));
    const b = sendBtn();
    expect(b.classList.contains('stop')).toBe(true);
    expect(b.classList.contains('interrupting')).toBe(false);
    expect(b.title).toBe('Stop');
  });

  it('interrupting: the button shows a disabled "Interrupting…" state', () => {
    win.cc.state(state({ turnActive: true, interrupting: true }));
    const b = sendBtn();
    expect(b.classList.contains('interrupting')).toBe(true);
    expect(b.title).toBe('Interrupting…');
  });

  it('clicking Stop while a turn is active sends an interrupt', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.state(state({ turnActive: true }));
    sendBtn().click();
    expect(sent).toContainEqual({ type: 'interrupt' });
  });

  it('clicking while interrupting does NOT re-send (button is showing Interrupting…)', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.state(state({ turnActive: true, interrupting: true }));
    sendBtn().click();
    expect(sent.filter((m) => m.type === 'interrupt')).toEqual([]);
  });
});
