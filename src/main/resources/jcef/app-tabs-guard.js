/* app-tabs-guard.js — the flicker guard, whole.
 *
 * Owns: the flicker guard, whole.
 *
 * One subject, and it is kept in ONE file on purpose: what the bar has drawn, as a string, plus the slot
 * holding the last one. `render` compares the signature unconditionally and stamps it LAST. The two pieces
 * only make sense together — split across files, the next person changes one and the flicker comes back under
 * the pointer, which is exactly the bug this guard exists to stop.
 *
 * **The signature has to describe everything the bar draws, and nothing else.** Too little and the skip lies:
 * a row the signature does not mention is drawn once and then frozen for the rest of the session, so an agent
 * that finished keeps its running colour and one that started never gets a pill. Too much and the skip never
 * fires: the host pushes the whole bar several times a turn, and folding in a field the row does not paint
 * means a rebuild per push — which, at the dozens of pills this row exists for, is the cost the guard was
 * written to remove. So the rule for changing either file is one sentence: **if the row starts drawing
 * something, it starts appearing here; if it stops drawing something, it stops appearing here.**
 *
 * Regression tests pin both directions (see tabs.test.js).
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
   *
   * Four things are drawn and therefore four things are described:
   *  1. the CHAT tabs — the title, whether it is the open one, and whether it is asking for attention;
   *  2. WHICH subtab is open, because that pill wears the accent and is the only one carrying a close;
   *  3. the SUBTAB row — the chat's own agents and its background tasks, read through `T.chatWork`;
   *  4. the BRANCH row, when one is open — which agent it belongs to (the row exists at all only because of
   *     that, and its `aria-label` names it) and everything in it, read through `T.openBranch`.
   *
   * Both rows are read through the functions that RENDER them, never re-derived here. A second traversal
   * would eventually disagree with the first, and a signature describing a different row than the one on
   * screen is worse than no signature: the skip would be lying rather than merely blunt.
   *
   * **What is deliberately NOT here is the rest of the tree.** A subagent inside a branch nobody has opened
   * is not drawn, so its status moving must not repaint the bar — that is most of the traffic on a session
   * running dozens. It enters the signature the moment its branch does, through `openBranch`, and leaves
   * again when the branch closes.
   */
  function drawnSignature() {
    var parts = T.state.chats.map(function (chat) {
      return [chat.id, chat.title, !!chat.selected, !!chat.attention].join(SEP);
    });
    // The open subtab by identity only: its label and its state are already described by its own entry below,
    // and `render` prunes a selection whose subject the payload dropped before it asks for this.
    parts.push(T.selected ? T.selected.kind + SEP + T.selected.id : '');
    T.chatWork().forEach(function (w) {
      parts.push(entry(w));
    });
    var branch = T.openBranch();
    parts.push(branch ? branch.rootId + SEP + branch.rootLabel : '');
    if (branch) {
      branch.items.forEach(function (w) {
        parts.push(entry(w));
      });
    }
    return parts.join(SEP);
  }

  /**
   * One drawn pill, as the fields that decide how it looks: identity, text, state — and whether it can be
   * opened into a branch, which the pill announces (`aria-expanded`) and which therefore counts as drawn.
   *
   * `hasKids` is a BOOLEAN on purpose. It flips once, when an agent's first subagent appears, and that really
   * does change the row; every later change inside a closed branch leaves it alone, which is what keeps a
   * session running dozens from repainting the bar on work nobody is looking at.
   */
  function entry(w) {
    var n = w.node;
    return [
      w.kind,
      w.id,
      w.depth,
      n.label || '',
      n.type || '',
      n.status || '',
      !!n.running,
      !!w.hasKids,
    ].join(SEP);
  }

  /** The last drawn signature, so an identical push is a no-op instead of a rebuild. */
  T.drawn = null;

  T.drawnSignature = drawnSignature;
})();
