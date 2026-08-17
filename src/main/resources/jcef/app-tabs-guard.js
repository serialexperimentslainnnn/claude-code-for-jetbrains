/* app-tabs-guard.js — the flicker guard, whole.
 *
 * One subject, and it is kept in ONE file on purpose: what the bar has drawn, as a string, plus the slot
 * holding the last one. `render` compares the combined signature unconditionally and stamps it LAST; the tree
 * panel stamps it too, because a hover opens and closes that panel without going through `render`. Those
 * pieces only make sense together — split across files, the next person changes one of the three stamps and
 * the flicker comes back under the pointer, which is exactly the bug this guard exists to stop.
 *
 * Two mutation-checked regression tests pin the behaviour (see tabs.test.js).
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var T = (CC.tabbar = CC.tabbar || {});

  /**
   * The field boundary inside one signature entry — built from its CODE POINT, never typed as a character.
   *
   * A separator that cannot occur in the data is the right idea: `label:'ab', status:''` and
   * `label:'a', status:'b'` are the same string once concatenated, and a collision here does not look like a
   * bug — it looks like a row that stopped updating.
   *
   * How it is WRITTEN is the part that cost an afternoon. This file once carried a literal U+0000 between two
   * quotes. That byte makes the source binary — `git diff` reports `Bin`, `grep` goes quiet on the file — and,
   * fatally, the HTML parser rewrites U+0000 to U+FFFD while reading the script, so the text the browser
   * hashes is no longer the text the host hashed. The page is served under a hash-pinned CSP, so the script
   * was REFUSED: this whole file never ran, `T.drawnSignature` was never defined, and `render` threw on every
   * push. The tab bar vanished and nothing anywhere said why — an exception inside `executeJavaScript` fires
   * no `error` event and reaches no log.
   *
   * So: no non-printable character is ever typed into this page's sources. `String.fromCharCode` is
   * unambiguous, survives every editor and every copy-paste, and cannot be mistyped invisibly.
   */
  var SEP = String.fromCharCode(31); // U+001F INFORMATION SEPARATOR ONE

  /**
   * What the bar actually DRAWS, as a string — everything else in the payload is invisible here.
   *
   * The host pushes the whole bar on every agent event, several times a turn, and almost all of those
   * pushes change nothing you can see: an agent's transcript grew, a token landed. Rebuilding the DOM
   * anyway is what made the row flicker under the pointer, and it cost a full rebuild per event.
   */
  function signature() {
    var parts = T.state.chats.map(function (chat) {
      var work = chat.selected ? T.hasWork() : (chat.tree || []).length > 0 || (chat.tasks || []).length > 0;
      return [chat.id, chat.title, !!chat.selected, !!chat.attention, chat.pinned || '', work].join(SEP);
    });
    if (T.selected) {
      var node = T.selected.kind === 'agent' ? T.nodeById(T.selected.id) : T.taskById(T.selected.id);
      parts.push(
        node
          ? [T.selected.kind, T.selected.id, node.label || '', node.status || '', !!node.running].join(SEP)
          : [T.selected.kind, T.selected.id, 'gone'].join(SEP)
      );
    }
    // The SUBTAB ROW, which is drawn whenever the chat on screen has started anything.
    //
    // Without it the row is frozen: the skip below is unconditional, so a push that only moves an agent's
    // status would look identical to the last one and never repaint — an agent that finished would keep its
    // running colour for the rest of the session, and one that started would never get a pill at all. What is
    // drawn has to be described, and this row is drawn.
    //
    // Read through `T.workOrder`, the same walk the row itself renders from: a second traversal here would
    // eventually disagree with that one, and a signature that describes a different row is worse than none.
    T.workOrder().forEach(function (w) {
      var n = w.node;
      parts.push([w.kind, w.id, w.depth, n.label || '', n.type || '', n.status || '', !!n.running].join(SEP));
    });
    return parts.join(SEP);
  }

  /**
   * What the OPEN panel draws, as a string — the other half of [signature], and empty while nothing is open.
   *
   * The skip in [render] used to be waived outright whenever a panel was open, reasoning that the panel shows
   * the tree and the tree does change on the pushes being filtered. The reasoning is right and the guard was
   * far too blunt: with the pointer resting on a tab, EVERY push rebuilt the whole bar and then re-anchored
   * and reopened the panel underneath the cursor — several times a second on a session with agents running,
   * which is the flicker that was reported. The answer is not to skip less, it is to DESCRIBE more: fold in
   * what the panel renders, and an identical push becomes a no-op whether the menu is open or not, while a
   * genuine change still repaints it.
   *
   * Covers the whole tree of the panel's chat rather than only the `rootId` subtree on screen: it is one pass
   * over a list the payload already carries, and narrowing it would mean reimplementing `openTree`'s
   * parent/owner traversal a second time — two walks that have to agree is how a stale panel gets shipped.
   * Which row is `selected` needs nothing here: which subtab is open is already in [signature].
   */
  function panelSignature() {
    if (!T.openPanel) return '';
    var foreign = null;
    if (T.openPanel.chatId != null) {
      for (var ci = 0; ci < T.state.chats.length; ci++) {
        var chat = T.state.chats[ci];
        if (chat && chat.id === T.openPanel.chatId && !chat.selected) foreign = chat;
      }
    }
    var nodes = foreign ? foreign.tree || [] : T.state.tree;
    var tasks = foreign ? foreign.tasks || [] : T.state.tasks;
    // The root row itself: which agent it is pinned to, whose chat, and that chat's title — the panel prints
    // the title as its top row when it is showing a tab you are only hovering.
    var parts = [T.openPanel.rootId || '', T.openPanel.chatId == null ? '' : T.openPanel.chatId];
    if (foreign) parts.push(foreign.title || '');
    nodes.forEach(function (n) {
      if (!n) return;
      parts.push([n.id, n.parent || '', n.label || '', n.type || '', n.status || '', !!n.running].join(SEP));
    });
    tasks.forEach(function (t) {
      if (!t) return;
      parts.push([t.id, t.owner || '', t.label || '', t.type || '', t.status || '', !!t.running].join(SEP));
    });
    return parts.join(SEP);
  }

  /** Everything currently drawn: the bar AND its open panel. */
  function drawnSignature() {
    return signature() + SEP + panelSignature();
  }

  /** The last drawn signature, so an identical push is a no-op instead of a rebuild. */
  T.drawn = null;

  T.drawnSignature = drawnSignature;
})();
