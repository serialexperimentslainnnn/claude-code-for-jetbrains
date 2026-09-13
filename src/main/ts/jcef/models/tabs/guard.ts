(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const T = (CC.tabbar = CC.tabbar || ({} as TabbarNs));

  const SEP = String.fromCharCode(31);

  function drawnSignature(): string {
    const parts = T.state.chats.map(function (chat) {
      return [chat.id, chat.title, !!chat.selected, !!chat.attention].join(SEP);
    });
    parts.push(T.selected ? T.selected.kind + SEP + T.selected.id : '');
    T.chatWork().forEach(function (w) {
      parts.push(entry(w));
    });
    T.openBranches().forEach(function (branch) {
      parts.push(branch.rootId + SEP + branch.rootLabel);
      branch.items.forEach(function (w) {
        parts.push(entry(w));
      });
    });
    return parts.join(SEP);
  }

  function entry(w: TabWork): string {
    const n = w.node;
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

  T.drawn = null;

  T.drawnSignature = drawnSignature;
})();
