/* app-transcript-find.js — in-transcript search and the find bar.
 *
 * One subject: highlighting matches across the rendered rows, walking between them, and the Ctrl/Cmd+F
 * overlay that drives it (plus the Ctrl/Cmd+O reasoning toggle, which shares the same capture-phase key
 * handler). Extends the shared `CC.transcript` namespace created by app-transcript.js.
 */
(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});
  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  var el = TX.el;
  var safeSend = TX.safeSend;
  var conversationEl = TX.conversationEl;
  var rows = TX.rows;

  // ---- search -------------------------------------------------------------
  var currentQuery = '';
  var searchHits = [];
  var activeIndex = 0;

  // Move the active highlight to hit [i] (wrapping), scroll it into view, and refresh the counter. The find bar
  // previously marked the first hit but never scrolled to it and offered no next/prev — you could see "10 matches"
  // and never reach any of them.
  function setActiveHit(i, scroll) {
    if (!searchHits.length) {
      activeIndex = 0;
      updateFindCount();
      return;
    }
    var n = searchHits.length;
    activeIndex = ((i % n) + n) % n; // wrap both directions
    for (var k = 0; k < n; k++) searchHits[k].classList.remove('active');
    var hit = searchHits[activeIndex];
    hit.classList.add('active');
    // Only scroll on an explicit navigation (fresh query / next / prev). The silent re-highlight that runs on
    // every streaming batch must NOT scroll, or it would yank the viewport to the active match on every frame
    // and fight auto-follow.
    if (scroll) {
      try {
        hit.scrollIntoView({ block: 'center', inline: 'nearest' });
      } catch (e) {
        /* older engines */
      }
    }
    updateFindCount();
  }
  function nextHit() {
    setActiveHit(activeIndex + 1, true);
  }
  function prevHit() {
    setActiveHit(activeIndex - 1, true);
  }

  function clearHighlights() {
    var c = conversationEl();
    if (!c) {
      return;
    }
    var marks = c.querySelectorAll('mark.cc-hit');
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i];
      var parent = m.parentNode;
      if (!parent) {
        continue;
      }
      // replace mark with its text
      var txt = document.createTextNode(m.textContent || '');
      parent.replaceChild(txt, m);
      parent.normalize();
    }
    searchHits = [];
  }

  function highlightInNode(node, lower) {
    var count = 0;
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null, false);
    var textNodes = [];
    var n;
    while ((n = walker.nextNode())) {
      // skip text already inside code-head copy controls etc. — fine to include
      if (n.nodeValue && n.nodeValue.length) {
        textNodes.push(n);
      }
    }
    for (var i = 0; i < textNodes.length; i++) {
      var tn = textNodes[i];
      var val = tn.nodeValue;
      var hay = val.toLowerCase();
      if (hay.indexOf(lower) === -1) {
        continue;
      }
      var frag = document.createDocumentFragment();
      var idx = 0;
      var pos;
      while ((pos = hay.indexOf(lower, idx)) !== -1) {
        if (pos > idx) {
          frag.appendChild(document.createTextNode(val.slice(idx, pos)));
        }
        var mark = document.createElement('mark');
        mark.className = 'cc-hit';
        mark.textContent = val.slice(pos, pos + lower.length);
        frag.appendChild(mark);
        searchHits.push(mark);
        count++;
        idx = pos + lower.length;
      }
      if (idx < val.length) {
        frag.appendChild(document.createTextNode(val.slice(idx)));
      }
      if (tn.parentNode) {
        tn.parentNode.replaceChild(frag, tn);
      }
    }
    return count;
  }

  function runSearch(q, silent) {
    clearHighlights();
    currentQuery = q || '';
    if (!currentQuery) {
      if (!silent) {
        safeSend({ type: 'search', count: 0 });
      }
      return;
    }
    var lower = currentQuery.toLowerCase();
    var total = 0;
    rows.forEach(function (rec) {
      if (!rec || !rec.bodyNode) {
        return;
      }
      total += highlightInNode(rec.bodyNode, lower);
    });
    if (searchHits.length) {
      // Fresh (non-silent) query → jump+scroll to the first hit. Silent re-highlight (streaming batch) → only
      // restore the active class at the current position, NEVER scroll (that yanked the viewport every frame).
      if (silent) {
        setActiveHit(Math.min(activeIndex, searchHits.length - 1), false);
      } else {
        setActiveHit(0, true);
      }
    }
    if (!silent) {
      safeSend({ type: 'search', count: total });
    }
  }
  TX.runSearch = runSearch;

  /** Re-apply the active highlight to the bodies a batch just rebuilt. No-op when nothing is being searched. */
  TX.refreshSearch = function () {
    if (currentQuery) {
      runSearch(currentQuery, true);
    }
  };

  /** cc.clear() emptied the transcript: there is nothing left to have found. */
  TX.resetSearch = function () {
    currentQuery = '';
    searchHits = [];
  };

  // ---- find bar overlay (Ctrl/Cmd+F) -------------------------------------
  // Lightweight, null-safe in-transcript search UI. Typing drives the existing
  // highlight path (CC.emit('search', q) when available, else runSearch); the
  // match count is shown locally. Esc / ✕ closes and clears highlights.
  var findBar = null;
  var findInput = null;
  var findCount = null;

  function emitSearch(q) {
    if (CC.emit) {
      try {
        CC.emit('search', q);
        return;
      } catch (e) {
        /* fall through */
      }
    }
    runSearch(q, false);
  }

  function updateFindCount() {
    if (!findCount) {
      return;
    }
    var q = (findInput && findInput.value) || '';
    if (!q) {
      findCount.textContent = '';
      return;
    }
    var n = searchHits.length;
    findCount.textContent = n === 0 ? 'No results' : activeIndex + 1 + ' / ' + n;
  }
  TX.updateFindCount = updateFindCount;

  function ensureFindBar() {
    if (findBar) {
      return findBar;
    }
    findInput = el('input', {
      class: 'find-input',
      attrs: { type: 'text', placeholder: 'Find…', spellcheck: 'false' },
    });
    findCount = el('span', { class: 'find-count' });
    var closeBtn = el('span', {
      class: 'find-x',
      text: '✕',
      title: 'Close',
      attrs: { role: 'button' },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          closeFindBar();
        },
      },
    });
    findBar = el('div', { class: 'find-bar' });
    findBar.hidden = true;
    findBar.appendChild(findInput);
    findBar.appendChild(findCount);
    findBar.appendChild(closeBtn);

    findInput.addEventListener('input', function () {
      emitSearch(findInput.value || '');
      updateFindCount();
    });
    findInput.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' || e.keyCode === 27) {
        e.preventDefault();
        e.stopPropagation();
        closeFindBar();
      } else if (e.key === 'Enter' || e.keyCode === 13) {
        // Enter → next match, Shift+Enter → previous (standard find-bar navigation).
        e.preventDefault();
        if (e.shiftKey) prevHit();
        else nextHit();
      }
    });

    // The WORK AREA, not the body. Mounted on the body the bar is positioned against the viewport, and in a
    // narrow tool window a bar that spans it covers the tab row outright — so tabbing to a chat focused
    // something nobody could see (WCAG 2.2 SC 2.4.11, Focus Not Obscured). Inside `#work` it cannot reach the
    // tabs at all, because `#work` does not contain them; that is a guarantee of the structure rather than a
    // z-index kept out of their way by hand. It is also what its `top` is now measured from — the rule lives
    // in `css/dashboard.css` (`.find-bar`), and inside `#work` that offset starts below the tab row instead of
    // at the edge of the window. The body stays as the way out if the work area is ever absent — a find bar
    // somewhere is better than none.
    var host = document.getElementById('work') || document.body || conversationEl();
    if (host) {
      host.appendChild(findBar);
    }
    return findBar;
  }

  function openFindBar() {
    ensureFindBar();
    if (!findBar) {
      return;
    }
    findBar.hidden = false;
    if (findInput) {
      try {
        findInput.focus();
        findInput.select();
      } catch (e) {
        /* ignore */
      }
      if (findInput.value) {
        emitSearch(findInput.value);
        updateFindCount();
      }
    }
  }

  function closeFindBar() {
    if (findBar) {
      findBar.hidden = true;
    }
    emitSearch('');
    if (findCount) {
      findCount.textContent = '';
    }
  }

  document.addEventListener(
    'keydown',
    function (e) {
      var key = e.key;
      var isF = key === 'f' || key === 'F' || e.keyCode === 70;
      var isO = key === 'o' || key === 'O' || e.keyCode === 79;
      if (isF && (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey) {
        e.preventDefault();
        openFindBar();
      } else if (isO && (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey) {
        e.preventDefault();
        cc.toggleReasoning();
      } else if ((key === 'Escape' || e.keyCode === 27) && findBar && !findBar.hidden) {
        e.preventDefault();
        // Stop the event here so closing the find bar doesn't ALSO reach the composer's Escape handler, which
        // would interrupt the running turn (capture phase runs before the composer's bubble handler).
        e.stopPropagation();
        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
        closeFindBar();
      }
    },
    true
  ); // capture phase — beat in-view handlers; IDE-level capture is handled host-side if needed
})();
