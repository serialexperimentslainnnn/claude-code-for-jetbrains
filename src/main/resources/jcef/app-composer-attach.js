(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;
  var send = CX.send;

  var attachmentsList = [];

  CX.attachGlyph = function () {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M12.75 7.5 7.5 12.75a3 3 0 0 1-4.25-4.25l5.75-5.75a2 2 0 0 1 2.83 2.83l-5.75 5.75a1 1 0 0 1-1.42-1.42l5.09-5.09"/></svg>'
    );
  };
  function attIconGlyph(kind) {
    if (kind === 'image') {
      return (
        '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
        '<rect x="3" y="4" width="18" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="2"/>' +
        '<circle cx="8.5" cy="9" r="1.6" fill="currentColor"/>' +
        '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" d="m4 18 5-5 4 4 3-3 4 4"/></svg>'
      );
    }
    if (kind === 'selection') {
      return (
        '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
        '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ' +
        'd="M4 7V5a1 1 0 0 1 1-1h2M4 17v2a1 1 0 0 0 1 1h2M20 7V5a1 1 0 0 0-1-1h-2M20 17v2a1 1 0 0 1-1 1h-2M8 12h8"/></svg>'
      );
    }
    return (
      '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
      '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" ' +
      'd="M6 2h7l5 5v15a0 0 0 0 1 0 0H6a0 0 0 0 1 0 0V2Z"/>' +
      '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" d="M13 2v5h5"/></svg>'
    );
  }

  function folderGlyph() {
    return (
      '<svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor" aria-hidden="true">' +
      '<path d="M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/></svg>'
    );
  }

  var lastAttachData = { recent: [], hasSelection: false, hasFile: false };

  var view = 'root';

  var tree = null;

  var groupSeq = 0;

  var MODES = {
    files: { title: 'Project files', search: 'Search files in project…' },
    directories: { title: 'Project folders', search: 'Search folders in project…' },
  };

  function isText(v) {
    return typeof v === 'string' && v !== '';
  }

  function fileIconGlyph(ext) {
    var e = (ext || '').toLowerCase();
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].indexOf(e) !== -1) return attIconGlyph('image');
    return attIconGlyph('file');
  }

  function attachMenuItem(label, onClick) {
    return h(
      'div',
      {
        class: 'menu-item',
        attrs: { role: 'option' },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            onClick();
          },
        },
      },
      h('span', { class: 'menu-item-label', text: label })
    );
  }

  function bodyEl() {
    var menu = CX.openMenu && CX.openMenu.pill === '__attach' ? CX.openMenu.el : null;
    return menu ? menu.querySelector('.attach-body') : null;
  }

  function reposition() {
    if (CX.openMenu && CX.openMenu.el && CX.openMenu.anchor) {
      CX.positionMenu(CX.openMenu.el, CX.openMenu.anchor);
    }
  }

  function renderAttachMenu(menu, from, focusSearch) {
    menu.innerHTML = '';
    var body = h('div', { class: 'attach-body' });
    if (from) body.classList.add(from);
    menu.appendChild(body);
    if (view === 'tree') buildTreeView(body);
    else buildRootView(body);
    reposition();
    if (focusSearch !== false) focusSearchSoon(body);
  }

  function focusSearchSoon(body) {
    var search = body.querySelector('.attach-search');
    if (!search) return;
    setTimeout(function () {
      try {
        search.focus();
      } catch (e) {}
    }, 0);
  }

  function buildRootView(body) {
    var search = h('input', {
      class: 'attach-search',
      attrs: { type: 'text', placeholder: 'Search recent files…', 'aria-label': 'Search recent files' },
    });
    var list = h('div', { class: 'attach-list' });

    function paint(q) {
      list.innerHTML = '';
      var actions = [
        { label: 'Files…', fn: enterFiles },
        { label: 'Directory…', fn: enterDirectories },
        {
          label: 'Image…',
          fn: function () {
            CX.closeMenu();
            send({ type: 'pasteClipboardImage', notify: true });
          },
        },
      ];
      if (lastAttachData.hasSelection)
        actions.push({
          label: 'Current selection',
          fn: function () {
            CX.closeMenu();
            send({ type: 'attachSelection' });
          },
        });
      if (lastAttachData.hasFile)
        actions.push({
          label: 'Current file',
          fn: function () {
            CX.closeMenu();
            send({ type: 'attachCurrentFile' });
          },
        });
      actions.forEach(function (a) {
        list.appendChild(attachMenuItem(a.label, a.fn));
      });

      var recent = Array.isArray(lastAttachData.recent) ? lastAttachData.recent : [];
      var ql = (q || '').toLowerCase();
      var matched = recent.filter(function (r) {
        return (
          !ql ||
          String(r.name || '')
            .toLowerCase()
            .indexOf(ql) !== -1 ||
          String(r.path || '')
            .toLowerCase()
            .indexOf(ql) !== -1
        );
      });
      if (matched.length) {
        list.appendChild(h('div', { class: 'attach-section', text: 'Recent files' }));
        matched.forEach(function (r) {
          var row = h(
            'div',
            {
              class: 'menu-item attach-recent',
              attrs: { role: 'option', title: String(r.path || '') },
              on: {
                click: function (e) {
                  e.preventDefault();
                  e.stopPropagation();
                  CX.closeMenu();
                  send({ type: 'attachPath', path: r.path });
                },
              },
            },
            h('span', { class: 'attach-icon', html: fileIconGlyph(r.ext) }),
            h('span', { class: 'attach-name', text: String(r.name || r.path || '') })
          );
          list.appendChild(row);
        });
      }
    }

    body.appendChild(search);
    body.appendChild(list);
    search.addEventListener('input', function () {
      paint(search.value);
      reposition();
    });
    paint('');
  }

  function newDir() {
    return { entries: null, pending: false, truncated: false };
  }

  function enterFiles() {
    enterTree('files');
  }

  function enterDirectories() {
    enterTree('directories');
  }

  function enterTree(mode) {
    var menu = CX.openMenu && CX.openMenu.pill === '__attach' ? CX.openMenu.el : null;
    if (!menu) return;
    view = 'tree';
    tree = {
      mode: mode,
      multi: false,
      query: '',
      dirs: { '': newDir() },
      open: {},
      sel: {},
      exp: {},
      capped: {},
    };
    requestChildren('');
    renderAttachMenu(menu, 'attach-from-right');
  }

  function leaveTree() {
    var menu = CX.openMenu && CX.openMenu.pill === '__attach' ? CX.openMenu.el : null;
    if (!menu) return;
    view = 'root';
    tree = null;
    renderAttachMenu(menu, 'attach-from-left');
  }

  function requestChildren(path) {
    var node = tree.dirs[path] || (tree.dirs[path] = newDir());
    if (node.entries || node.pending) return;
    node.pending = true;
    send({ type: 'treeChildren', path: path, mode: tree.mode });
  }

  function buildTreeView(body) {
    var conf = MODES[tree.mode] || MODES.files;

    var back = h(
      'button',
      {
        class: 'attach-back',
        title: 'Back',
        attrs: { type: 'button', 'aria-label': 'Back to the attach menu' },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            leaveTree();
          },
        },
      },
      h('span', { text: '←', attrs: { 'aria-hidden': 'true' } })
    );
    var multi = h('button', {
      class: 'attach-multi',
      title: 'Select multiple',
      text: 'Multiple',
      attrs: {
        type: 'button',
        'aria-label': 'Select multiple',
        'aria-pressed': tree.multi ? 'true' : 'false',
      },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          setMulti(!tree.multi);
        },
      },
    });
    var head = h(
      'div',
      { class: 'attach-head' },
      back,
      h('span', { class: 'attach-title', text: conf.title }),
      multi
    );
    if (tree.multi) head.appendChild(doneButton());

    var search = h('input', {
      class: 'attach-search',
      attrs: { type: 'text', placeholder: conf.search, 'aria-label': conf.search },
    });
    search.value = tree.query;
    search.addEventListener('input', function () {
      tree.query = search.value.toLowerCase();
      renderTree();
    });

    var list = h('div', { class: 'attach-list' });
    list.appendChild(
      h('div', {
        class: 'tree',
        attrs: {
          role: 'tree',
          'aria-label': conf.title,
          'aria-multiselectable': tree.multi ? 'true' : 'false',
        },
      })
    );

    body.appendChild(head);
    body.appendChild(search);
    body.appendChild(list);
    renderTree();
  }

  function doneButton() {
    var n = selectedCount();
    var btn = h('button', {
      class: 'attach-done',
      text: 'Attach ' + n,
      attrs: { type: 'button' },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          confirmSelection();
        },
      },
    });
    if (!n) btn.setAttribute('disabled', 'disabled');
    return btn;
  }

  function setMulti(on) {
    tree.multi = !!on;
    if (!tree.multi) tree.sel = {};
    var body = bodyEl();
    if (!body) return;
    body.innerHTML = '';
    buildTreeView(body);
    reposition();
    if (CC.announce) CC.announce(tree.multi ? 'Multiple selection on' : 'Multiple selection off');
  }

  function confirmSelection() {
    var paths = Object.keys(tree.sel);
    if (!paths.length) return;
    CX.closeMenu();
    send({ type: 'attachPaths', paths: paths });
  }

  function selectedCount() {
    return Object.keys(tree.sel).length;
  }

  var matchCache = null;

  function matchesQuery(entry) {
    return (
      String(entry.name || '')
        .toLowerCase()
        .indexOf(tree.query) !== -1
    );
  }

  function hasMatch(path) {
    if (!matchCache) matchCache = {};
    if (Object.prototype.hasOwnProperty.call(matchCache, path)) return matchCache[path];
    matchCache[path] = false;
    var node = tree.dirs[path];
    var found = false;
    if (node && node.entries) {
      for (var i = 0; i < node.entries.length && !found; i++) {
        var e = node.entries[i];
        found = matchesQuery(e) || (e.directory && hasMatch(e.path));
      }
    }
    matchCache[path] = found;
    return found;
  }

  function openFor(path) {
    if (path === '') return true;
    if (tree.open[path]) return true;
    return tree.query !== '' && hasMatch(path);
  }

  function visibleEntry(entry) {
    if (!tree.query) return true;
    return matchesQuery(entry) || (entry.directory && hasMatch(entry.path));
  }

  function treeEl() {
    var body = bodyEl();
    return body ? body.querySelector('.tree') : null;
  }

  function renderTree() {
    var root = treeEl();
    if (!root) return;
    var list = root.parentNode;
    var scroll = list ? list.scrollTop : 0;
    var hadFocus = root.contains(document.activeElement);
    var wanted = hadFocus && document.activeElement.__ccPath != null ? document.activeElement.__ccPath : null;

    matchCache = {};
    root.setAttribute('aria-multiselectable', tree.multi ? 'true' : 'false');
    root.innerHTML = '';
    fillChildren(root, '', 1);

    if (list) list.scrollTop = scroll;
    var target = (wanted != null && rowByPath(wanted)) || null;
    if (hadFocus) focusRow(target || visibleRows()[0]);
    else setRoving(visibleRows()[0]);
    reposition();
  }

  function fillChildren(into, path, level) {
    var node = tree.dirs[path];
    if (!node || node.pending) {
      into.appendChild(noteRow('Loading…', level));
      return;
    }
    if (!node.entries) return;
    var shown = node.entries.filter(visibleEntry);
    if (!shown.length) {
      into.appendChild(noteRow(tree.query ? 'Nothing matches here.' : 'Nothing to attach here.', level));
      return;
    }
    shown.forEach(function (entry) {
      into.appendChild(nodeFor(entry, level));
    });
    if (node.truncated) {
      var more = 'Only the first ' + shown.length + ' are shown — this folder holds more.';
      into.appendChild(noteRow(more, level));
    }
  }

  function noteRow(text, level) {
    var row = h('div', {
      class: 'menu-item tree-row tree-note',
      text: text,
      attrs: { role: 'treeitem', 'aria-disabled': 'true', 'aria-level': String(level), tabindex: '-1' },
    });
    row.style.setProperty('--level', String(level));
    return row;
  }

  function nodeFor(entry, level) {
    var isDir = !!entry.directory;
    var open = isDir && openFor(entry.path);
    var wrap = h('div', { class: 'tree-node' + (open ? ' open' : '') });
    var row = h('button', {
      class: 'menu-item settings-item tree-row',
      title: entry.path,
      attrs: {
        type: 'button',
        role: 'treeitem',
        'aria-level': String(level),
        tabindex: '-1',
      },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          onRowPress(entry, e);
        },
      },
    });
    row.style.setProperty('--level', String(level));
    row.__ccPath = entry.path;
    row.__ccDir = isDir;
    row.appendChild(h('span', { class: 'tree-caret', attrs: { 'aria-hidden': 'true' } }));
    row.appendChild(
      h('span', { class: 'attach-icon', html: isDir ? folderGlyph() : fileIconGlyph(extOf(entry.name)) })
    );
    row.appendChild(h('span', { class: 'menu-item-label', text: String(entry.name || entry.path) }));
    if (isDir && tree.capped[entry.path]) {
      row.appendChild(h('span', { class: 'tree-cap', text: 'Too many' }));
    }
    applyRowState(row, entry);

    if (isDir) {
      var id = 'tree-group-' + ++groupSeq;
      row.setAttribute('aria-expanded', open ? 'true' : 'false');
      row.setAttribute('aria-controls', id);
      var kids = h('div', {
        class: 'tree-children',
        attrs: { role: 'group', 'aria-label': String(entry.name || entry.path), id: id },
      });
      if (open) fillChildren(kids, entry.path, level + 1);
      wrap.appendChild(row);
      wrap.appendChild(kids);
      return wrap;
    }
    wrap.appendChild(row);
    return wrap;
  }

  function extOf(name) {
    var s = String(name || '');
    var dot = s.lastIndexOf('.');
    return dot > 0 ? s.slice(dot + 1) : '';
  }

  function dirState(path) {
    var prefix = path + '/';
    var inside = 0;
    for (var p in tree.sel) {
      if (!Object.prototype.hasOwnProperty.call(tree.sel, p)) continue;
      if (p === path || p.indexOf(prefix) === 0) inside++;
    }
    if (!inside) return 'none';
    var exp = tree.exp[path];
    return exp && inside >= exp.length ? 'all' : 'mixed';
  }

  function applyRowState(row, entry) {
    if (!tree.multi) {
      row.removeAttribute('aria-selected');
      row.removeAttribute('aria-checked');
      return;
    }
    if (!entry.directory) {
      row.setAttribute('aria-selected', tree.sel[entry.path] ? 'true' : 'false');
      row.removeAttribute('aria-checked');
      return;
    }
    var state = dirState(entry.path);
    row.setAttribute('aria-selected', state === 'all' ? 'true' : 'false');
    if (state === 'mixed') row.setAttribute('aria-checked', 'mixed');
    else row.removeAttribute('aria-checked');
  }

  function syncSelection() {
    var root = treeEl();
    if (!root) return;
    var rows = root.querySelectorAll('.tree-row');
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      if (row.__ccPath == null) continue;
      applyRowState(row, { path: row.__ccPath, directory: row.__ccDir });
    }
    var body = bodyEl();
    var head = body ? body.querySelector('.attach-head') : null;
    var done = head ? head.querySelector('.attach-done') : null;
    if (head && done) head.replaceChild(doneButton(), done);
  }

  function markPaths(paths, on) {
    for (var i = 0; i < paths.length; i++) {
      if (on) tree.sel[paths[i]] = true;
      else delete tree.sel[paths[i]];
    }
  }

  function onRowPress(entry, e) {
    var onCaret = !!(e && e.target && e.target.classList && e.target.classList.contains('tree-caret'));
    if (entry.directory && (onCaret || (!tree.multi && tree.mode === 'files'))) {
      setOpen(entry.path, !openFor(entry.path));
      return;
    }
    if (!tree.multi) {
      CX.closeMenu();
      send({ type: 'attachPaths', paths: [entry.path] });
      return;
    }
    if (!entry.directory) {
      if (tree.sel[entry.path]) delete tree.sel[entry.path];
      else tree.sel[entry.path] = true;
      syncSelection();
      return;
    }
    toggleFolder(entry);
  }

  function setOpen(path, on) {
    if (on) {
      tree.open[path] = true;
      requestChildren(path);
    } else {
      delete tree.open[path];
    }
    renderTree();
  }

  function toggleFolder(entry) {
    var path = entry.path;
    if (dirState(path) === 'all' && tree.exp[path]) {
      markPaths(tree.exp[path], false);
      syncSelection();
      return;
    }
    if (tree.capped[path]) {
      announceCap(entry);
      return;
    }
    if (tree.exp[path]) {
      markPaths(tree.exp[path], true);
      syncSelection();
      return;
    }
    send({ type: 'treeExpand', path: path, mode: tree.mode });
  }

  function announceCap(entry) {
    if (!CC.announce) return;
    CC.announce(
      String(entry.name || entry.path) + ' holds more than can be attached at once — open it and pick inside.'
    );
  }

  function rowByPath(path) {
    var root = treeEl();
    if (!root) return null;
    var rows = root.querySelectorAll('.tree-row');
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].__ccPath === path) return rows[i];
    }
    return null;
  }

  function visibleRows() {
    var root = treeEl();
    return root ? Array.prototype.slice.call(root.querySelectorAll('.tree-row')) : [];
  }

  function setRoving(row) {
    var all = visibleRows();
    for (var i = 0; i < all.length; i++) all[i].setAttribute('tabindex', all[i] === row ? '0' : '-1');
  }

  function focusRow(row) {
    setRoving(row);
    if (row) row.focus();
  }

  function step(delta) {
    var all = visibleRows();
    if (!all.length) return;
    var at = all.indexOf(document.activeElement);
    var next = at < 0 ? (delta > 0 ? 0 : all.length - 1) : (at + delta + all.length) % all.length;
    focusRow(all[next]);
  }

  function parentRow(path) {
    var cut = String(path).lastIndexOf('/');
    return cut > 0 ? rowByPath(String(path).slice(0, cut)) : null;
  }

  function onMenuKey(e) {
    if (view !== 'tree') {
      if (e.key === 'Escape' || e.key === 'Esc') {
        e.preventDefault();
        CX.closeMenu();
      }
      return;
    }
    if (e.key === 'Escape' || e.key === 'Esc') {
      e.preventDefault();
      e.stopPropagation();
      leaveTree();
      return;
    }
    var row = document.activeElement;
    if (!row || !row.classList || !row.classList.contains('tree-row')) {
      if (e.key === 'ArrowDown' || e.key === 'Down') {
        e.preventDefault();
        focusRow(visibleRows()[0]);
      }
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'Down') {
      e.preventDefault();
      step(1);
    } else if (e.key === 'ArrowUp' || e.key === 'Up') {
      e.preventDefault();
      step(-1);
    } else if (e.key === 'ArrowRight' || e.key === 'Right') {
      e.preventDefault();
      onRight(row);
    } else if (e.key === 'ArrowLeft' || e.key === 'Left') {
      e.preventDefault();
      onLeft(row);
    } else if (e.key === 'Home') {
      e.preventDefault();
      focusRow(visibleRows()[0]);
    } else if (e.key === 'End') {
      var all = visibleRows();
      e.preventDefault();
      focusRow(all[all.length - 1]);
    }
  }

  function onRight(row) {
    if (!row.__ccDir || row.__ccPath == null) return;
    var path = row.__ccPath;
    if (row.getAttribute('aria-expanded') !== 'true') {
      setOpen(path, true);
      focusRow(rowByPath(path));
      return;
    }
    var all = visibleRows();
    var at = all.indexOf(rowByPath(path));
    if (at >= 0 && at + 1 < all.length) focusRow(all[at + 1]);
  }

  function onLeft(row) {
    if (row.__ccPath == null) return;
    var path = row.__ccPath;
    if (row.__ccDir && row.getAttribute('aria-expanded') === 'true') {
      setOpen(path, false);
      focusRow(rowByPath(path));
      return;
    }
    var up = parentRow(path);
    if (up) focusRow(up);
  }

  CX.toggleAttachMenu = function (anchorEl) {
    if (CX.openMenu && CX.openMenu.pill === '__attach') {
      CX.closeMenu();
      return;
    }
    CX.closeMenu();
    view = 'root';
    tree = null;
    var menu = h('div', { class: 'menu attach-menu' });
    menu.addEventListener('keydown', onMenuKey);
    document.body.appendChild(menu);
    CX.openMenu = { el: menu, pill: '__attach', anchor: anchorEl };
    renderAttachMenu(menu);
    anchorEl.classList.add('pill-open');
    send({ type: 'requestAttachData' });
  };

  cc.attachData = function (payload) {
    if (payload && typeof payload === 'object') {
      lastAttachData = {
        recent: Array.isArray(payload.recent) ? payload.recent : [],
        hasSelection: !!payload.hasSelection,
        hasFile: !!payload.hasFile,
      };
    }
    if (view === 'root' && CX.openMenu && CX.openMenu.pill === '__attach' && CX.openMenu.el) {
      renderAttachMenu(CX.openMenu.el);
    }
  };

  cc.treeChildren = function (payload) {
    if (!tree || !payload || typeof payload !== 'object') return;
    if (String(payload.mode || '') !== tree.mode) return;
    var path = payload.path != null ? String(payload.path) : '';
    var node = tree.dirs[path] || (tree.dirs[path] = newDir());
    node.pending = false;
    node.entries = (Array.isArray(payload.entries) ? payload.entries : []).filter(function (e) {
      return e && isText(e.path) && isText(e.name);
    });
    node.truncated = !!payload.truncated;
    if (view === 'tree') renderTree();
  };

  cc.treeExpansion = function (payload) {
    if (!tree || !payload || typeof payload !== 'object') return;
    if (String(payload.mode || '') !== tree.mode) return;
    var path = payload.path != null ? String(payload.path) : '';
    var paths = (Array.isArray(payload.paths) ? payload.paths : []).filter(isText);
    if (payload.truncated) {
      tree.capped[path] = true;
      renderTree();
      announceCap({ name: path.slice(path.lastIndexOf('/') + 1), path: path });
      return;
    }
    tree.exp[path] = paths;
    markPaths(paths, true);
    syncSelection();
  };

  function renderAttachments(list) {
    var els = CX.els;
    if (!els || !els.attachments) return;
    var row = els.attachments;
    row.innerHTML = '';
    if (!Array.isArray(list) || list.length === 0) {
      row.setAttribute('hidden', 'hidden');
      return;
    }
    row.removeAttribute('hidden');
    for (var i = 0; i < list.length; i++) {
      (function (att) {
        if (!att || att.id == null) return;
        var kind = att.kind != null ? String(att.kind) : 'file';
        var label = att.label != null ? String(att.label) : '';
        var icon = h('span', { class: 'att-icon', html: attIconGlyph(kind) });
        var name = h('span', { class: 'att-label', text: label });
        var x = h('span', {
          class: 'att-x',
          text: '✕',
          title: 'Remove attachment',
          attrs: { role: 'button', 'aria-label': 'Remove attachment' },
          on: {
            click: function (e) {
              e.preventDefault();
              e.stopPropagation();
              send({ type: 'removeAttachment', id: att.id });
            },
          },
        });
        var chip = h('span', { class: 'att-chip att-' + kind, title: label }, icon, name, x);
        row.appendChild(chip);
      })(list[i]);
    }
  }
  CX.renderAttachments = function () {
    renderAttachments(attachmentsList);
  };

  function attachImageFile(file) {
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var result = reader.result;
      if (typeof result !== 'string') return;
      var comma = result.indexOf(',');
      var base64 = comma >= 0 ? result.slice(comma + 1) : result;
      send({
        type: 'attach',
        name: file.name != null ? String(file.name) : 'image',
        mediaType: file.type != null ? String(file.type) : 'application/octet-stream',
        base64: base64,
      });
    };
    try {
      reader.readAsDataURL(file);
    } catch (e) {}
  }

  function isImageFile(f) {
    return !!(f && typeof f.type === 'string' && f.type.indexOf('image/') === 0);
  }

  CX.wireImageDrop = function (card) {
    if (!card) return;
    card.addEventListener('dragover', function (e) {
      if (e.preventDefault) e.preventDefault();
      if (e.dataTransfer) {
        try {
          e.dataTransfer.dropEffect = 'copy';
        } catch (x) {}
      }
      card.classList.add('drag-over');
    });
    card.addEventListener('dragleave', function (e) {
      if (e && e.target === card) card.classList.remove('drag-over');
    });
    card.addEventListener('drop', function (e) {
      if (e.preventDefault) e.preventDefault();
      card.classList.remove('drag-over');
      var dt = e.dataTransfer;
      if (!dt || !dt.files) return;
      for (var i = 0; i < dt.files.length; i++) {
        if (isImageFile(dt.files[i])) attachImageFile(dt.files[i]);
      }
    });
  };

  CX.insertAtCursor = function (input, text) {
    var start = input.selectionStart != null ? input.selectionStart : input.value.length;
    var end = input.selectionEnd != null ? input.selectionEnd : input.value.length;
    var v = input.value;
    input.value = v.slice(0, start) + text + v.slice(end);
    var pos = start + text.length;
    try {
      input.setSelectionRange(pos, pos);
    } catch (e) {}
    CX.autosize(input);
  };

  CX.wireImagePaste = function (input) {
    if (!input) return;
    input.addEventListener('paste', function (e) {
      if (CX.hostClipboard) {
        e.preventDefault();
        send({ type: 'pasteClipboard' });
        return;
      }

      var cd = e.clipboardData || window.clipboardData;
      if (!cd) return;

      var images = [];
      var items = cd.items;
      if (items) {
        for (var i = 0; i < items.length; i++) {
          var it = items[i];
          if (it && it.kind === 'file' && typeof it.type === 'string' && it.type.indexOf('image/') === 0) {
            var f = it.getAsFile();
            if (f) images.push(f);
          }
        }
      }
      if (images.length === 0 && cd.files && cd.files.length) {
        for (var j = 0; j < cd.files.length; j++) {
          if (isImageFile(cd.files[j])) images.push(cd.files[j]);
        }
      }
      if (images.length > 0) {
        e.preventDefault();
        for (var k = 0; k < images.length; k++) attachImageFile(images[k]);
        return;
      }

      var text;
      try {
        text = (cd.getData && (cd.getData('text/plain') || cd.getData('text'))) || '';
      } catch (x) {
        text = '';
      }
      if (text) {
        e.preventDefault();
        CX.insertAtCursor(input, text);
        return;
      }

      e.preventDefault();
      send({ type: 'pasteClipboard' });
    });
  };

  cc.attachments = function (list) {
    attachmentsList = Array.isArray(list) ? list.slice() : [];
    if (!CX.ensureBuilt()) return;
    renderAttachments(attachmentsList);
  };
})();
