// Every top-level transcript row enters the same way.
//
// From a real report: the chat "felt rigid" even though the entrance animation demonstrably worked. `.msg` and
// `.elicit-card` rose into place, but `.tool`, `.fold` and `.notice` snapped in — and a normal turn is mostly
// tool cards and thought-process folds, so what the user saw was a rigid transcript with two smooth
// exceptions. The rule covers the row-level containers, not their contents: a partial answer reads WORSE than
// none here, because it is the mix of smooth and abrupt that draws the eye to the seam.
const fs = require('fs');

const CSS = fs.readFileSync('src/main/resources/jcef/app.css', 'utf8');

/** The declaration block of a TOP-LEVEL rule, or null when the selector has no rule of its own. */
function blockFor(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = CSS.match(new RegExp('^' + escaped + '\\s*\\{([^}]*)\\}', 'm'));
  return match ? match[1] : null;
}

// Selectors as the stylesheet actually writes them — `details.fold`, not `.fold`. Asserting on the real
// selector is the point: a test that guesses at one is a test that passes for the wrong reason the day
// someone renames it.
describe('css contract — transcript rows share one entrance animation', () => {
  for (const selector of ['.msg', '.tool', 'details.fold', '.notice', '.elicit-card']) {
    it(`${selector} animates on entrance`, () => {
      const block = blockFor(selector);
      expect(block, `no top-level rule found for ${selector}`).not.toBeNull();
      expect(block).toMatch(/animation:\s*(rise|pop)\b/);
    });
  }

  it('rise is a fade UPWARD, not merely a fade', () => {
    // The requested feel is "a soft fade upward", so the keyframe has to move as well as reveal. A pure
    // opacity ramp is what this degrades into when someone trims it, and it reads as flat.
    const rise = CSS.match(/@keyframes rise\s*\{([\s\S]*?)\n\}/);
    expect(rise).not.toBeNull();
    expect(rise[1]).toMatch(/opacity:\s*0/);
    expect(rise[1]).toMatch(/translateY\(/);
  });
});
