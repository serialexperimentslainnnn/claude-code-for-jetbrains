const { readCss } = require('./helpers/load');

const CSS = readCss();

function blockFor(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = CSS.match(new RegExp('^' + escaped + '\\s*\\{([^}]*)\\}', 'm'));
  return match ? match[1] : null;
}

describe('css contract — transcript rows share one entrance animation', () => {
  for (const selector of ['.msg', '.tool', 'details.fold', '.notice', '.elicit-card']) {
    it(`${selector} animates on entrance`, () => {
      const block = blockFor(selector);
      expect(block, `no top-level rule found for ${selector}`).not.toBeNull();
      expect(block).toMatch(/animation:\s*(rise|pop)\b/);
    });
  }

  it('rise is a fade UPWARD, not merely a fade', () => {
    const rise = CSS.match(/@keyframes rise\s*\{([\s\S]*?)\n\}/);
    expect(rise).not.toBeNull();
    expect(rise[1]).toMatch(/opacity:\s*0/);
    expect(rise[1]).toMatch(/translateY\(/);
  });
});
