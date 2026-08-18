// The tab bar: the chats, and every subtab of the one you are in.
//
// Four designs came before this one — a capsule per level joined by a measured <svg> thread, then a
// breadcrumb, then a hover menu per segment, then a flat row with the whole tree still a hover away behind a
// `⋮` on every tab — and the rules pinned here are what survived them:
//  - the bar is THREE rows AT MOST, whatever the depth of the tree, and it is one level of hierarchy: the
//    chats · what the CHAT started (its own agents, and its background tasks whoever started them) · and,
//    when you open one of those agents, everything IT started at any depth;
//  - the branch row holds the whole subtree of the agent you opened, NOT the children of whatever pill you
//    last clicked — so moving around inside a branch never reshuffles the row you are reading, nothing at
//    any depth becomes unreachable, and there is never a fourth row;
//  - a background task shows `BT: …` and is ANNOUNCED `Background Task (…)`: the abbreviation is for the
//    eye, and a screen reader would read it out as two letters;
//  - there is no ⋮ and no pin. Both were removed with the tree panel and the pinned-tab concept, and the last
//    test here is the one that keeps them removed: a control nothing emits is a stylesheet rule and a bridge
//    message nobody deletes;
//  - the second row is dragged, wheeled and centred exactly like the first — the same three functions, not a
//    second implementation of them;
//  - a repaint that changes nothing does not rebuild either row, and that has to hold AT DOZENS of pills,
//    which is the session this feature exists for;
//  - BOTH rows are fixed-width, so each is a strip of identical ovals rather than an accordion of blobs, and
//    the subtab row follows the chats' rule two pixels under it in every box measure. The subtab row was
//    content-sized until the pills were rescaled, and the trade is recorded at the rule in `tabs.css`: short
//    labels now cost more scroll distance, long ones stop at the width instead of running to a `ch` cap;
//  - a tab's close is a `<button>` SIBLING of the chat's own button, never a child of it;
//  - a chat that started nothing has no second row;
//  - going back to the chat's own transcript says so with an EMPTY agentId — the host reads a blank id as
//    "the chat", never as an agent whose id is the empty string;
//  - a subtab pill shows the BARE name and is announced with its kind and its state, because a row of pills
//    all beginning with `Agent (` spends the width on the word that repeats — and because colour alone is
//    not allowed to be the only carrier of a state (WCAG 2.2 SC 1.4.1).
const { loadFrontend, readCss } = require('./helpers/load');

describe('tab bar', () => {
  let win;
  let sent;

  const bar = () => win.document.getElementById('tabsbar');

  /**
   * The SECOND row's capsule — what the chat itself started.
   *
   * `:not(.branch-capsule)` is not defensive: the third row carries `.subtab-capsule` too, on purpose (it is
   * the same kind of thing at the same size), so a bare `.subtab-capsule` lookup returns whichever comes
   * first in the document. That would silently keep working and quietly assert about the wrong row the day
   * the order changed.
   */
  const subCapsule = () => bar().querySelector('.subtab-capsule:not(.branch-capsule)');
  /** The THIRD row's capsule — what the agent you opened started. Null while no branch is open. */
  const branchCapsule = () => bar().querySelector('.branch-capsule');
  const branchPills = () => Array.from(branchCapsule() ? branchCapsule().querySelectorAll('.pill-wrap') : []);
  const branchLabels = () => branchPills().map((p) => p.querySelector('.pill-label').textContent);
  /**
   * A TAB is the `.pill-wrap`: the chat's own `<button class="pill">` plus its close as a SIBLING.
   *
   * They used to be spans inside that button, which is interactive content nested where the content model
   * forbids it — invisible to assistive technology, and not reliably clickable in Chromium, which is what
   * made the × dead. So these helpers select the wrapper, and a control is found on IT and never under the
   * pill: a helper that still reached inside would keep passing while the markup that broke came back.
   */
  const subPills = () => Array.from(subCapsule() ? subCapsule().querySelectorAll('.pill-wrap') : []);
  const subLabels = () => subPills().map((p) => p.querySelector('.pill-label').textContent);
  /**
   * The subtab whose transcript is on screen, in EITHER subtab row.
   *
   * Scoped to `.subtab-capsule` and not to the whole bar: the chats' row has a selected pill of its own at
   * all times, and a lookup that caught it would answer "a subtab is open" on a page where none is.
   */
  const openSubPill = () => bar().querySelector('.subtab-capsule .pill-wrap.selected');
  /** The subtab pill carrying [name], in whichever of the two rows it lives. */
  const subPill = (name) =>
    subPills()
      .concat(branchPills())
      .find((p) => p.querySelector('.pill-label').textContent === name);
  /** The tab for a chat by its visible title, wherever its controls live. */
  const chatTab = (title) =>
    Array.from(bar().querySelectorAll('.tab-capsule .pill-wrap')).find(
      (p) => p.querySelector('.pill-label').textContent === title
    );
  /** The subtab whose transcript is on screen — null while that is the chat's own. */
  const subtab = () => {
    const open = openSubPill();
    if (!open) return null;
    const text = open.querySelector('.pill-label').textContent;
    return text === 'Chat' ? null : text;
  };
  /**
   * How the open subtab is ANNOUNCED: the kind and the state live here, not in the visible text.
   *
   * The one helper that reaches THROUGH the wrapper, and the exception is not an oversight. The rule above
   * is about CONTROLS, which must be siblings of the pill and never children of it. An accessible name is
   * the opposite case: `.pill-wrap` is a bare `<div>` with no role and no focus, and `aria-label` on a
   * generic element is ignored, so the name can only live on the control it names. Asking the wrapper
   * returns `null` whatever the product does — a test that cannot pass, rather than one that catches
   * something.
   */
  const subtabName = () => {
    const open = openSubPill();
    return open ? open.querySelector('.pill').getAttribute('aria-label') : null;
  };

  const click = (el) => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

  const TREE = [
    {
      id: 'a',
      parent: null,
      label: 'Inventario de dependencias',
      type: 'general-purpose',
      status: 'running',
    },
    { id: 'b', parent: 'a', label: 'Dependencias de desarrollo', type: 'general-purpose', status: 'running' },
    { id: 'c', parent: 'b', label: 'Dependencias sin usar', type: 'Explore', status: 'completed' },
  ];
  const TASKS = [{ id: 't1', owner: 'a', label: 'npm run dev', type: 'bash', running: true }];
  const CHATS = [{ id: '1', title: 'Chat 1', selected: true }];

  const setup = () => {
    win = loadFrontend(['app-tabs.js'], { vendor: false });
    sent = [];
    win.CC.send = (msg) => sent.push(msg);
    win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
  };

  beforeEach(setup);

  it('at rest it shows the chats and what the CHAT started', () => {
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
    // Nothing but the chat's own transcript is open, and the row says so with its first pill rather than by
    // being absent: a row that appears and disappears moves the transcript under the pointer.
    expect(subtab()).toBe(null);
    // The chat's own agent and its background task — and NOT the subagents, which belong to `a`.
    expect(subLabels()).toEqual(['Chat', 'Inventario de dependencias', 'BT: npm run dev']);
    expect(branchCapsule()).toBe(null);
  });

  it('a subagent is not in the chat’s row: it appears under the agent that invoked it', () => {
    // The whole of the hierarchy decision. `b` hangs off `a` and `c` off `b`; the chat's row carries only
    // `a`, and opening `a` is what reveals the two of them.
    expect(subLabels()).not.toContain('Dependencias de desarrollo');

    click(subPill('Inventario de dependencias').querySelector('.pill'));

    expect(bar().querySelectorAll('.tab-row').length).toBe(3);
    expect(branchLabels()).toEqual(['Dependencias de desarrollo', 'Dependencias sin usar']);
    // The chat's row is untouched by the drill-down: it still lists what the CHAT started.
    expect(subLabels()).toEqual(['Chat', 'Inventario de dependencias', 'BT: npm run dev']);
  });

  it('the agent that owns the branch says so, and it is not the same thing as being open', () => {
    // Two different facts on two different pills, and merging them is the trap. `selected` means "this
    // transcript is on screen" and exactly one pill in the bar wears it; `branch-open` means "the row below
    // is yours", which stays true while you read one of the subagents in it. Here they coincide; the next
    // test is the one where they do not.
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    const owner = subPill('Inventario de dependencias');
    expect(owner.classList.contains('branch-open')).toBe(true);
    expect(owner.querySelector('.pill').getAttribute('aria-expanded')).toBe('true');
    // A disclosure relationship the keyboard can find: the row it opens is named after it, because an indent
    // says nothing to anyone not looking at the screen.
    expect(branchCapsule().getAttribute('aria-label')).toBe('Started by Inventario de dependencias');
  });

  it('moving around INSIDE a branch does not reshuffle it, and never opens a fourth row', () => {
    // The reason the branch row is the whole subtree rather than "the children of what you are looking at".
    // With the latter, clicking `b` would replace the row with `[c]` — the strip moves under the click you
    // just made — and `b` itself would then appear in no row at all, so the bar could no longer say where
    // you are. Here the row is fixed by the BRANCH, so only the accent moves.
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    const before = branchLabels();

    click(subPill('Dependencias de desarrollo').querySelector('.pill'));
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: 'b' });
    expect(branchLabels()).toEqual(before);
    expect(bar().querySelectorAll('.tab-row').length).toBe(3);
    expect(subtab()).toBe('Dependencias de desarrollo');
    // `b` has a child of its own. It gets no row: the branch is `a`'s, and `c` is already in it.
    expect(branchLabels()).toContain('Dependencias sin usar');
    // ...and `a` is still marked as the owner of the row even though its transcript is not the one on screen.
    expect(subPill('Inventario de dependencias').classList.contains('branch-open')).toBe(true);
    expect(subPill('Inventario de dependencias').classList.contains('selected')).toBe(false);
  });

  it('a node three levels deep is reachable, and announced as a subagent', () => {
    // Depth costs one click to open the branch, and no more however deep it goes — the bar stays at three
    // rows and nothing in the tree becomes unreachable.
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    click(subPill('Dependencias sin usar').querySelector('.pill'));

    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: 'c' });
    expect(subtab()).toBe('Dependencias sin usar');
    // The kind and the state are in the accessible name, the way every other view says them.
    expect(subtabName()).toBe('Subagent (Dependencias sin usar)  ·  completed');
    expect(bar().querySelectorAll('.tab-row').length).toBe(3);
  });

  it('the branch row goes away when you go back to the chat', () => {
    // An empty row that still takes height is worse than no row: the transcript would sit lower for no
    // reason anyone could see.
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    expect(branchCapsule()).not.toBe(null);

    click(subPills()[0].querySelector('.pill')); // the `Chat` pill
    expect(branchCapsule()).toBe(null);
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
  });

  it('an agent that started nothing opens no row, and does not claim it could', () => {
    win.cc.tabs({
      chats: CHATS,
      tree: [{ id: 'solo', parent: null, label: 'Nada que delegar', status: 'running' }],
      tasks: [],
    });
    const pill = subPill('Nada que delegar');
    // No `aria-expanded` at all, rather than `false`: on a control that discloses nothing the attribute
    // claims a hidden region that does not exist.
    expect(pill.querySelector('.pill').hasAttribute('aria-expanded')).toBe(false);

    click(pill.querySelector('.pill'));
    expect(branchCapsule()).toBe(null);
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
  });

  it('an agent with children says it can be opened BEFORE it is opened', () => {
    // `aria-expanded="false"` is the half that matters: it is how a screen-reader user learns the control
    // opens something at all, rather than discovering it by pressing everything.
    expect(subPill('Inventario de dependencias').querySelector('.pill').getAttribute('aria-expanded')).toBe(
      'false'
    );
    // A background task discloses nothing and must not pretend otherwise.
    expect(subPill('BT: npm run dev').querySelector('.pill').hasAttribute('aria-expanded')).toBe(false);
  });

  it('the open subtab goes back to the chat, with a blank agentId', () => {
    win.cc.revealAgentTab('b');
    expect(subtab()).toBe('Dependencias de desarrollo');
    click(openSubPill().querySelector('.pill'));
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: '' });
    expect(subtab()).toBe(null);
  });

  it('the Chat pill is the way back, and it is always in the same place', () => {
    win.cc.revealAgentTab('b');
    expect(subLabels()[0]).toBe('Chat');
    click(subPills()[0].querySelector('.pill'));
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: '' });
    expect(subtab()).toBe(null);
  });

  it('a background task is a destination too, and it is shown `BT:` but SAID in full', () => {
    // The two names of one pill, and the reason they are two. Bare, `npm run dev` is indistinguishable from
    // an agent's title — nothing else on a pill says this is a process with output rather than a
    // conversation — so the eye gets a four-character prefix. The ear must not: `BT:` is read out as two
    // letters, so the accessible name stays the written-out kind (WCAG 4.1.2), and with it the state, which
    // on screen is carried by the colour of the dot alone (1.4.1).
    click(subPill('BT: npm run dev').querySelector('.pill'));
    expect(sent.pop()).toEqual({ type: 'revealBackgroundTask', taskId: 't1' });
    expect(subtab()).toBe('BT: npm run dev');
    expect(subtabName()).toContain('Background Task (npm run dev)');
    expect(subtabName()).not.toContain('BT:');
    // And the visible text is contained in neither direction by accident: `title` is the spoken form too, so
    // a voice-control user says what a screen-reader user hears.
    expect(openSubPill().querySelector('.pill').getAttribute('title')).toContain('Background Task (');
  });

  it('an AGENT keeps its bare name — only the task is prefixed', () => {
    // The prefix is not decoration applied to every pill. An agent's kind is already implied by the row it
    // sits in, and at a fixed pill width a repeated word is spent out of the title, which is the part that
    // tells two agents apart. It rides in the accessible name instead, like the state.
    const pill = subPill('Inventario de dependencias').querySelector('.pill');
    expect(pill.textContent).toBe('Inventario de dependencias');
    expect(pill.getAttribute('aria-label')).toContain('Agent (Inventario de dependencias)');
  });

  it('only the OPEN agent subtab carries a close, and closing it is a transcript gesture', () => {
    // The row can hold dozens of pills. Giving every one of them a control would put that many targets a few
    // pixels wide in a 20px row, and the control only ever acts on what you are reading anyway.
    //
    // The message is the load-bearing half. Closing a subtab HIDES a transcript (`closeAgent`); it must never
    // be able to close the chat, because a chat's close disposes its `claude` process — which is exactly the
    // defect that pinned subtabs produced when a subtab was a tab of its own.
    win.cc.revealAgentTab('b');
    const open = openSubPill();
    expect(open.querySelector('.pill-x')).not.toBe(null);
    // BOTH subtab rows, not just the one the open pill happens to be in: `b` is a subagent, so it sits in the
    // branch row while the chat's own agents sit above it, and a check scoped to one row would be blind to
    // half the pills it is about.
    const others = subPills()
      .concat(branchPills())
      .filter((p) => p !== open);
    expect(others.length).toBeGreaterThan(0);
    for (const p of others) {
      expect(p.querySelector('.pill-x')).toBe(null);
    }
    sent = [];
    click(open.querySelector('.pill-x'));
    expect(sent).toEqual([{ type: 'closeAgent', agentId: 'b' }]);
  });

  it('a background task subtab has no close at all', () => {
    // Its row is the plugin's own record of a task and it ages out with the retention window; there is
    // nothing for the user to dismiss, and a control that does nothing is worse than none.
    win.cc.revealTaskTab('t1');
    expect(subtab()).toBe('BT: npm run dev');
    expect(openSubPill().querySelector('.pill-x')).toBe(null);
  });

  /**
   * The × closes the chat — the whole path, from the click to the message.
   *
   * **This test did not exist, and that is why the button spent a day doing nothing.** There were three
   * references to `.pill-x` in this file and all three were about its SHAPE: that it exists on the open
   * subtab, that it does not exist on the others, and that its box is 20px in the stylesheet. Nothing ever
   * pressed it. A control whose only job is to send one message needs a test that presses it and reads the
   * message, or "it renders" and "it works" are the same green.
   */
  it('the × on a chat sends closeChat for THAT chat, and nothing else', () => {
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    const pill = chatTab('Chat 2');
    sent = [];
    click(pill.querySelector('.pill-x'));
    // Exactly one message, and it names the chat the × belongs to — not the selected one, which is the
    // mistake a handler that reads the current selection instead of its own row would make.
    expect(sent).toEqual([{ type: 'closeChat', chatId: '2' }]);
  });

  it('pressing the × does not also select the chat it is closing', () => {
    // Nested, the × sat inside the pill's own hit area, so without `stopPropagation` the click reached the
    // pill's handler as well and closing a background chat first switched to it — a visible jump to a tab
    // that is going away. As a SIBLING that route no longer exists, which is why this assertion stays: what
    // it pins is the shape, and the next arrangement of these controls has to keep it. `stopPropagation`
    // still guards the ancestors the click really does bubble through — the capsule's drag handler watches
    // clicks on their way up.
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    const pill = chatTab('Chat 2');
    sent = [];
    click(pill.querySelector('.pill-x'));
    expect(sent.map((m) => m.type)).not.toContain('selectChat');
  });

  it('the × is named after the chat it closes, not just "Close"', () => {
    // A bar of tabs is a bar of identical glyphs: announced as "Close" they are indistinguishable, and there
    // is no phrase a voice-control user can say to reach one of them in particular (SC 4.1.2, SC 2.5.3). The
    // visible title has to be part of the name.
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    expect(chatTab('Chat 2').querySelector('.pill-x').getAttribute('aria-label')).toContain('Chat 2');
  });

  it('the close is a real button, and a SIBLING of the pill rather than a child of it', () => {
    // The structural assertion the whole de-nesting exists for. The controls used to be
    // `<span role="button">` INSIDE the pill's `<button>`: interactive content in a place the `button`
    // content model forbids. Two things follow. The ARIA `button` role is Children Presentational, so a
    // conforming browser deletes its descendants from the accessibility tree — the × was never announced,
    // and tabbing to it landed on a control with no name (SC 4.1.2). And Chromium's hit-testing for a nested
    // interactive element is not the DOM's: the press never reached the handler, so the × did nothing at
    // all, silently. jsdom dispatches it happily, which is exactly why the suite stayed green while the
    // button was dead.
    //
    // `parentElement` before `closest` is the point of the assertion and not a detour: `closest` matches the
    // element ITSELF, so asking a button whether it sits inside a button always answers yes. The question is
    // about its ANCESTORS.
    win.cc.revealAgentTab('b');
    const ctl = openSubPill().querySelector('.pill-x');
    expect(ctl).not.toBe(null);
    // A real `<button>`, so Enter and Space come with the element instead of a hand-rolled `keydown` pair.
    expect(ctl.tagName).toBe('BUTTON');
    // `type="button"`, not the default `submit`: the page has real forms in it (the elicitation cards).
    expect(ctl.getAttribute('type')).toBe('button');
    expect(ctl.getAttribute('aria-label')).toBeTruthy();
    expect(ctl.parentElement.closest('button')).toBe(null);
  });

  it('the oval is declared on the WRAPPER, which is what puts the × inside the tab', () => {
    // The user asked for the × inside the tab, and the obvious way to give it to them is the markup this
    // whole file exists to keep out: a `<button class="pill-x">` inside the `<button class="pill">`. That is
    // nested interactive content — deleted from the accessibility tree by Children Presentational, and never
    // handed the click by Chromium, which is what made the × dead with no error and no log.
    //
    // So the containment is VISUAL and the DOM is unchanged: the border, the radius, the surface and the
    // selected accent are painted on `.pill-wrap`, which holds both buttons as siblings, and the chat's own
    // button goes transparent inside it. Move the oval back onto `.pill` and the × is outside it again on
    // screen while every other test in this file still passes — the DOM is identical and only the painting
    // moved. That is exactly why this is asserted.
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const block = (selector) => {
      const at = css.indexOf('\n' + selector + ' {');
      return at < 0 ? '' : css.slice(at, css.indexOf('}', at));
    };
    // The oval: the wrapper paints it.
    expect(block('.pill-wrap')).toMatch(/border:\s*1px solid/);
    expect(block('.pill-wrap')).toMatch(/border-radius:\s*var\(--radius-pill\)/);
    expect(block('.pill-wrap')).toMatch(/[\s;]background:/);
    expect(block('.pill-wrap.selected')).toMatch(/border-color:\s*var\(--accent\)/);
    // ...and the button inside paints none of it. All three, because any one left behind draws a second pill
    // inside the first.
    expect(block('.tab-capsule .pill')).toMatch(/border:\s*none/);
    expect(block('.tab-capsule .pill')).toMatch(/background:\s*none/);
    expect(block('.tab-capsule .pill')).toMatch(/padding:\s*0/);
    // The click target stays the whole oval minus the ×. Strip a button's surface and it keeps the size of
    // its text unless told otherwise, which leaves a dead band inside the tab where a click selects nothing —
    // and the tab looks pressable across its whole width while only the letters respond.
    expect(block('.tab-capsule .pill')).toMatch(/flex:\s*1 1 auto/);
    expect(block('.tab-capsule .pill')).toMatch(/align-self:\s*stretch/);
    // `height: auto` is what lets `stretch` apply at all: the base declares an explicit height, and a
    // definite height defeats stretching.
    expect(block('.tab-capsule .pill')).toMatch(/height:\s*auto/);
    // Focus follows the oval, because what takes focus is the button inside it and a ring drawn there is
    // taller than the tab on one axis and stops short of the close on the other. Suppressed WITH a
    // replacement, never merely removed (WCAG 2.2 SC 2.4.7), and the system colour still wins under forced
    // colours.
    expect(block('.tab-capsule .pill:focus-visible')).toMatch(/outline:\s*none/);
    expect(block('.tab-capsule .pill-wrap:has(.pill:focus-visible)')).toMatch(/outline:\s*2px solid/);
    expect(css).toMatch(/forced-colors: active[\s\S]*?\.pill-wrap:has\(\.pill:focus-visible\)/);
    // And in the DOM the two really are siblings under that same wrapper, which is what makes the visual
    // containment honest rather than a trick.
    const wrap = chatTab('Chat 1');
    expect(wrap.classList.contains('pill-wrap')).toBe(true);
    expect(wrap.querySelector('.pill').parentElement).toBe(wrap);
    expect(wrap.querySelector('.pill-x').parentElement).toBe(wrap);
  });

  it('two chats with wildly different titles get the SAME box — width is not a function of the text', () => {
    // The requirement, and the half a stylesheet cannot promise on its own. jsdom lays nothing out and the
    // harness never injects the stylesheet, so the width itself can only be asserted against the CSS (see
    // "the pills are sized to a declared scale"). What is asserted HERE is equality: that nothing the page
    // does makes one tab's box differ from another's. Sized to their titles they were an accordion — three
    // sentences beside a four-letter chat — and the two ways that comes back are a per-tab inline style and
    // a modifier class, both of which live in the DOM and neither of which a CSS assertion can see.
    const long = 'Take this repository and tell me everything that is wrong with it';
    win.cc.tabs({
      chats: [
        { id: '1', title: long, selected: true },
        { id: '2', title: 'test' },
      ],
      tree: [],
      tasks: [],
    });
    const wrapA = chatTab(long);
    const wrapB = chatTab('test');
    const a = wrapA.querySelector('.pill');
    const b = wrapB.querySelector('.pill');
    // `selected` is allowed to differ — it carries colour, weight and height, and deliberately no width.
    // Anything else would be a class that sizes one tab and not the other.
    const shape = (el) =>
      el.className
        .split(/\s+/)
        .filter((c) => c && c !== 'selected')
        .sort()
        .join(' ');
    // The WRAPPER first, because the box is its: the oval, the fixed width and the padding are declared
    // there, so a modifier class or an inline geometry on the wrapper is what would size one tab and not the
    // other. Checking only the button inside it would miss the element that decides the width.
    expect(shape(wrapA)).toBe(shape(wrapB));
    expect(wrapA.getAttribute('style')).toBe(null);
    expect(wrapB.getAttribute('style')).toBe(null);
    expect(shape(a)).toBe(shape(b));
    expect(a.getAttribute('style')).toBe(null);
    expect(b.getAttribute('style')).toBe(null);
    // The title lives in a child that owns the overflow, so the tab is a box the text is poured into rather
    // than a box the text measures out. The label keeps the WHOLE string — the truncation is the CSS's.
    expect(a.querySelector('.pill-label').textContent).toBe(long);
    // ...and the whole of it stays readable somewhere, which matters far more now that a fixed width
    // truncates nearly every chat title on screen.
    expect(a.getAttribute('title')).toBe(long);
    expect(a.getAttribute('aria-label')).toBe(long);
  });

  it('two subtabs with wildly different labels get the SAME box — that row is fixed-width now too', () => {
    // The twin of the test above, and it is here because the subtab row is new to the rule: it was
    // content-sized until the pills were rescaled, and a rule that changed sides is exactly the one with no
    // test on the new side. The stylesheet half is asserted in "the pills are sized to a declared scale";
    // what a CSS assertion cannot see is the page undoing it, and the two ways that happens — a per-pill
    // inline style and a modifier class — live in the DOM. This is the row where it matters most: it carries
    // every agent, subagent and background task of the open chat, so it is the one that reaches dozens of
    // pills whose labels are of completely different natures (`Chat`, a sentence, `npm run dev`).
    const long = 'Revisa el guard de permisos y dime exactamente qué regla falla';
    win.cc.tabs({
      chats: CHATS,
      tree: [
        { id: 'a', parent: null, label: 'x', type: 'Explore', status: 'completed' },
        { id: 'b', parent: null, label: long, type: 'general-purpose', status: 'running' },
      ],
      tasks: [],
    });
    const shortWrap = subPill('x');
    const wideWrap = subPill(long);
    const short = shortWrap.querySelector('.pill');
    const wide = wideWrap.querySelector('.pill');
    // `selected` and `branch-open` are the two STATE classes and are allowed to differ: they carry colour and
    // height, and deliberately no width. Anything else would be a class that sizes one tab and not another.
    const shape = (el) =>
      el.className
        .split(/\s+/)
        .filter((c) => c && c !== 'selected' && c !== 'branch-open')
        .sort()
        .join(' ');
    // The WRAPPER carries the box on this row too — it is the oval, and the fixed width is declared on it.
    expect(shape(shortWrap)).toBe(shape(wideWrap));
    expect(shortWrap.getAttribute('style')).toBe(null);
    expect(wideWrap.getAttribute('style')).toBe(null);
    expect(shape(short)).toBe(shape(wide));
    expect(short.getAttribute('style')).toBe(null);
    expect(wide.getAttribute('style')).toBe(null);
    // The label keeps the WHOLE string — the truncation is the CSS's — and the whole of it stays readable in
    // the accessible name, which is where it has to be at a width that cuts nearly every agent label.
    expect(wide.querySelector('.pill-label').textContent).toBe(long);
    expect(wide.getAttribute('aria-label')).toContain(long);
    expect(wide.getAttribute('title')).toContain(long);
  });

  it('the host can reveal an agent, and clearing it returns to the chat', () => {
    win.cc.revealAgentTab('b');
    // The VISIBLE text is the bare name; the kind and the state are in the accessible name instead. A dozen
    // pills all beginning "Subagent (" spend the only scarce thing in a tool window — width — on the word
    // that repeats.
    expect(subtab()).toBe('Dependencias de desarrollo');
    expect(subtabName()).toContain('Subagent (Dependencias de desarrollo)');
    win.cc.clearAgentSelection();
    expect(subtab()).toBe(null);
  });

  it('an agent that vanished from the payload takes the selection with it', () => {
    win.cc.revealAgentTab('b');
    win.cc.tabs({ chats: CHATS, tree: [TREE[0]], tasks: TASKS });
    expect(subtab()).toBe(null);
  });

  it('a push that changes nothing visible does not rebuild the bar', () => {
    // The host pushes the whole bar on every agent event, several times a turn, and nearly all of those
    // change nothing you can see — an agent's transcript grew. Rebuilding anyway made the row flicker under
    // the pointer. Identity of the DOM node is the check: a rebuild replaces it.
    const before = bar().querySelector('.tab-capsule');
    win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).toBe(before);
    // A real change still redraws.
    win.cc.tabs({ chats: [{ id: '1', title: 'Renamed', selected: true }], tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).not.toBe(before);
  });

  it('a repaint keeps whatever else lives in the bar', () => {
    // `#tabsbar` is a `<nav>` in the shared shell, not this module's element, and the bar is rebuilt on every
    // agent event — several times a turn. A blanket clear therefore deletes any other tenant, which is not a
    // hypothesis: the dashboard's view buttons were mounted here (they have since moved into the composer)
    // and a blanket clear took them with it. The fix is that the rows live in their own `.tab-rows`
    // container and only that is cleared, so this asserts the SCOPE of the clear rather than one tenant.
    const other = win.document.createElement('div');
    other.id = 'a-tenant-of-the-bar';
    bar().appendChild(other);
    const before = bar().querySelector('.tab-capsule');
    // A push that really CHANGES something. An identical one is skipped by the flicker guard, and this test
    // would then be asserting that a render which never ran deleted nothing.
    win.cc.tabs({ chats: [{ id: '1', title: 'Renamed', selected: true }], tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).not.toBe(before);
    expect(win.document.getElementById('a-tenant-of-the-bar')).not.toBe(null);
  });

  it('the overflow is bounded and reachable — the CSS contract that kept breaking', () => {
    // Reported twice, and jsdom lays nothing out, so only the stylesheet can be asserted. Two ways this
    // failed in practice, both invisible to every other test:
    //   1. A container that does not bound its width lets the capsule GROW instead of overflowing, so the
    //      extra tabs are painted outside the tool window and no amount of scrolling reaches them.
    //   2. The scrollbar was hidden (`scrollbar-width: none`) on the grounds that the wheel would do — and
    //      when the wheel did not, there was no way left to even see that there was more.
    // Comments stripped first: the rules below are EXPLAINED at length right above them, and matching raw
    // text would assert against the explanation rather than against the code.
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const block = (selector) => {
      const at = css.indexOf(selector + ' {');
      return at < 0 ? '' : css.slice(at, css.indexOf('}', at));
    };
    for (const sel of ['#tabsbar', '.tab-rows', '.tab-row']) {
      expect(block(sel), `${sel} must bound its width`).toMatch(/max-width:\s*100%/);
    }
    expect(block('.tab-capsule')).toMatch(/overflow-x:\s*auto/);
    // The branch row is INDENTED, and an indent has to be paid for out of the maximum. `margin-left` without
    // a matching reduction pushes exactly that much of the row past its container's `overflow: hidden`, where
    // no wheel and no drag can reach it — which is the failure the three rules above exist for, arriving by a
    // new route. Asserted as the PAIR, because either one alone is the bug.
    expect(block('.branch-capsule')).toMatch(/margin-left:\s*16px/);
    expect(block('.branch-capsule')).toMatch(/max-width:\s*calc\(100% - 16px\)/);
    // No scrollbar to aim at — the row is grabbed, wheeled, or moved by centring the chat you select. The
    // grab needs a cursor that says so, and the drag must not fight the smooth scrolling.
    expect(block('.tab-capsule')).toMatch(/cursor:\s*grab/);
    expect(block('.tab-capsule.dragging')).toMatch(/scroll-behavior:\s*auto/);
    // A tab cannot grow to fit its title, or one named after a long first prompt fills the bar on its own.
    // ONE mechanism, on both rows: a declared width on the TAB, which is the `.pill-wrap` — the wrapper is
    // the oval, and it is what the row lays out. It used to be two mechanisms, a fixed width on the pill and
    // a `ch` cap on the wrapper for the subtab row, and a cap is a maximum rather than a measure, which is
    // what produced the accordion it was supposed to prevent. Either way the extra tabs land in the capsule's
    // `overflow-x` above instead of the row quietly growing past the tool window, which is the failure this
    // test exists for. The exact figures are asserted in "the pills are sized to a declared scale"; what
    // matters here is that a width is declared per row and that no cap is left behind to fight it.
    expect(block('.tab-capsule:not(.subtab-capsule) .pill-wrap')).toMatch(/[\s;]width:\s*\d+px/);
    expect(block('.subtab-capsule .pill-wrap')).toMatch(/[\s;]width:\s*\d+px/);
    // `[\s;]` and not a bare `width:`, or `min-width` would match and the absence below could never hold.
    expect(block('.pill-wrap')).not.toMatch(/max-width:/);
  });

  it('the row is dragged by grabbing it, and the drag does not switch chat on release', () => {
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 200)); // 100px to the left
    expect(capsule.scrollLeft).toBe(100);
    expect(capsule.classList.contains('dragging')).toBe(true);
    win.document.dispatchEvent(at('mouseup', 200));
    expect(capsule.classList.contains('dragging')).toBe(false);

    // Releasing over a tab must NOT also select that chat: the click that ends a drag is swallowed.
    sent.length = 0;
    click(bar().querySelector('.pill'));
    expect(sent).toEqual([]);
    // ...and the very next click, with no drag before it, works normally again.
    click(bar().querySelector('.pill'));
    expect(sent.length).toBe(1);
  });

  it('a movement below the threshold is a click, not a drag', () => {
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 298)); // 2px — a hand resting, not a drag
    win.document.dispatchEvent(at('mouseup', 298));
    expect(capsule.scrollLeft).toBe(0);
    sent.length = 0;
    click(bar().querySelector('.pill'));
    expect(sent.length).toBe(1); // the chat still selects
  });

  // NB this one PASSES WHILE BLIND, and that is worth knowing rather than fixing: jsdom has no scroll methods
  // on Element and treats scrollLeft as a plain property, so it exercises the fallback and can say nothing
  // about the behaviour the real browser applies. It pins the translation of the gesture, no more. The
  // assertion that covers what actually broke is the next test, which looks at the argument.
  it('the wheel scrolls the chats, because a vertical wheel does not move a horizontal row', () => {
    const capsule = bar().querySelector('.tab-capsule');
    // jsdom lays nothing out, so the overflow is simulated. The handler does not measure it — a measurement
    // that says "nothing to scroll" when there is leaves the row inert with no way to tell why — but a row
    // with somewhere to go is the situation being described.
    Object.defineProperty(capsule, 'scrollWidth', { value: 900, configurable: true });
    Object.defineProperty(capsule, 'clientWidth', { value: 300, configurable: true });
    capsule.scrollLeft = 0;
    capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true }));
    expect(capsule.scrollLeft).toBe(120);
  });

  it('the wheel asks for an INSTANT scroll, and consecutive ticks accumulate', () => {
    // `.tab-capsule` declares `scroll-behavior: smooth`, and an assignment to scrollLeft takes its behaviour
    // from that declaration: it starts an animation and leaves the offset reading where the animation BEGAN.
    // Two things broke on that, both invisible to the test above. The read-back could not tell whether the row
    // had moved, so the gesture never claimed the wheel; and the next tick computed its target from the stale
    // offset, so fast wheeling re-aimed at the same place instead of walking along the row. Naming the
    // behaviour is the fix — 'auto' would not be, since 'auto' means "ask the CSS".
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const asked = [];
    // A real instant scroll has landed by the time the call returns; a smooth one has not, which is the whole
    // bug. Modelling the instant one is what lets the second tick be asserted at all.
    capsule.scrollTo = (opts) => {
      asked.push(opts);
      capsule.scrollLeft = opts.left;
    };

    const first = new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true });
    capsule.dispatchEvent(first);
    expect(asked).toEqual([{ left: 120, behavior: 'instant' }]);
    // Having moved, the gesture belongs to the row: the page must not scroll underneath it as well.
    expect(first.defaultPrevented).toBe(true);

    capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true }));
    expect(asked[1].left).toBe(240);
  });

  it('repainting the bar does not pile up another document-wide drag listener', () => {
    // The drag continues over the whole document once it starts, so half of it cannot live on the capsule.
    // The bar builds a NEW capsule on every repaint — several times a turn — and a `document` listener added
    // per capsule outlives the element it was added for: an unbounded pile, each handler running on every
    // mouse move and each holding a discarded row alive. Those two halves are registered once, for the page.
    const before = bar().querySelector('.tab-capsule');
    const added = [];
    const real = win.document.addEventListener.bind(win.document);
    win.document.addEventListener = (type, fn, opts) => {
      added.push(type);
      return real(type, fn, opts);
    };
    try {
      for (let i = 0; i < 5; i++) {
        win.cc.tabs({ chats: [{ id: '1', title: `Chat ${i}`, selected: true }], tree: TREE, tasks: TASKS });
      }
    } finally {
      win.document.addEventListener = real;
    }

    // The repaints were real ones — a rebuild replaces the capsule — so the count below is about the
    // listeners and not about a render that never happened.
    expect(bar().querySelector('.tab-capsule')).not.toBe(before);
    expect(added.filter((t) => t === 'mousemove' || t === 'mouseup')).toEqual([]);

    // ...and the row that survived all that is still draggable: the fix is one registration, not none.
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 240));
    expect(capsule.scrollLeft).toBe(60);
    win.document.dispatchEvent(at('mouseup', 240));
  });

  // The row is rebuilt from nothing on every repaint (the test above proves the capsule is a NEW element), and
  // a new element starts at offset zero. So everything the reader did to the row — the drag, the wheel — was
  // undone several times a turn, and then the selected tab was re-centred on top of that, which aimed the row
  // at the tab they had deliberately scrolled away from.
  describe('the row stays where the reader put it', () => {
    const MANY = [
      { id: '1', title: 'Chat 1', selected: true },
      { id: '2', title: 'Chat 2' },
      { id: '3', title: 'Chat 3' },
    ];
    const push = (chats, tree) => win.cc.tabs({ chats, tree: tree || TREE, tasks: TASKS });

    it('carries the offset across a repaint', () => {
      push(MANY);
      const before = bar().querySelector('.tab-capsule');
      before.scrollLeft = 180;

      // A repaint with the SAME selection: an agent event, a renamed chat — several times a turn.
      push(MANY.map((c) => ({ ...c, title: c.title + '!' })));
      const after = bar().querySelector('.tab-capsule');
      expect(after).not.toBe(before); // a real rebuild, or the assertion below proves nothing
      expect(after.scrollLeft).toBe(180);
    });

    it('asks for the restore INSTANTLY, because the capsule scrolls smoothly', () => {
      // `.tab-capsule` declares `scroll-behavior: smooth`, which a bare assignment obeys: the row would GLIDE
      // back into place on every repaint instead of simply being where it was. jsdom has no scroll methods on
      // Element, so the call has to be observed on a stub — the same technique the wheel test uses.
      push(MANY);
      bar().querySelector('.tab-capsule').scrollLeft = 200;
      const asked = [];
      win.Element.prototype.scrollTo = function (opts) {
        asked.push(opts);
        this.scrollLeft = opts.left;
      };
      try {
        push(MANY.map((c) => ({ ...c, title: c.title + '!' })));
      } finally {
        delete win.Element.prototype.scrollTo;
      }
      expect(asked).toEqual([{ left: 200, behavior: 'instant' }]);
    });

    it('re-centres the selected chat when the selection CHANGES, and not on every repaint', () => {
      // jsdom has no `scrollIntoView` at all, and the code tests for it before calling — so the stub is what
      // makes the centring observable, and its absence is what the module already tolerates. The frame
      // callback is run inline for the same reason: the centring is deliberately deferred to one, and a
      // real frame is not something a test can wait for.
      const centred = [];
      const rafBefore = win.requestAnimationFrame;
      win.requestAnimationFrame = (fn) => fn();
      win.Element.prototype.scrollIntoView = function (opts) {
        // The CHAT row only. The subtab row centres its own open pill on the same terms, in its own capsule
        // and with its own test — collecting both here would make this assertion fail whenever that row
        // simply did its job, which is a test measuring the wrong thing rather than a defect.
        if (this.closest('.subtab-capsule')) return;
        centred.push([this.querySelector('.pill-label').textContent, opts.inline]);
      };
      try {
        setup(); // a fresh page: the first draw has to start the row aimed at the open chat
        push(MANY);
        expect(centred).toEqual([['Chat 1', 'center']]);

        push(MANY.map((c) => ({ ...c, title: c.title + '!' }))); // same chat open — leave the row alone
        expect(centred.length).toBe(1);

        push([
          { id: '1', title: 'Chat 1' },
          { id: '2', title: 'Chat 2', selected: true },
          { id: '3', title: 'Chat 3' },
        ]);
        expect(centred.length).toBe(2);
        expect(centred[1]).toEqual(['Chat 2', 'center']);
      } finally {
        delete win.Element.prototype.scrollIntoView;
        win.requestAnimationFrame = rafBefore;
      }
    });
  });

  // The two subtab rows. The guard is the thing to watch here — `render` returns early on an unchanged
  // signature, so a row the signature does not describe is a row that is drawn once and then frozen for the
  // rest of the session.
  describe('the subtabs row', () => {
    it('lists the chat, the agents the CHAT started, and its background tasks', () => {
      // Top level plus the tasks — the subagents are one level down, under `a`, and have their own tests
      // above. Same walk the guard reads (`T.chatWork`): two traversals that have to agree is how a stale
      // row ships.
      expect(subLabels()).toEqual(['Chat', 'Inventario de dependencias', 'BT: npm run dev']);
      expect(bar().querySelectorAll('.tab-row').length).toBe(2);
    });

    it('keeps a background task at the CHAT’s level whoever started it', () => {
      // `t1` is owned by agent `a` and still sits in this row rather than in `a`'s branch. A task is not a
      // conversation: it has nothing under it, it is the plugin's own record of a running process, and it
      // routinely outlives the agent that spawned it. Filing it under that agent would make a live task's
      // output unreachable whenever the branch is closed — and the output is the whole point of the row.
      expect(subLabels()).toContain('BT: npm run dev');
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      expect(branchLabels()).not.toContain('BT: npm run dev');
      expect(subLabels()).toContain('BT: npm run dev');
    });

    it('draws a task whose owner is not in the tree rather than hiding it', () => {
      // The retention window drops nodes by age, so a live task whose owning agent has aged out is a real
      // case. Tasks all live at the chat's level, so an unresolved owner is not even a question here — which
      // is what the tree panel needed a special branch for.
      win.cc.tabs({
        chats: CHATS,
        tree: [],
        tasks: [{ id: 't9', owner: 'long-gone', label: 'pytest -x', type: 'bash', running: true }],
      });
      expect(subLabels()).toEqual(['Chat', 'BT: pytest -x']);
    });

    it('paints the state word the HOST sent, and never derives one', () => {
      // One vocabulary for every view (`JcefStatus`: running|completed|failed|stopped). The bar used to say
      // `done` where the dashboard said `completed`, so one task had two colours and two CSS rules.
      const dotOf = (name) => subPill(name).querySelector('.pill-dot');
      expect(dotOf('Inventario de dependencias').className).toBe('pill-dot running');
      // ...and the branch row paints from the same vocabulary, which is the half a row drawn by a second
      // code path would get wrong.
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      expect(dotOf('Dependencias sin usar').className).toBe('pill-dot completed');
      // An unknown word is painted as it arrives rather than mapped onto one of ours: the host owns the
      // vocabulary, and a page that "corrects" it is how the two views drifted apart in the first place.
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'stopped' } : n)),
        tasks: TASKS,
      });
      expect(dotOf('Inventario de dependencias').className).toBe('pill-dot stopped');
    });

    it('says the state in words too, not only in the colour of the dot', () => {
      // WCAG 2.2 SC 1.4.1: colour is never the only carrier. The word rides in the accessible name and in the
      // tooltip, which is also what a voice-control user has to say to reach the pill.
      const pill = subPill('Inventario de dependencias').querySelector('.pill');
      expect(pill.getAttribute('aria-label')).toContain('running');
      expect(pill.getAttribute('title')).toContain('running');
      // And the visible text is contained in the accessible name (SC 2.5.3 Label in Name).
      expect(pill.getAttribute('aria-label')).toContain('Inventario de dependencias');
    });

    it('a chat that started nothing has no subtabs row at all', () => {
      win.cc.tabs({ chats: CHATS, tree: [], tasks: [] });
      expect(subCapsule()).toBe(null);
      expect(bar().querySelectorAll('.tab-row').length).toBe(1);
    });

    // The DOM-identity tests, the same shape as the ones over the chats' row above. This row is the one the
    // guard was most likely to freeze, because the whole reason it exists is to show work that MOVES.
    it('an identical push does not rebuild it', () => {
      const before = subCapsule();
      win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
      win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
      expect(subCapsule()).toBe(before);
    });

    /** A chat with one agent that started [n] subagents, all of them in its branch. */
    const manyUnder = (n) => {
      const tree = [{ id: 'root', parent: null, label: 'El que reparte', status: 'running' }];
      for (let i = 0; i < n; i++) {
        tree.push({ id: 'n' + i, parent: 'root', label: 'Agent ' + i, status: 'running' });
      }
      return tree;
    };

    it('an identical push does not rebuild EITHER row at dozens of pills', () => {
      // The session this whole feature exists for runs dozens of agents, and the host pushes the whole bar on
      // every event one of them raises. That is the case where a rebuild per push actually costs something —
      // eighty-odd pills laid out several times a second — so it is the case the guard is asserted on, and
      // asserted with a real change afterwards so the skip is not just permanently on.
      const many = manyUnder(84);
      win.cc.tabs({ chats: CHATS, tree: many, tasks: [] });
      click(subPill('El que reparte').querySelector('.pill'));
      const before = subCapsule();
      const beforeBranch = branchCapsule();
      expect(branchPills().length).toBe(84);

      win.cc.tabs({ chats: CHATS, tree: many, tasks: [] });
      win.cc.tabs({ chats: CHATS, tree: many, tasks: [] });
      expect(subCapsule()).toBe(before);
      expect(branchCapsule()).toBe(beforeBranch);

      // One of the eighty-four finishing still repaints: the skip describes the rows, it does not disable them.
      win.cc.tabs({
        chats: CHATS,
        tree: many.map((n) => (n.id === 'n40' ? { ...n, status: 'completed' } : n)),
        tasks: [],
      });
      expect(branchCapsule()).not.toBe(beforeBranch);
      expect(subPill('Agent 40').querySelector('.pill-dot.completed')).not.toBe(null);
    });

    it('work churning inside a CLOSED branch does not repaint the bar at all', () => {
      // The other half of "describe everything drawn and NOTHING else", and the one that pays for itself at
      // these numbers: with the branch shut, none of those eighty-four pills is on screen, so their statuses
      // moving — which is most of the traffic on a running session — must move nothing. Before the rows were
      // split this was a full rebuild per push, because the signature described the whole tree.
      const many = manyUnder(84);
      win.cc.tabs({ chats: CHATS, tree: many, tasks: [] });
      const before = subCapsule();
      expect(branchCapsule()).toBe(null);

      win.cc.tabs({
        chats: CHATS,
        tree: many.map((n) => (n.id === 'n40' ? { ...n, status: 'completed' } : n)),
        tasks: [],
      });
      expect(subCapsule()).toBe(before);
    });

    it('the FIRST subagent of a closed agent does repaint it — the pill starts disclosing', () => {
      // The exception to the test above, and why `hasKids` is in the signature. An agent that had no children
      // and now has one stops being a plain tab and becomes a disclosure control: it gains
      // `aria-expanded="false"`, which is how a screen-reader user learns it opens something. That is a
      // change in what is drawn, so it has to be a repaint — and it is a boolean, so it happens once.
      win.cc.tabs({ chats: CHATS, tree: [TREE[0]], tasks: [] });
      const before = subCapsule();
      expect(subPill('Inventario de dependencias').querySelector('.pill').hasAttribute('aria-expanded')).toBe(
        false
      );

      win.cc.tabs({ chats: CHATS, tree: [TREE[0], TREE[1]], tasks: [] });
      expect(subCapsule()).not.toBe(before);
      expect(subPill('Inventario de dependencias').querySelector('.pill').getAttribute('aria-expanded')).toBe(
        'false'
      );
    });

    it('an agent that FINISHES repaints it — the failure the guard would hide', () => {
      // Without the row in the signature this push looks identical to the last one: the agent would keep its
      // running colour for the rest of the session, and nothing on screen would say otherwise.
      const before = subCapsule();
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'completed' } : n)),
        tasks: TASKS,
      });
      expect(subCapsule()).not.toBe(before);
      expect(subPill('Inventario de dependencias').querySelector('.pill-dot.completed')).not.toBe(null);
    });

    it('an agent the CHAT starts repaints it, and gets its own pill', () => {
      const before = subCapsule();
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.concat([{ id: 'd', parent: null, label: 'Nuevo', type: 'Explore', status: 'running' }]),
        tasks: TASKS,
      });
      expect(subCapsule()).not.toBe(before);
      expect(subLabels()).toContain('Nuevo');
    });

    it('a subagent appearing inside the OPEN branch repaints it and gets its own pill', () => {
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      const before = branchCapsule();
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.concat([{ id: 'd', parent: 'b', label: 'Nieto', type: 'Explore', status: 'running' }]),
        tasks: TASKS,
      });
      expect(branchCapsule()).not.toBe(before);
      expect(branchLabels()).toContain('Nieto');
      // Three rows still: a grandchild joins the branch it belongs to, it does not open one of its own.
      expect(bar().querySelectorAll('.tab-row').length).toBe(3);
    });

    it('opening a subtab repaints it — that pill is the one wearing the accent', () => {
      // The other thing the row draws and the payload does not carry: which pill is open. Left out of the
      // signature, `revealAgentTab` would move `T.selected` and then skip the render, so the accent, the
      // `aria-current` and the close would all stay on the pill you just left.
      const before = subCapsule();
      win.cc.revealAgentTab('c');
      expect(subCapsule()).not.toBe(before);
      expect(subtab()).toBe('Dependencias sin usar');
    });

    it('carries its OWN offset across a repaint', () => {
      // Two capsules in the bar now, each with its own reader position. The subtab row is read by class and
      // not by index for exactly this reason: `.tab-capsule` matches the chats' one first.
      const before = subCapsule();
      before.scrollLeft = 140;
      bar().querySelector('.tab-capsule').scrollLeft = 60;
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'completed' } : n)),
        tasks: TASKS,
      });
      // A real rebuild, or the two assertions below prove nothing at all.
      expect(subCapsule()).not.toBe(before);
      expect(subCapsule().scrollLeft).toBe(140);
      expect(bar().querySelector('.tab-capsule').scrollLeft).toBe(60);
    });

    it('re-centres the open subtab when the SELECTION changes, and not on every repaint', () => {
      // Same rule as the chats' row, and the same reason: re-aiming on every push undoes the drag that was
      // just restored, which makes the row unreadable while a turn is running.
      const centred = [];
      const rafBefore = win.requestAnimationFrame;
      win.requestAnimationFrame = (fn) => fn();
      win.Element.prototype.scrollIntoView = function () {
        if (this.closest('.subtab-capsule')) centred.push(this.querySelector('.pill-label').textContent);
      };
      try {
        win.cc.revealAgentTab('b');
        expect(centred).toEqual(['Dependencias de desarrollo']);

        // A push that only moves a status: same subtab open, leave the row where the reader put it.
        win.cc.tabs({
          chats: CHATS,
          tree: TREE.map((n) => (n.id === 'c' ? { ...n, status: 'failed' } : n)),
          tasks: TASKS,
        });
        expect(centred.length).toBe(1);

        win.cc.revealAgentTab('c');
        expect(centred).toEqual(['Dependencias de desarrollo', 'Dependencias sin usar']);
      } finally {
        delete win.Element.prototype.scrollIntoView;
        win.requestAnimationFrame = rafBefore;
      }
    });

    it('drags, wheels and swallows the ending click exactly like the row above it', () => {
      // It holds dozens of pills in a tool window a few hundred pixels wide, so it needs every gesture the
      // chats' row needed — the wheel translated, the row grabbable, the click that ends a drag swallowed —
      // or its far end is unreachable and reaching it selects something. They are the SAME three functions
      // applied to both capsules, not a second implementation: a copy would drift, and the row that drifted
      // would be this one, because it is the one nobody demonstrates on a two-chat project.
      const capsule = subCapsule();
      capsule.scrollLeft = 0;
      capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 90, bubbles: true, cancelable: true }));
      expect(capsule.scrollLeft).toBe(90);

      const at = (type, x) =>
        new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
      capsule.dispatchEvent(at('mousedown', 300));
      win.document.dispatchEvent(at('mousemove', 260));
      expect(capsule.scrollLeft).toBe(130);
      win.document.dispatchEvent(at('mouseup', 260));

      // Releasing over a subtab must not also open it.
      sent.length = 0;
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      expect(sent).toEqual([]);
    });

    it('brings a focused pill into view, so tabbing cannot leave focus off-screen', () => {
      // WCAG 2.2 SC 2.4.7 and 2.4.11: with dozens of pills the row is far wider than the tool window, and
      // keyboard focus walking along it would otherwise land on pills nobody can see. One listener on the
      // capsule, so it survives the rebuild.
      // Both rows, because both are wired by the same call and a copy of it in one of them would rot in the
      // one nobody demonstrates on a small project.
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      const seen = [];
      win.Element.prototype.scrollIntoView = function () {
        seen.push(this.className);
      };
      try {
        subPill('BT: npm run dev')
          .querySelector('.pill')
          .dispatchEvent(new win.FocusEvent('focusin', { bubbles: true }));
        subPill('Dependencias sin usar')
          .querySelector('.pill')
          .dispatchEvent(new win.FocusEvent('focusin', { bubbles: true }));
      } finally {
        delete win.Element.prototype.scrollIntoView;
      }
      expect(seen.length).toBe(2);
      expect(seen.every((c) => c.includes('pill'))).toBe(true);
    });

    it('the pills are sized to a declared scale, capped and ellipsised', () => {
      // jsdom lays nothing out, so only the stylesheet can be asserted — and concrete numbers are the point,
      // because they are the only thing standing between a considered scale and the next "just a bit bigger".
      // THREE registers live in one file and have to stay apart: the shared base `.pill`, which the tab bar no
      // longer reads for SIZE at all (it is the composer's control row, and the fallback for a `.pill`
      // anywhere else); the chats' row; and the subtab row, the same rule two pixels under it.
      const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
      // Anchored at the start of a line, unlike the looser lookup the older test uses: `.pill {` is a
      // SUBSTRING of several composer selectors, and matching one of those would assert the composer's
      // numbers while claiming to assert the tab bar's. The trailing ` {` is also what keeps `… .pill {` from
      // matching `… .pill.selected {`.
      const block = (selector) => {
        const at = css.indexOf('\n' + selector + ' {');
        return at < 0 ? '' : css.slice(at, css.indexOf('}', at));
      };
      const chats = '.tab-capsule:not(.subtab-capsule)';
      const subtabs = '.subtab-capsule';
      // The shared base. Neither row reads its box any more — the oval is the wrapper and
      // `.tab-capsule .pill` strips the rest off the button — but both still inherit the `gap` between the
      // dot and the label from it, and it is the register the composer's own control row matches. Pinned so
      // that a change made "for the tab bar" here is seen for what it is: a change to the composer.
      expect(block('.pill')).toMatch(/height:\s*24px/);
      expect(block('.pill')).toMatch(/font-size:\s*11\.5px/);
      // ONE width for every chat tab, which is what makes the row a strip of identical ovals instead of an
      // accordion — and it is declared on the WRAPPER, because the wrapper is both the oval and the thing the
      // row lays out. Two declarations hold it, neither sufficient alone. `flex: 0 0 auto` on the wrapper is
      // the single place that says a tab does not squash: a shrinking flex item ignores a declared width the
      // moment the row is crowded, which is the accordion back by another route.
      expect(block(`${chats} .pill-wrap`)).toMatch(/width:\s*123px/);
      expect(block('.pill-wrap')).toMatch(/flex:\s*0 0 auto/);
      // And `min-width: 0`, which does not look load-bearing and is: a flex item's `min-width` defaults to
      // `auto`, which resolves to its CONTENT minimum and overrides a smaller `width` — so a pill holding one
      // long unbreakable word would grow past the declared box, for that one tab, only sometimes.
      expect(block('.tab-capsule .pill')).toMatch(/min-width:\s*0/);
      // The chats' row, and the open chat five pixels above its own neighbours. The type moves with the box:
      // a taller tab at the same type is a stretched pill, not a bigger one — and the type is the BUTTON's
      // while the box is the oval's, which is the split the whole row now rests on.
      expect(block(`${chats} .pill-wrap`)).toMatch(/height:\s*22px/);
      expect(block(`${chats} .pill-wrap`)).toMatch(/padding:\s*0 2px 0 8px/);
      expect(block(`${chats} .pill`)).toMatch(/font-size:\s*10\.5px/);
      expect(block(`${chats} .pill-wrap.selected`)).toMatch(/height:\s*27px/);
      expect(block(`${chats} .pill.selected`)).toMatch(/font-size:\s*11\.5px/);
      // ...and deliberately neither wider nor more padded. A width that moves with the selection reflows the
      // row on every click — the total width changes, and with it the offset the capsule restores and the
      // point `scrollIntoView` centres on — and at a fixed width extra padding only takes space from the
      // label of the very tab you are reading.
      expect(block(`${chats} .pill-wrap.selected`)).not.toMatch(/[\s;]width:/);
      expect(block(`${chats} .pill-wrap.selected`)).not.toMatch(/[\s;]padding:/);
      // The SUBTAB row is the same rule two pixels under it in every box measure — 121×20 against
      // 123×22, and 25px open against 27px. Its padding is symmetric where the chats' row's is not,
      // and that is not an inconsistency: there every tab carries a close whose own box supplies the
      // right-hand inset, while here only the OPEN pill has one and the rest end in bare text that
      // would otherwise touch the border. This row is the one that is NEW to the fixed-width rule, and
      // what that costs is recorded at the rule itself: a four-letter `Chat` pill occupies the full
      // 121px like everything else.
      expect(block(`${subtabs} .pill-wrap`)).toMatch(/width:\s*121px/);
      expect(block(`${subtabs} .pill-wrap`)).toMatch(/height:\s*20px/);
      expect(block(`${subtabs} .pill-wrap`)).toMatch(/padding:\s*0 7px/);
      expect(block(`${subtabs} .pill`)).toMatch(/font-size:\s*10\.5px/);
      expect(block(`${subtabs} .pill-wrap.selected`)).toMatch(/height:\s*25px/);
      expect(block(`${subtabs} .pill.selected`)).toMatch(/font-size:\s*11\.5px/);
      // Same rule as the chats' row above, and for the same reason: the open tab grows, and grows only.
      expect(block(`${subtabs} .pill-wrap.selected`)).not.toMatch(/[\s;]width:/);
      expect(block(`${subtabs} .pill-wrap.selected`)).not.toMatch(/[\s;]padding:/);
      // "Oval" is the shape and it comes from the token, on the element that paints it: `--radius-pill` is
      // 999px, which every engine clamps to half the shorter side, so the ends are semicircles at both
      // heights. Swapped for `--radius-sm` (9px) the same box is a rounded rectangle — a change nobody would
      // read as a regression in the diff.
      expect(block('.pill-wrap')).toMatch(/border-radius:\s*var\(--radius-pill\)/);
      // Size REINFORCES the selected state and is never its only carrier (WCAG 2.2 SC 1.4.1). The accent
      // border is the oval's and the accent text and weight are the button's — a scale change must not be
      // able to take either with it.
      expect(block('.pill-wrap.selected')).toMatch(/border-color:\s*var\(--accent\)/);
      expect(block('.pill.selected')).toMatch(/font-weight:\s*600/);
      // `[\s;]` and not a bare `color:`, which the `border-color` on the same rule would satisfy without the
      // text ever turning accent.
      expect(block('.pill.selected')).toMatch(/[\s;]color:\s*var\(--accent\)/);
      // The invariants that kept breaking, restated so a size change cannot quietly take them either. All
      // three are needed for the label to be what gives way inside a fixed box: without `min-width: 0` a flex
      // item refuses to go below its content and the ellipsis never appears, however small the pill.
      expect(block('.pill-label')).toMatch(/text-overflow:\s*ellipsis/);
      expect(block('.pill-label')).toMatch(/overflow:\s*hidden/);
      expect(block('.pill-label')).toMatch(/min-width:\s*0/);
      // No CAP survives anywhere, and that absence is the decision. A `22ch` cap lived on the wrapper while a
      // tab was as wide as its title; each row declares a width now, so the cap governed nothing — and a cap
      // and a fixed width are two answers to one question, with the leftover being a rule nothing can reach,
      // which is this repository's signature defect. Both rows are checked, not only the base.
      expect(block('.pill-wrap')).not.toMatch(/max-width:/);
      expect(block(`${chats} .pill-wrap`)).not.toMatch(/max-width:/);
      expect(block(`${subtabs} .pill-wrap`)).not.toMatch(/max-width:/);
      // A control keeps a declared box rather than whatever the glyph happens to measure: 20px on the subtab
      // row, the one declared shortfall against WCAG 2.2 SC 2.5.8, held by `docs/adr/0004` and scoped to this
      // class — not by the row's height, which no longer forces it — and the full 24×24 on the chats' row,
      // where it exceeds the 22px oval by a pixel top and bottom on purpose: the target is conformant and the
      // tab keeps the height it was given, because this control paints nothing.
      expect(block('.pill-x')).toMatch(/width:\s*20px/);
      expect(block('.pill-x')).toMatch(/height:\s*20px/);
      expect(block(`${chats} .pill-x`)).toMatch(/width:\s*24px/);
      expect(block(`${chats} .pill-x`)).toMatch(/height:\s*24px/);
      // ...and it paints nothing on hover either. A 24px tinted disc inside a 22px oval spills past the tab's
      // own border, which reads as a rendering fault; the colour change is the affordance now that the glyph
      // sits on a surface of its own.
      expect(block('.pill-x:hover')).not.toMatch(/background:/);
    });
  });

  /**
   * The two amputations, kept amputated.
   *
   * The `⋮` opened a tree panel and the `⇱` promoted a subtab to a tab of its own; both went when the row
   * started showing every subtab outright. This is the assertion that keeps them gone, and it is here because
   * of what this repository's signature defect actually is: not code that fails, but code that is
   * implemented, tested and reachable from nothing. A `.pill-more` handler nobody can press, a `.tab-menu`
   * rule nothing emits and a `pinSubtab` message nothing sends all pass every other test in this file.
   *
   * Asserted over the whole document, in the state where they USED to be drawn — a chat with work, a subtab
   * open — so a partial revival is caught as surely as a full one.
   */
  it('there is no ⋮ and no pin anywhere in the document', () => {
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    win.cc.revealAgentTab('b');
    expect(win.document.querySelectorAll('.pill-more, .pill-pin').length).toBe(0);
    expect(win.document.querySelectorAll('.tab-menu, .tab-tree').length).toBe(0);
    // And nothing in the page can ask the host to pin: the message and its parser both went with the glyph.
    sent = [];
    Array.from(win.document.querySelectorAll('#tabsbar button')).forEach(click);
    expect(sent.map((m) => m.type)).not.toContain('pinSubtab');
    // The stylesheet went with them. A rule with no emitter is invisible to `css-contract.test.js`, which
    // checks the other direction, so it is checked here. Comments are stripped first — one of them is a note
    // recording which classes were deleted, and asserting against the explanation instead of against the
    // rules is how this kind of check quietly stops meaning anything.
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css).not.toMatch(/\.pill-pin|\.pill-more|\.tab-menu|\.tab-tree/);
  });
});
