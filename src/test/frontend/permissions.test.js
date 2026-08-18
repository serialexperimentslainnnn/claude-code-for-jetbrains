// Permission cards (app-permissions.js). Covers the 4.0.5 change (a read-only diff replaced the per-line hunk
// checkboxes), the 4.0.4 fix (cards reconcile by id on re-push instead of wiping the region's innerHTML), and the
// GUARD ALERT card — the one a rule the user switched off produces, which has to be unmistakable.
const { loadFrontend, readCss } = require('./helpers/load');

const editCard = (id, diff) => ({
  id,
  tool: 'Edit',
  title: 'Edit',
  summary: `Edit on ${id}.txt`,
  headline: `Edit ${id}`,
  reviewable: true,
  isPlan: false,
  diff,
});

describe('permission card — read-only diff, no per-line checkboxes', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-permissions.js']);
    win.cc.permissions([editCard('req1', '@@ -1 +1 @@\n-old value\n+new value\n context')]);
  });

  it('renders a colour-coded read-only diff (dl-add / dl-del), not hunk checkboxes', () => {
    const region = win.CC.els.permissions;
    expect(region.querySelector('.perm-diff')).not.toBeNull();
    expect(region.querySelector('.perm-diff .diff-line.dl-add').textContent).toContain('new value');
    expect(region.querySelector('.perm-diff .diff-line.dl-del').textContent).toContain('old value');
    // The old per-hunk checkbox UI is gone (accepting a subset of an edit broke code).
    expect(region.querySelector('.perm-hunk')).toBeNull();
    expect(region.querySelector('input[type="checkbox"]')).toBeNull();
  });

  it('Accept / Reject resolve the exact request id', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    const buttons = [...win.CC.els.permissions.querySelectorAll('.btn')];
    buttons.find((b) => b.textContent.trim() === 'Accept').click();
    buttons.find((b) => b.textContent.trim() === 'Reject').click();
    expect(sent).toContainEqual({ type: 'resolvePermission', id: 'req1', allow: true });
    expect(sent).toContainEqual({ type: 'resolvePermission', id: 'req1', allow: false });
  });
});

describe('permission cards — reconcile by id on re-push', () => {
  it('keeps the existing card DOM node when the region is re-pushed (no innerHTML wipe)', () => {
    const win = loadFrontend(['app-permissions.js']);
    win.cc.permissions([editCard('reqA', '@@\n-a\n+b')]);
    const region = win.CC.els.permissions;
    const nodeA1 = region.querySelector('[data-card-id="reqA"]');
    expect(nodeA1).not.toBeNull();

    // A second card arrives — the first card's node must survive (its in-progress input would otherwise be wiped).
    win.cc.permissions([editCard('reqA', '@@\n-a\n+b'), editCard('reqB', '@@\n-c\n+d')]);
    const nodeA2 = region.querySelector('[data-card-id="reqA"]');
    const nodeB = region.querySelector('[data-card-id="reqB"]');
    expect(nodeA2).toBe(nodeA1); // SAME element, not rebuilt
    expect(nodeB).not.toBeNull();

    // Resolving reqA removes only it.
    win.cc.permissions([editCard('reqB', '@@\n-c\n+d')]);
    expect(region.querySelector('[data-card-id="reqA"]')).toBeNull();
    expect(region.querySelector('[data-card-id="reqB"]')).toBe(nodeB);
  });
});

// Keeping the NODE is only half of keeping the card: a node re-appended to the parent it is already in is
// removed and inserted again — the DOM has no move — and a subtree that leaves the document comes back with
// its scroll offsets at zero and its focus gone. Both were happening on every push, and every push is a
// second request arriving or another one resolving, which is exactly when there is something on screen to
// lose. The card body is a scroll container (`.perm-diff`, capped at 28vh), so a real diff scrolls.
describe('permission cards — a push that changes nothing moves nothing', () => {
  const elicitCard = (id) => ({
    id,
    tool: 'Mcp',
    title: 'Input requested',
    elicitation: { description: 'Credentials', fields: [{ name: 'user', title: 'User', required: true }] },
  });

  /** Every childList mutation the region records while `fn` runs, as [removed, added] pairs. */
  const mutations = (win, region, fn) => {
    const records = [];
    const observer = new win.MutationObserver((list) => list.forEach((m) => records.push(m)));
    observer.observe(region, { childList: true });
    fn();
    observer.takeRecords().forEach((m) => records.push(m));
    observer.disconnect();
    return records.map((m) => [m.removedNodes.length, m.addedNodes.length]);
  };

  it('re-inserts nothing when the same list is pushed again', () => {
    const win = loadFrontend(['app-permissions.js']);
    const region = win.CC.els.permissions;
    const list = [editCard('reqA', '@@\n-a\n+b'), editCard('reqB', '@@\n-c\n+d')];
    win.cc.permissions(list);

    // The identical push the host makes on any permission change. `appendChild` on a node already in place
    // reports a removal AND an addition — measured here, not assumed — so the assertion is that there is no
    // record at all, which is the only state in which the offset and the caret survive.
    expect(mutations(win, region, () => win.cc.permissions(list))).toEqual([]);
  });

  it('keeps the caret in the field being typed into while another request arrives', () => {
    const win = loadFrontend(['app-permissions.js']);
    const region = win.CC.els.permissions;
    win.cc.permissions([elicitCard('reqA')]);
    const field = region.querySelector('[data-card-id="reqA"] input[name="user"]');
    field.focus();
    expect(win.document.activeElement).toBe(field);

    // A second card arriving must not evict the first one from the document: jsdom blurs a focused element
    // whose subtree is detached, exactly as the browser does.
    win.cc.permissions([elicitCard('reqA'), editCard('reqB', '@@\n-c\n+d')]);
    expect(win.document.activeElement).toBe(field);
  });

  it('still puts a reordered list in the order the host sent', () => {
    const win = loadFrontend(['app-permissions.js']);
    const region = win.CC.els.permissions;
    win.cc.permissions([editCard('reqA', '@@\n-a\n+b'), editCard('reqB', '@@\n-c\n+d')]);
    const ids = () => [...region.children].map((n) => n.getAttribute('data-card-id'));
    expect(ids()).toEqual(['reqA', 'reqB']);

    // Only what MOVED may move: the region ends in the pushed order, and the untouched card is still the
    // same node it was.
    const nodeB = region.querySelector('[data-card-id="reqB"]');
    win.cc.permissions([editCard('reqB', '@@\n-c\n+d'), editCard('reqA', '@@\n-a\n+b')]);
    expect(ids()).toEqual(['reqB', 'reqA']);
    expect(region.querySelector('[data-card-id="reqB"]')).toBe(nodeB);
  });
});

// ── the guard alert card ─────────────────────────────────────────────────────────────────────────────────────
// A card that exists because `SensitiveGuard` has something to say about the call, and NOT because the permission
// mode would have asked anyway. Since an ENFORCED rule denies outright, this is what the user sees for a rule they
// switched OFF: the guard reporting what it would otherwise have stopped. It has to be impossible to mistake for an
// ordinary request, and the reason it fired has to be readable without opening Settings.
//
// NB this fixture carries NO filesystem path, real or invented, and that is deliberate twice over: the page renders
// whatever text it is handed, so a path would prove nothing here — and every string in this file is a candidate the
// live guard judges when the file is edited, so a path-shaped fixture makes the test unmaintainable by the very rule
// it is testing. What the assertions are about is the rule NAME and the wording, not a location.
const guardCard = (id, guard) => ({
  id,
  tool: 'Read',
  title: 'Claude wants to use Read',
  summary: 'a key file',
  headline: 'Read',
  reviewable: false,
  isPlan: false,
  guard: guard || {
    rule: 'CREDENTIALS',
    label: 'Block credential files',
    category: 'Sensitive data',
    reason:
      'reads credentials or key material (downgraded to a prompt: disabled in Settings ▸ Claude Code ▸ Security)',
  },
});

describe('permission card — guard alert', () => {
  let win;
  let region;
  beforeEach(() => {
    win = loadFrontend(['app-permissions.js']);
    region = win.CC.els.permissions;
  });

  it('marks the card so the stylesheet can make it unmistakable, and only that card', () => {
    win.cc.permissions([guardCard('g1'), editCard('e1', '@@\n-a\n+b')]);
    expect(region.querySelector('[data-card-id="g1"]').classList.contains('perm-guard')).toBe(true);
    expect(region.querySelector('[data-card-id="e1"]').classList.contains('perm-guard')).toBe(false);
  });

  it('says in TEXT that it is a guard alert — colour and motion are not information', () => {
    // WCAG 1.4.1: the red pulse is the attention-getter, the words are the message. They also survive
    // forced-colors mode, a greyscale screenshot in a bug report, and a screen reader.
    win.cc.permissions([guardCard('g1')]);
    const badge = region.querySelector('[data-card-id="g1"] .guard-badge');
    expect(badge).not.toBeNull();
    expect(badge.textContent.trim()).toBe('Guard alert');
    // The headline the card would have had anyway is still there, beside the badge rather than replaced by it.
    expect(region.querySelector('[data-card-id="g1"] .perm-head').textContent).toContain('Read');
  });

  it('names the exact rule, in both the vocabularies that matter', () => {
    win.cc.permissions([guardCard('g1')]);
    const row = region.querySelector('[data-card-id="g1"] .guard-rule');
    // The id is what makes a false-positive report actionable; the label + category are how the user finds the
    // switch in Settings, which is the only thing that can change the outcome.
    expect(row.querySelector('.guard-rule-id').textContent).toBe('CREDENTIALS');
    expect(row.textContent).toContain('Block credential files');
    expect(row.textContent).toContain('Sensitive data');
  });

  it("shows the guard's own sentence, including where the switch is", () => {
    win.cc.permissions([guardCard('g1')]);
    const reason = region.querySelector('[data-card-id="g1"] .guard-reason').textContent;
    expect(reason).toContain('credentials or key material');
    expect(reason).toContain('Settings ▸ Claude Code ▸ Security');
  });

  it('puts the finding ABOVE the command it is about', () => {
    // Why this is being asked is what has to be read first; a summary above it turns the alert into a footnote.
    win.cc.permissions([guardCard('g1')]);
    const body = region.querySelector('[data-card-id="g1"] .perm-body');
    const classes = [...body.children].map((n) => n.className);
    expect(classes.indexOf('guard-rule')).toBeLessThan(classes.indexOf('perm-summary'));
  });

  it('renders as an ordinary card when the host sends no guard block', () => {
    win.cc.permissions([editCard('e1', '@@\n-a\n+b')]);
    const card = region.querySelector('[data-card-id="e1"]');
    expect(card.querySelector('.guard-badge')).toBeNull();
    expect(card.querySelector('.guard-rule')).toBeNull();
    expect(card.querySelector('.guard-reason')).toBeNull();
  });

  it('survives a guard block missing its optional halves rather than printing "undefined"', () => {
    win.cc.permissions([guardCard('g1', { rule: 'TEMP_DIR' })]);
    const card = region.querySelector('[data-card-id="g1"]');
    expect(card.querySelector('.guard-rule-id').textContent).toBe('TEMP_DIR');
    expect(card.textContent).not.toContain('undefined');
    expect(card.querySelector('.guard-reason')).toBeNull();
  });

  it('announces the RULE, ahead of the tool, for anyone who cannot see the card', () => {
    // A guard card is not the same event as an ordinary one and is not announced as one (WCAG 4.1.3). The rule is
    // the reason the turn stopped here, so it is said first — the same order a sighted user reads badge then head.
    win.cc.permissions([guardCard('g1')]);
    const said = win.document.getElementById('a11y-status').textContent;
    expect(said).toContain('Guard alert');
    expect(said).toContain('Block credential files');
    expect(said.indexOf('Guard alert')).toBeLessThan(said.indexOf('Read'));
  });
});

describe('guard alert — the stylesheet contract', () => {
  const css = readCss();

  it('declares every class the card emits', () => {
    // The JS↔CSS contract for this card specifically: an emitted class with no rule is an alert that looks like
    // nothing at all, which is the one failure mode this whole feature exists to prevent.
    ['perm-guard', 'guard-badge', 'guard-rule', 'guard-rule-id', 'guard-reason'].forEach((cls) => {
      expect(css).toContain(`.${cls}`);
    });
  });

  it('pulses, and declares what the pulse falls back to when motion is reduced', () => {
    expect(css).toMatch(/@keyframes guard-alert/);
    expect(css).toMatch(/\.perm-card\.perm-guard\s*\{[^}]*animation:/);
    // The blanket rule in boot.css flattens every animation on `body.reduced-motion`, and this animation's end
    // state is the un-haloed keyframe — so without an explicit fallback the card would silently lose its red ring
    // for exactly the users who cannot see it pulse. Same shape as `.pill-dot.running` in tabs.css.
    expect(css).toMatch(/body\.reduced-motion\s+\.perm-card\.perm-guard/);
  });

  it('keeps the badge visible under forced colors, where background and colour are overridden', () => {
    // `border` is honoured in forced-colors mode; `background` is not. Without this the pill dissolves into the
    // card and the alert reads as an ordinary head.
    expect(css).toMatch(/@media \(forced-colors: active\)\s*\{\s*\.guard-badge/);
  });

  it('uses the danger colour, not the accent — this card is not a normal request', () => {
    const rule = css.match(/\.perm-card\.perm-guard\s*\{([^}]*)\}/)[1];
    expect(rule).toContain('--danger');
  });
});
