/* app-tabs-pill.js — one tab, and the ⋮ that hangs the tree off it.
 *
 * One subject: the widget the bar is made of. A pill is a real `<button>` carrying its state as a dot, its
 * name as text and, when it has any, the pin, the close and the ⋮ — each of those a control in its own right
 * rather than a click zone inside another one.
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
          // The bar is a set of tab-like controls: say which one is current rather than leaving it to colour.
          'aria-current': opts.selected ? 'true' : 'false',
        },
        on: { click: opts.onClick },
      },
      kids
    );
    // Which chat this pill IS, so a repaint can put an open menu back on the same tab (see `render`).
    btn.__chatId = opts.menuChat == null ? null : opts.menuChat;
    if (opts.onMenu) attachMenu(btn, opts.menuRoot || null, opts.menuChat || null);
    // The pin: turn what you are reading into a chat tab of its own.
    //
    // A subtab is a VIEW — one browser painting somebody else's transcript — and it is gone the moment you
    // look at something else. Pinning is how you say "I am going to keep coming back to this one", and what
    // you get back is a real tab, alongside the chats, that stays put.
    if (opts.onPin) {
      btn.appendChild(
        h('span', {
          class: 'pill-pin',
          attrs: {
            role: 'button',
            tabindex: '0',
            'aria-label': 'Pin ' + opts.label + ' as a tab',
            title: 'Pin as a tab',
          },
          text: '⇱',
          on: {
            click: function (ev) {
              ev.preventDefault();
              ev.stopPropagation();
              opts.onPin();
            },
            keydown: function (ev) {
              if (ev.key === 'Enter' || ev.key === ' ') {
                ev.preventDefault();
                ev.stopPropagation();
                opts.onPin();
              }
            },
          },
        })
      );
    }
    if (opts.onClose) {
      btn.appendChild(
        h('span', {
          class: 'pill-x',
          attrs: { role: 'button', tabindex: '0', 'aria-label': 'Close ' + opts.label, title: 'Close' },
          text: '×',
          on: {
            click: function (ev) {
              ev.preventDefault();
              ev.stopPropagation();
              opts.onClose();
            },
            keydown: function (ev) {
              if (ev.key === 'Enter' || ev.key === ' ') {
                ev.preventDefault();
                ev.stopPropagation();
                opts.onClose();
              }
            },
          },
        })
      );
    }
    return btn;
  }

  /**
   * The ⋮ that opens the tree, plus the hover that opens it without aiming at anything.
   *
   * Both, deliberately. Hover is the fast path and how the panel is discovered at all; the ⋮ is what makes
   * it a real control — visible before you touch it, reachable from the keyboard, and usable on a device
   * that has no hover at all.
   */
  function attachMenu(host, rootId, chatId) {
    var dots = h('span', {
      class: 'pill-more',
      attrs: {
        role: 'button',
        tabindex: '0',
        'aria-haspopup': 'listbox',
        'aria-label': 'Show every subtab',
        title: 'Every agent and background task this chat started',
      },
      text: '⋮',
      on: {
        click: function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          if (T.openPanel && T.openPanel.anchor === host) T.closeTree();
          else T.openTree(host, rootId, chatId);
        },
        keydown: function (ev) {
          if (ev.key === 'Enter' || ev.key === ' ') {
            ev.preventDefault();
            ev.stopPropagation();
            T.openTree(host, rootId, chatId);
          }
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
