/* app-tabs-pill.js — one tab.
 *
 * Owns: one tab, and the × beside it.
 *
 * One subject: the widget the bar is made of. A tab is a `.pill-wrap` holding SIBLINGS — the chat's own
 * `<button>`, and the close beside it where there is one. Both are real buttons.
 *
 * **They used to be nested, and that is what made the × dead on screen.** The controls were
 * `<span role="button" tabindex="0">` INSIDE the pill's `<button>`: interactive content in a place the
 * `button` content model forbids. Two things follow, and the second one is why a whole day went into it.
 * The ARIA `button` role is *Children Presentational*, so a conforming browser deletes its descendants from
 * the accessibility tree — the × was never announced, and tabbing to it landed on a control with no name
 * (SC 4.1.2). And Chromium's hit-testing for a nested interactive element is not the DOM's: the press never
 * reached the span's handler, so no message was sent, nothing was logged, and the button simply did nothing.
 * In jsdom the same code sent `closeChat` correctly, which is exactly why the frontend suite stayed green —
 * and why `tabs.test.js` presses the × rather than only checking that it exists.
 *
 * Siblings also delete the hand-rolled `keydown` handlers this file used to carry: a real `<button>` brings
 * Enter and Space with it. `src/uiTest/.../UiTestBase.kt` reaches for `.pill-x`.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var T = (CC.tabbar = CC.tabbar || {});
  var h = CC.h;

  function pill(opts) {
    var kids = [];
    if (opts.status) {
      // A dot, not a colour on the text: state has to survive both a colour-blind reader and a screen reader,
      // and the label spells it out in words.
      kids.push(h('span', { class: 'pill-dot ' + opts.status, attrs: { 'aria-hidden': 'true' } }));
    }
    kids.push(h('span', { class: 'pill-label', text: opts.label }));
    var btn = h(
      'button',
      {
        class: 'pill' + (opts.selected ? ' selected' : ''),
        attrs: {
          type: 'button',
          title: opts.title || opts.label,
          // Named EXPLICITLY rather than from its own content. With no `aria-label` the name is computed from
          // the subtree, and the close appended below is part of that subtree — so a chat tab announced as
          // "Chat 1 Close Chat 1", and voice control had no phrase that matched what is on screen. `title`
          // alone does not fix it: content wins over `title`. The visible text is always contained in the
          // value used here (WCAG 2.2 SC 2.5.3 Label in Name).
          'aria-label': opts.title || opts.label,
          // The bar is a set of tab-like controls: say which one is current rather than leaving it to colour.
          'aria-current': opts.selected ? 'true' : 'false',
          // An agent that started subagents DISCLOSES a row of them when it is opened, so it says so — and
          // it says so while shut as well, which is the half that matters: `aria-expanded="false"` is how a
          // screen-reader user learns the control opens something at all, before deciding to press it.
          // Absent, not `false`, on a pill that discloses nothing: `aria-expanded` on a plain tab claims a
          // hidden region that does not exist.
          'aria-expanded': opts.expanded == null ? null : opts.expanded ? 'true' : 'false',
        },
        on: { click: opts.onClick },
      },
      kids
    );
    // The wrapper IS the tab from the row's point of view: it is what the row lays out and what carries the
    // selected state the centring looks for. Everything the old code hung off the pill hangs off this.
    //
    // `branch-open` is NOT `selected` and the two must not be merged: `selected` means "this transcript is on
    // screen" and exactly one pill in the bar has it, while `branch-open` marks the agent whose row of
    // subagents is showing — which is still true when you are reading one of those subagents rather than the
    // agent itself. Collapsing them would put `aria-current="true"` on two pills and make the accent stop
    // meaning "you are here".
    var wrap = h(
      'div',
      {
        class:
          'pill-wrap' + (opts.selected ? ' selected' : '') + (opts.expanded === true ? ' branch-open' : ''),
      },
      btn
    );
    if (opts.onClose) {
      // A REAL button beside the pill rather than a span inside it (see the header).
      //
      // `stopPropagation` still, and for a reason that survives the de-nesting: it sits inside the wrapper the
      // row lays out, and the capsule's drag handler watches clicks on their way up. What is gone is the
      // `keydown` pair it used to carry — Enter and Space are what a `<button>` is.
      var close = h('button', {
        class: 'pill-x',
        text: '×',
        attrs: { type: 'button', 'aria-label': 'Close ' + opts.label, title: 'Close' },
        on: {
          click: function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            opts.onClose();
          },
        },
      });
      wrap.appendChild(close);
    }
    return wrap;
  }

  T.pill = pill;
})();
