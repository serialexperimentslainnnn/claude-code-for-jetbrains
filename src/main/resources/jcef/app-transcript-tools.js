(function () {
  'use strict';

  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  var el = TX.el;
  var safeSend = TX.safeSend;
  var toolCards = TX.toolCards;

  var TOOL_ICONS = {
    bash: '<rect x="1.75" y="2.75" width="12.5" height="10.5" rx="1.75"/><path d="m4.5 6.25 2 1.75-2 1.75"/><path d="M8 10.25h3"/>',
    read: '<path d="M8 4.25C6.75 3.4 5.4 3 3.5 3H2v8.5h1.75c1.6 0 3 .35 4.25 1.25"/><path d="M8 4.25C9.25 3.4 10.6 3 12.5 3H14v8.5h-1.75c-1.6 0-3 .35-4.25 1.25"/><path d="M8 4.25v8.5"/>',
    edit: '<path d="M10.75 2.5 13.5 5.25 6 12.75 2.75 13.5l.75-3.25z"/><path d="M9.25 4 12 6.75"/>',
    search: '<circle cx="7" cy="7" r="4.25"/><path d="m10.25 10.25 3 3"/>',
    web: '<circle cx="8" cy="8" r="6"/><path d="M2 8h12"/><path d="M8 2c1.75 1.6 2.75 3.7 2.75 6S9.75 12.4 8 14C6.25 12.4 5.25 10.3 5.25 8S6.25 3.6 8 2z"/>',
    task: '<circle cx="8" cy="8" r="6"/><path d="M6.5 5.5 11 8l-4.5 2.5z" fill="currentColor"/>',
    generic:
      '<path d="M6.25 2.75h3.5v1.5a1.25 1.25 0 1 0 2.5 0v-1.5h.75v3.5h1.5a1.25 1.25 0 1 1 0 2.5h-1.5v3.5H9.5v-1.5a1.25 1.25 0 1 0-2.5 0v1.5H3.5v-3.5H2a1.25 1.25 0 1 1 0-2.5h1.5v-3.5h2.75z"/>',
  };
  function toolIconKey(meta) {
    var m = meta == null ? '' : String(meta);
    if (m === 'Bash') return 'bash';
    if (m === 'Read' || m === 'NotebookRead') return 'read';
    if (m === 'Edit' || m === 'Write' || m === 'MultiEdit' || m === 'NotebookEdit') return 'edit';
    if (m === 'Grep' || m === 'Glob' || m === 'Search') return 'search';
    if (m === 'WebFetch' || m === 'WebSearch') return 'web';
    if (m === 'Task' || m === 'Agent') return 'task';
    return 'generic';
  }
  TX.toolIconSvg = function (meta) {
    var inner = TOOL_ICONS[toolIconKey(meta)] || TOOL_ICONS.generic;
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      inner +
      '</svg>'
    );
  };

  TX.buildTool = function (entry) {
    var node = el('div', { class: 'tool' });
    var head = el('div', { class: 'tool-head' });
    head.appendChild(el('span', { class: 'ic', text: '▸' }));
    var name = el('span', { class: 'name' });
    head.appendChild(name);
    var elapsed = el('span', { class: 'tool-elapsed' });
    elapsed.hidden = true;
    head.appendChild(elapsed);
    var diffBtn = el('button', { class: 'tool-diff', text: 'View diff', attrs: { type: 'button' } });
    diffBtn.hidden = true;
    diffBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (node.__toolUseId) {
        safeSend({ type: 'viewDiffByTool', toolUseId: node.__toolUseId });
      }
    });
    head.appendChild(diffBtn);
    var restoreBtn = el('button', {
      class: 'tool-restore',
      text: 'Restore',
      attrs: { type: 'button' },
      title: 'Revert this edit',
    });
    restoreBtn.hidden = true;
    restoreBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (node.__toolUseId) {
        safeSend({ type: 'revertEdit', toolUseId: node.__toolUseId });
      }
    });
    head.appendChild(restoreBtn);
    node.__restoreBtn = restoreBtn;
    head.appendChild(el('span', { class: 'chev', text: '▾' }));
    var cmd = el('div', { class: 'tool-cmd' });
    var msg = el('div', { class: 'tool-msg' });
    var out = el('div', { class: 'tool-out' });
    var children = el('div', { class: 'tool-children' });
    head.addEventListener('click', function () {
      if (node.__isAgentCard && node.__toolUseId) {
        safeSend({ type: 'revealAgent', toolUseId: node.__toolUseId });
        return;
      }
      node.classList.toggle('open');
    });
    node.appendChild(head);
    node.appendChild(msg);
    node.appendChild(cmd);
    node.appendChild(out);
    node.appendChild(children);
    if (entry && entry.message) {
      renderMessageBlock(msg, entry.message);
      node.classList.add('msg-tool');
    }
    node.__nameNode = name;
    node.__msgNode = msg;
    node.__cmdNode = cmd;
    node.__outNode = out;
    node.__childrenNode = children;
    node.__elapsedNode = elapsed;
    node.__diffBtn = diffBtn;
    return { el: node, bodyNode: name, kind: 'tool', outNode: out };
  };

  function formatElapsed(secs) {
    var n = Number(secs);
    if (!isFinite(n) || n <= 0) {
      return '';
    }
    return '· ' + n.toFixed(1) + 's';
  }

  TX.applyToolElapsed = function (node, state, elapsedSecs) {
    var badge = node.__elapsedNode || node.querySelector('.tool-elapsed');
    if (!badge) {
      return;
    }
    var running = state === 'RUNNING' || state === 'LOADING';
    var label = running ? formatElapsed(elapsedSecs) : '';
    if (label) {
      badge.textContent = label;
      badge.hidden = false;
    } else {
      badge.textContent = '';
      badge.hidden = true;
    }
  };

  TX.applyToolState = function (node, state, meta) {
    node.classList.remove('loading', 'running', 'done', 'failed');
    if (state === 'ERROR' || meta === 'error') {
      node.classList.add('failed');
      if (!node.__autoOpenedOnError && !node.__isAgentCard) {
        node.__autoOpenedOnError = true;
        node.classList.add('open');
      }
    } else if (state === 'LOADING') {
      node.classList.add('loading');
    } else if (state === 'RUNNING') {
      node.classList.add('running');
    } else if (state === 'FINISHED') {
      node.classList.add('done');
    }
  };

  var MAX_JSON_CHARS = 200000;

  function prettyJson(text) {
    if (!text || text.length > MAX_JSON_CHARS) {
      return null;
    }
    var s = text.trim();
    var head = s.charAt(0);
    var tail = s.charAt(s.length - 1);
    if ((head !== '{' || tail !== '}') && (head !== '[' || tail !== ']')) {
      return null;
    }
    try {
      return JSON.stringify(JSON.parse(s), null, 2);
    } catch (e) {
      return null;
    }
  }

  TX.routeToolOutput = function (entry, cards) {
    if (!cards) {
      cards = toolCards;
    }
    var tid = entry.toolUseId;
    if (!tid) {
      return false;
    }
    var card = cards.get(tid);
    if (!card) {
      return false;
    }
    var out = card.__outNode || card.querySelector('.tool-out');
    if (!out) {
      return false;
    }
    var pid = 'to-' + entry.id;
    var block = out.querySelector('[data-out-id="' + pid + '"]');
    if (!block) {
      block = el('pre', {});
      block.setAttribute('data-out-id', pid);
      var code = el('code', {});
      block.appendChild(code);
      out.appendChild(block);
    }
    var codeEl = block.querySelector('code');
    if (codeEl) {
      var tags = ' ' + (entry.meta || '') + ' ';
      var fileLang =
        window.CC && typeof CC.languageForPath === 'function' ? CC.languageForPath(card.__filePath) : null;
      if (entry.meta === 'diff') {
        renderDiff(codeEl, entry.text == null ? '' : String(entry.text), fileLang);
        block.classList.add('diff');
        block.classList.remove('command');
        block.classList.remove('flow');
      } else if (tags.indexOf(' command ') >= 0) {
        block.classList.remove('diff');
        block.classList.remove('flow');
        block.classList.add('command');
        codeEl.textContent = entry.text == null ? '' : String(entry.text);
        if (window.CC && typeof CC.decorateOneCodeBlock === 'function') {
          CC.decorateOneCodeBlock(codeEl);
          var langLabel = block.querySelector('.code-lang');
          if (langLabel) {
            langLabel.textContent = 'shell';
          }
        }
      } else {
        block.classList.remove('diff');
        block.classList.remove('command');
        var raw = entry.text == null ? '' : String(entry.text);
        var json = prettyJson(raw);
        codeEl.textContent = json == null ? raw : json;
        if (card.__filePath) {
          block.classList.remove('flow');
          if (window.CC && typeof CC.decorateOneCodeBlock === 'function') {
            if (fileLang) {
              codeEl.className = 'language-' + fileLang;
            }
            CC.decorateOneCodeBlock(codeEl);
          }
        } else {
          block.classList.add('flow');
          if (json != null && window.CC && typeof CC.decorateOneCodeBlock === 'function') {
            codeEl.className = 'language-json';
            CC.decorateOneCodeBlock(codeEl);
          }
        }
      }
    }
    return true;
  };

  function renderDiff(codeEl, text, lang) {
    codeEl.innerHTML = '';
    var lines = String(text).split('\n');
    var hljs = window.hljs;
    var canHighlight = !!(
      lang &&
      hljs &&
      typeof hljs.getLanguage === 'function' &&
      hljs.getLanguage(lang) &&
      typeof hljs.highlight === 'function'
    );
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var c0 = line.charAt(0);
      var isHunk = line.indexOf('@@') === 0;
      var cls = 'dl-ctx';
      if (isHunk) {
        cls = 'dl-hunk';
      } else if (c0 === '+') {
        cls = 'dl-add';
      } else if (c0 === '-') {
        cls = 'dl-del';
      }
      var span = el('span', { class: 'diff-line ' + cls });
      var trailingNl = i < lines.length - 1;
      var highlighted = false;
      if (!isHunk && canHighlight && line.length > 0) {
        try {
          var hi = hljs.highlight(line.slice(1), { language: lang, ignoreIllegals: true }).value;
          span.appendChild(document.createTextNode(c0));
          span.insertAdjacentHTML('beforeend', hi);
          if (trailingNl) {
            span.appendChild(document.createTextNode('\n'));
          }
          highlighted = true;
        } catch (e) {}
      }
      if (!highlighted) {
        span.textContent = trailingNl ? line + '\n' : line;
      }
      codeEl.appendChild(span);
    }
  }

  function carriedBlock(node, text, blockClass, label) {
    if (!node) {
      return;
    }
    var block = el('pre', { class: blockClass });
    var code = el('code', {});
    code.textContent = String(text);
    block.appendChild(code);
    node.appendChild(block);
    if (window.CC && typeof CC.decorateOneCodeBlock === 'function') {
      CC.decorateOneCodeBlock(code);
      var langLabel = block.querySelector('.code-lang');
      if (langLabel) {
        langLabel.textContent = label;
      }
    }
  }

  TX.renderCommandBlock = function (cmdNode, commandText) {
    carriedBlock(cmdNode, commandText, 'command-src', 'shell');
  };

  function renderMessageBlock(msgNode, messageText) {
    carriedBlock(msgNode, messageText, 'message-src', 'message');
  }

  TX.jbHref = function (relPath, line) {
    var u = 'jb://open?file=' + encodeURIComponent(String(relPath));
    if (line) {
      u += '&line=' + encodeURIComponent(String(line));
    }
    return u;
  };

  TX.renderToolLabel = function (nameEl, text, filePath) {
    if (!nameEl) {
      return;
    }
    var label = text == null ? '' : String(text);
    var p = String(filePath);
    var at = label.indexOf(p);
    if (at < 0) {
      nameEl.textContent = label;
      return;
    }
    while (nameEl.firstChild) {
      nameEl.removeChild(nameEl.firstChild);
    }
    nameEl.appendChild(document.createTextNode(label.slice(0, at)));
    var a = el('a', { class: 'jb-link', text: p, attrs: { href: TX.jbHref(p), title: 'Open ' + p } });
    a.addEventListener('click', function (e) {
      e.stopPropagation();
    });
    nameEl.appendChild(a);
    nameEl.appendChild(document.createTextNode(label.slice(at + p.length)));
  };
})();
