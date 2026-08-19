// Permission cards (app-permissions.js). Covers the 4.0.5 change (a read-only diff replaced the per-line hunk
// checkboxes) and the 4.0.4 fix (cards reconcile by id on re-push instead of wiping the region's innerHTML).
const { loadFrontend } = require('./helpers/load');

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

// A card raised by the security guard. It can ONLY exist for a rule the user switched off — an enforced rule is
// denied outright and never becomes a card — which is why the action offered is re-enable and never disable.
const guardCard = (id, guard) => ({
  id,
  tool: 'Bash',
  title: 'Bash',
  summary: 'cat ~/.aws/credentials',
  headline: 'Bash',
  reviewable: false,
  isPlan: false,
  guard,
});

describe('permission card — the guard alert', () => {
  const alert = {
    rule: 'CREDENTIALS',
    label: 'Block credential files',
    category: 'Sensitive data',
    reason: 'reads credentials or key material: ~/.aws/credentials',
  };

  it('names the rule in words, and carries the meaning as TEXT rather than as colour', () => {
    const win = loadFrontend(['app-permissions.js']);
    win.cc.permissions([guardCard('g1', alert)]);
    const region = win.CC.els.permissions;
    // The badge is a word. Colour and motion survive neither forced-colors, a screenshot, nor a reader who
    // cannot tell two hues apart, so the alert must read correctly with every style stripped.
    expect(region.querySelector('.perm-guard-badge').textContent).toBe('Guard alert');
    expect(region.querySelector('.perm-guard-rule').textContent).toContain('Block credential files');
    expect(region.querySelector('.perm-guard-rule').textContent).toContain('Sensitive data');
    expect(region.querySelector('.perm-guard-reason').textContent).toContain('credentials or key material');
  });

  it('offers re-enable — and sends the ONE rule that fired, never a group or a category', () => {
    const win = loadFrontend(['app-permissions.js']);
    win.cc.permissions([guardCard('g1', alert)]);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.CC.els.permissions.querySelector('.perm-guard-restore').click();
    // `on: true` is the ROW's meaning (enforced). The inversion against the stored disabled-set lives in
    // JcefSettingsMenu.applyRule and nowhere else.
    expect(sent).toEqual([{ type: 'settingsToggle', key: 'rule:CREDENTIALS', on: true }]);
  });

  it('is absent from an ordinary permission card, so its presence always means an open lock', () => {
    const win = loadFrontend(['app-permissions.js']);
    win.cc.permissions([editCard('req1', '@@\n-a\n+b')]);
    expect(win.CC.els.permissions.querySelector('.perm-guard')).toBeNull();
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
