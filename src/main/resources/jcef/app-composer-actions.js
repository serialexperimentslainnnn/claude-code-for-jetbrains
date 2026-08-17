/* app-composer-actions.js — the two control rows above the prompt box.
 *
 * Owns: the chat's own action buttons (new chat, stop, commands, Git, close diffs, sign out) and the row the
 * dashboard mounts its view buttons into.
 *
 * WHY THEY ARE HERE AND NOT IN THE TOOL WINDOW'S TITLE BAR. They were six Swing `AnAction`s registered with
 * `setTitleActions`, which is the one part of this UI that was still Swing — a strip the page cannot share an
 * accent, a type scale or a transition with, sitting above a browser that draws everything else. It also put
 * them furthest from the composer, which is where every one of them is used.
 *
 * Two rows, because they are two different kinds of thing and reading them as one list is what made the old
 * arrangement noisy:
 *
 *   · the ACTIONS row is icons, right-aligned — things you do to this chat, occasionally;
 *   · the VIEWS row is pills, left-aligned, in the same shape as the model and mode pills below it — where
 *     you are, which is a state and not an action.
 *
 * This module builds NEITHER of the views row's two occupants. `app-session.js` owns whether the dashboard is
 * open and which view it shows, and it fills that container with its own stack; a second owner for that state
 * is how the panel ends up with two answers to "is the chat on screen". `app-composer-settings.js` owns the ⚙
 * at the head of the row. Both mount themselves through an idempotent step this file also calls, because all
 * three can load in any order. This module only decides where the row lives.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  // Icons are inline SVG, never `url()`: the CSP forbids external resources and there is no asset pipeline.
  // `currentColor` throughout, so a button inherits the theme the same way the rest of the bar does.
  function svg(body) {
    return (
      '<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" ' +
      'stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      body +
      '</svg>'
    );
  }

  var GLYPH = {
    newChat: svg('<path d="M8 3.5v9"/><path d="M3.5 8h9"/>'),
    commands: svg('<circle cx="7" cy="7" r="4"/><path d="m10 10 3 3"/>'),
    git: svg(
      '<circle cx="4.5" cy="3.5" r="1.8"/><circle cx="4.5" cy="12.5" r="1.8"/><circle cx="11.5" cy="8" r="1.8"/><path d="M4.5 5.3v5.4"/><path d="M9.7 8H8.2a3.7 3.7 0 0 1-3.7-3.7"/>'
    ),
    closeDiffs: svg('<path d="M3 4.5h10"/><path d="M6.5 4.5V3h3v1.5"/><path d="M4.5 4.5 5 13h6l.5-8.5"/>'),
    signOut: svg('<path d="M9.5 3.5H4v9h5.5"/><path d="M11 5.5 13.5 8 11 10.5"/><path d="M13.5 8h-6"/>'),
  };

  /** The one button whose enabled state is a function of the session, kept so [renderActions] can find it. */
  var diffsBtn = null;

  function actionButton(glyph, label, onClick) {
    var btn = h('button', {
      class: 'bar-icon',
      title: label,
      attrs: { type: 'button', 'aria-label': label },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          if (btn.disabled) return;
          onClick();
        },
      },
    });
    btn.innerHTML = glyph;
    return btn;
  }

  function send(message) {
    if (CC.send) CC.send(message);
  }

  /**
   * Fills the action group and returns the ROW that holds both groups.
   *
   * Called once, by `app-composer.js`, which puts it inside the prompt card, above the model/mode bar. One
   * row and not two: where you are and what you can do are different kinds of thing, but two stacked strips
   * of controls over a text box is a wall, so they share a line and are separated by which end they sit at.
   */
  CX.buildActionRows = function () {
    var controls = document.getElementById('controls');
    var actions = document.getElementById('actions');
    var views = document.getElementById('views');
    if (!controls || !actions || !views) return null;
    // The ⚙ that heads the views row (app-composer-settings.js). Idempotent and called from both sides, the
    // same arrangement `app-session.js` has for its view stack: three families write into `#views` and none
    // of them may depend on having loaded first.
    if (CX.mountSettingsButton) CX.mountSettingsButton();
    actions.innerHTML = '';
    // "Commands" needs no host round trip: the palette is this page's own, and asking the host to ask the
    // page back was only ever an artifact of the button living outside the browser.
    var commandsBtn = actionButton(GLYPH.commands, 'Browse slash commands', function () {
      if (cc.openPalette) cc.openPalette();
    });
    // NB no Stop here. The composer's own send button turns into one for the length of a turn, right where
    // you are typing — a second stop three centimetres away is the duplication this row exists to end.
    diffsBtn = actionButton(GLYPH.closeDiffs, 'Close every diff Claude opened', function () {
      send({ type: 'closeAllDiffs' });
    });
    [
      actionButton(GLYPH.newChat, 'New chat', function () {
        send({ type: 'newChat' });
      }),
      commandsBtn,
      actionButton(GLYPH.git, 'Git', function () {
        send({ type: 'openGitView' });
      }),
      diffsBtn,
      actionButton(GLYPH.signOut, 'Log out of Claude', function () {
        send({ type: 'logout' });
      }),
    ].forEach(function (button) {
      actions.appendChild(button);
    });
    return controls;
  };

  /**
   * Where the dashboard's view buttons go — a STATIC element of the shell, so it is there from the first
   * frame whether or not a composer has been built. The dashboard and the composer are two families and
   * either can run first; owning this container in one of them made the other's output depend on it.
   */
  CX.viewsRow = function () {
    return document.getElementById('views');
  };

  /**
   * Enablement, from the state the host already pushes — a button that cannot do anything says so.
   *
   * Close-diffs follows `openDiffs`, which the host counts: without it the button would be permanently live
   * and pressing it with nothing open would look broken rather than idempotent. The other four are always
   * available inside a chat.
   */
  CX.renderActions = function (s) {
    if (diffsBtn) diffsBtn.disabled = !(s && s.openDiffs > 0);
  };
})();
