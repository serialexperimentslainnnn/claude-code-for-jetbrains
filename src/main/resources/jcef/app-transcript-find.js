(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});
  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  var el = TX.el;
  var safeSend = TX.safeSend;
  var conversationEl = TX.conversationEl;
  var rows = TX.rows;

  var currentQuery = '';
  var searchHits = [];
  var activeIndex = 0;

  function setActiveHit(i, scroll) {
    if (!searchHits.length) {
      activeIndex = 0;
      updateFindCount();
      return;
    }
    var n = searchHits.length;
    activeIndex = ((i % n) + n) % n;
    for (var k = 0; k < n; k++) searchHits[k].classList.remove('active');
    var hit = searchHits[activeIndex];
    hit.classList.add('active');
    if (scroll) {
      try {
        hit.scrollIntoView({ block: 'center', inline: 'nearest' });
      } catch (e) {}
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

  TX.refreshSearch = function () {
    if (currentQuery) {
      runSearch(currentQuery, true);
    }
  };

  TX.resetSearch = function () {
    currentQuery = '';
    searchHits = [];
  };

  var findBar = null;
  var findInput = null;
  var findCount = null;

  function emitSearch(q) {
    if (CC.emit) {
      try {
        CC.emit('search', q);
        return;
      } catch (e) {}
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
        e.preventDefault();
        if (e.shiftKey) prevHit();
        else nextHit();
      }
    });

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
      } catch (e) {}
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
        e.stopPropagation();
        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
        closeFindBar();
      }
    },
    true
  );
})();
