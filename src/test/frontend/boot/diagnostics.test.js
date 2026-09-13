const { loadFrontend } = require('../helpers/load');

function capture(win) {
  const sent = [];
  win.__ccSend = (json) => sent.push(JSON.parse(json));
  return sent;
}

describe('page diagnostics reach the host', () => {
  it('an uncaught error is reported under the name the host parses', () => {
    const win = loadFrontend([]);
    const sent = capture(win);

    win.dispatchEvent(new win.ErrorEvent('error', { error: new Error('boom') }));

    const report = sent.find((m) => typeof m.report === 'string' && m.report.startsWith('uncaught error'));
    expect(report).toBeDefined();
    expect(report.type).toBe('diag');
    expect(report.report).toContain('boom');
  });

  it('the self-check reports a missing method under the same name', () => {
    const win = loadFrontend([]);
    const sent = capture(win);
    delete win.cc.settingsMenu;

    win.CC.selfCheck();

    const report = sent.find((m) => typeof m.report === 'string' && m.report.startsWith('uncaught missing'));
    expect(report).toBeDefined();
    expect(report.type).toBe('diag');
    expect(report.report).toContain('cc.settingsMenu');
  });
});
