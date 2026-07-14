// Permission cards (app-permissions.js). Covers the 4.0.5 change (a read-only diff replaced the per-line hunk
// checkboxes) and the 4.0.4 fix (cards reconcile by id on re-push instead of wiping the region's innerHTML).
const { loadFrontend } = require('./helpers/load');

const editCard = (id, diff) => ({
  id, tool: 'Edit', title: 'Edit', summary: `Edit on ${id}.txt`, headline: `Edit ${id}`,
  reviewable: true, isPlan: false, diff,
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
