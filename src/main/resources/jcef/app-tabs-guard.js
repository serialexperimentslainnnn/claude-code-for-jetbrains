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
   * What the bar actually DRAWS, as a string — everything else in the payload is invisible here.
   *
   * The host pushes the whole bar on every agent event, several times a turn, and almost all of those
   * pushes change nothing you can see: an agent's transcript grew, a token landed. Rebuilding the DOM
   * anyway is what made the row flicker under the pointer, and it cost a full rebuild per event.
   */
  function signature() {
    var parts = T.state.chats.map(function (chat) {
      var work = chat.selected ? T.hasWork() : (chat.tree || []).length > 0 || (chat.tasks || []).length > 0;
      return [chat.id, chat.title, !!chat.selected, !!chat.attention, chat.pinned || '', work].join('');
    });
    if (T.selected) {
      var node = T.selected.kind === 'agent' ? T.nodeById(T.selected.id) : T.taskById(T.selected.id);
      parts.push(
        node
          ? [T.selected.kind, T.selected.id, node.label || '', node.status || '', !!node.running].join('')
          : T.selected.kind + '' + T.selected.id + 'gone'
      );
    }
    return parts.join('');
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
      parts.push([n.id, n.parent || '', n.label || '', n.type || '', n.status || '', !!n.running].join(''));
    });
    tasks.forEach(function (t) {
      if (!t) return;
      parts.push([t.id, t.owner || '', t.label || '', t.type || '', t.status || '', !!t.running].join(''));
    });
    return parts.join('');
  }

  /** Everything currently drawn: the bar AND its open panel. */
  function drawnSignature() {
    return signature() + '' + panelSignature();
  }

  /** The last drawn signature, so an identical push is a no-op instead of a rebuild. */
  T.drawn = null;

  T.drawnSignature = drawnSignature;
})();
