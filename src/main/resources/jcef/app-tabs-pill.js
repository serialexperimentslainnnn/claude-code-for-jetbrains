/* app-tabs-pill.js — one tab, and the ⋮ that hangs the tree off it.
 *
 * One subject: the widget the bar is made of. A tab is a `.pill-wrap` holding SIBLINGS — the chat's own
 * `<button>`, and then whichever of the pin, the close and the ⋮ it has. Each is a real button.
 *
 * **They used to be nested, and that is what made the × dead on screen.** The three controls were
 * `<span role="button" tabindex="0">` INSIDE the pill's `<button>`: interactive content in a place the
 * `button` content model forbids. Two things follow, and the second one is why a whole day went into it.
 * The ARIA `button` role is *Children Presentational*, so a conforming browser deletes its descendants from
 * the accessibility tree — the ⋮, the pin and the × were never announced, and tabbing to one landed on a
 * control with no name (SC 4.1.2). And Chromium's hit-testing for a nested interactive element is not the
 * DOM's: the press never reached the span's handler, so no message was sent, nothing was logged, and the
 * button simply did nothing. In jsdom the same code sent `closeChat` correctly, which is exactly why the
 * frontend suite stayed green — and why `tabs.test.js` now presses the × rather than only checking that it
 * exists.
 *
 * Siblings also delete the hand-rolled `keydown` handlers this file used to carry: a real `<button>` brings
 * Enter and Space with it. `src/uiTest/.../UiTestBase.kt` reaches for `.pill-x` and moved with this change.
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
          // the subtree, and the glyph controls appended below are part of that subtree — so a chat tab
          // announced as "Chat 1 Show every subtab Close Chat 1", and voice control had no phrase that
          // matched what is on screen. `title` alone does not fix it: content wins over `title`. The visible
          // text is always contained in the value used here (WCAG 2.2 SC 2.5.3 Label in Name).
          'aria-label': opts.title || opts.label,
          // The bar is a set of tab-like controls: say which one is current rather than leaving it to colour.
          'aria-current': opts.selected ? 'true' : 'false',
        },
        on: { click: opts.onClick },
      },
      kids
    );
    // The wrapper IS the tab from the row's point of view: it is what carries the chat id, what the menu
    // anchors against and what the row lays out. Everything the old code hung off the pill hangs off this.
    var wrap = h('div', { class: 'pill-wrap' + (opts.selected ? ' selected' : '') }, btn);
    // Which chat this tab IS, so a repaint can put an open menu back on the same one (see `render`).
    wrap.__chatId = opts.menuChat == null ? null : opts.menuChat;
    btn.__chatId = wrap.__chatId;
    if (opts.onMenu) attachMenu(wrap, opts.menuRoot || null, opts.menuChat || null);
    // The pin: turn what you are reading into a chat tab of its own.
    //
    // A subtab is a VIEW — one browser painting somebody else's transcript — and it is gone the moment you
    // look at something else. Pinning is how you say "I am going to keep coming back to this one", and what
    // you get back is a real tab, alongside the chats, that stays put.
    if (opts.onPin) {
      wrap.appendChild(glyph('pill-pin', '⇱', 'Pin ' + opts.label + ' as a tab', 'Pin as a tab', opts.onPin));
    }
    if (opts.onClose) {
      wrap.appendChild(glyph('pill-x', '×', 'Close ' + opts.label, 'Close', opts.onClose));
    }
    return wrap;
  }

  /**
   * One of the tab's glyph controls, as a REAL button beside the pill rather than a span inside it.
   *
   * `stopPropagation` still, and for a reason that survives the de-nesting: these sit inside the wrapper the
   * menu's hover is bound to, and the row's drag handler watches clicks on its way up. What is gone is the
   * `keydown` pair every one of these used to carry — Enter and Space are what a `<button>` is.
   */
  function glyph(cls, text, name, tip, onPress) {
    return h('button', {
      class: cls,
      text: text,
      attrs: { type: 'button', 'aria-label': name, title: tip },
      on: {
        click: function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          onPress();
        },
      },
    });
  }

  /**
   * The ⋮ that opens the tree, plus the hover that opens it without aiming at anything.
   *
   * Both, deliberately. Hover is the fast path and how the panel is discovered at all; the ⋮ is what makes
   * it a real control — visible before you touch it, reachable from the keyboard, and usable on a device
   * that has no hover at all.
   */
  function attachMenu(host, rootId, chatId) {
    var dots = h('button', {
      class: 'pill-more',
      text: '⋮',
      attrs: {
        type: 'button',
        'aria-haspopup': 'listbox',
        'aria-label': 'Show every subtab',
        title: 'Every agent and background task this chat started',
      },
      on: {
        click: function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          if (T.openPanel && T.openPanel.anchor === host) T.closeTree();
          else T.openTree(host, rootId, chatId);
        },
      },
    });
    host.appendChild(dots);
    // Resting on the tab for a second opens the same menu — same rules as before: it stays for three seconds
    // after you leave, and any movement over it starts that over.
    host.addEventListener('mouseenter', function () {
      T.scheduleOpen(host, rootId, chatId);
    });
    host.addEventListener('mouseleave', T.scheduleClose);
  }

  T.pill = pill;
})();
