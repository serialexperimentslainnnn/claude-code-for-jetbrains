/* app-composer-attach.js — attachments: the 📎 menu, the project tree inside it, the chip row, and images.
 *
 * One subject: everything the composer carries alongside the prompt — where an attachment comes from (the
 * attach menu, the project tree, a drag, a paste), how it is shown as a chip, and how it is removed. The popup
 * machinery it borrows (`CX.openMenu`/`closeMenu`/`positionMenu`) lives in app-composer-menus.js.
 *
 * TWO VIEWS, ONE POPUP. *Files…* and *Directory…* browse the project INSIDE the menu. Entering is one
 * transition — the content slides out and the project root slides in, with a back arrow in its header — and it
 * is the ONLY one: inside the tree a folder unfolds IN PLACE, the way `app-composer-settings.js` unfolds a
 * settings group, because a stack of screens can only ever show one folder at a time and marking things in two
 * different folders is the normal case.
 *
 * THE IDE'S OWN FILE CHOOSER IS GONE, and what that costs is worth stating rather than discovering. There is
 * no native picker behind these two entries any more and no `pickFiles`/`pickDirectory` message left to send:
 * the tree IS the picker. So **nothing outside the project can be attached through this menu** — the tree is
 * rooted at the project and `ProjectTree`'s containment gate means a path outside it cannot even be spelled in
 * the terms this bridge speaks. That is the deliberate trade: a browser that never leaves the project, in
 * exchange for the ability to reach a file elsewhere on the machine. The remaining routes into the tray are
 * the ones that were never a chooser — *Image…* and *Current file* here, the editor's own "Add … as @-context"
 * actions, and a drag or a paste onto the composer.
 *
 * THE CHILDREN ARE ASKED FOR WHEN A FOLDER OPENS, never when the tree does. Loading the whole project to draw
 * its root is paying for all of it up front in a popup that lives for two seconds, so each folder asks once —
 * `pending` covers the flight and a filled `entries` covers the rest of the time the menu is open. Closing the
 * menu is what forgets: a tree kept across openings would be a cache with no invalidation in a directory the
 * user is editing.
 *
 * A CLOSED FOLDER'S CHILDREN ARE NOT IN THE DOCUMENT. They are not hidden with `display: none` and skipped by
 * a filter afterwards — they are simply not rendered, so "a closed folder exposes its children neither to the
 * arrows nor to Tab" is true by construction rather than by two rules agreeing. The `role="group"` container
 * is still emitted, empty, because `aria-controls` may not point at an element that is not there.
 *
 * THE FILTER OWNS NOTHING. It never writes to `tree.open`: a folder is drawn open when the user opened it OR
 * when the current query matches something inside it (`openFor`), so clearing the query restores the tree by
 * construction and not by remembering to undo something. It searches what has been LOADED — a filter that
 * fetched the whole project to answer would undo the reason the children are lazy in the first place.
 *
 * MULTIPLE SELECTION CHANGES WHAT A ROW DOES, so it says so: the toggle is `aria-pressed`, the tree becomes
 * `aria-multiselectable`, and each row carries `aria-selected` (WCAG 1.4.1 and 4.1.2) — a painted ✓ and
 * nothing else would leave the mode invisible to anyone not looking at it. The selection lives in `tree.sel`,
 * keyed by path, so folding a folder cannot lose it: the row is destroyed, the fact is not. **Done is the only
 * path that attaches** — leaving the mode discards — and it attaches in ONE message, not one per file.
 *
 * MARKING A FOLDER MARKS WHAT IS UNDER IT, and the count says how much. The host answers `treeExpand` with the
 * paths themselves rather than a number, because counting and resolving are the same walk (see `ProjectTree`);
 * the count on the button is that list's length, so "Attach 214" is the truth about what pressing it does. Over
 * the host's ceiling the folder is REFUSED, and refused ON ITS OWN ROW at the moment it is marked — a folder
 * that silently attaches half of itself is worse than one that will not attach at all.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;
  var send = CX.send;

  var attachmentsList = []; // last cc.attachments payload: [{id,label,kind}]

  // attach.svg from the previous UI (paperclip), themed via currentColor.
  CX.attachGlyph = function () {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M12.75 7.5 7.5 12.75a3 3 0 0 1-4.25-4.25l5.75-5.75a2 2 0 0 1 2.83 2.83l-5.75 5.75a1 1 0 0 1-1.42-1.42l5.09-5.09"/></svg>'
    );
  };
  // small kind glyph for an attachment chip (file | selection | image)
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
    // file (default)
    return (
      '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
      '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" ' +
      'd="M6 2h7l5 5v15a0 0 0 0 1 0 0H6a0 0 0 0 1 0 0V2Z"/>' +
      '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" d="M13 2v5h5"/></svg>'
    );
  }

  /** A folder, drawn as a silhouette for the same reason the ⚙ wrench is: a 13px outline turns into a smudge. */
  function folderGlyph() {
    return (
      '<svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor" aria-hidden="true">' +
      '<path d="M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/></svg>'
    );
  }

  // ---- attach menu (📎) ------------------------------------------------------
  // The 📎 button opens a small popup of context sources rather than jumping
  // straight to a file picker: files, a directory, the current editor selection,
  // the current file, and a Wayland-safe "paste image from clipboard" (read by
  // the host via AWT, since JCEF's web clipboard is unreliable under Wayland).
  // Rich attach menu (AI-Assistant-style): a search box, the attach actions, and a filterable
  // "Recent files" list. Recent files + available-context flags come from the host via cc.attachData.
  var lastAttachData = { recent: [], hasSelection: false, hasFile: false };

  /** Which content the ONE popup is showing: `root` (the attach actions) or `tree` (the project). */
  var view = 'root';

  /**
   * Everything the tree knows while the menu is open, or null.
   *
   * It is rebuilt on every opening of the popup and never read from a closed one, which is what makes "asked
   * once per folder" a statement about a session with the menu open rather than a cache nobody invalidates.
   */
  var tree = null;

  /** Serial behind the `aria-controls` ids, monotonic so a rebuild can never reuse a live one. */
  var groupSeq = 0;

  /** What each picker is called, is searching, and attaches. The mode string is the host's wire value. */
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

  /** The one open popup's body, or null — the element every re-render writes into. */
  function bodyEl() {
    var menu = CX.openMenu && CX.openMenu.pill === '__attach' ? CX.openMenu.el : null;
    return menu ? menu.querySelector('.attach-body') : null;
  }

  /**
   * Re-place the popup against its anchor.
   *
   * Not cosmetic: `.menu` has a `max-height` and this one hangs UPWARDS from the composer, so a view with a
   * different height would otherwise be drawn from the old top edge and run off the bottom of the tool window.
   */
  function reposition() {
    if (CX.openMenu && CX.openMenu.el && CX.openMenu.anchor) {
      CX.positionMenu(CX.openMenu.el, CX.openMenu.anchor);
    }
  }

  /**
   * Draw the current view into the popup, sliding it in from [from] when the view changed.
   *
   * The animation is decorative and lives entirely in the stylesheet, so `body.reduced-motion` — which zeroes
   * every duration and delay — removes it with nothing here to switch off.
   */
  function renderAttachMenu(menu, from, focusSearch) {
    menu.innerHTML = '';
    var body = h('div', { class: 'attach-body' });
    if (from) body.classList.add(from);
    // Appended BEFORE it is filled: the tree looks itself up through the open popup (`bodyEl`), so a body
    // built while still detached would render into an element the rest of the module cannot find.
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
      } catch (e) {
        /* ignore */
      }
    }, 0);
  }

  // ---- the attach actions (the view the 📎 opens on) -------------------------
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

  // ---- the project tree ------------------------------------------------------
  function newDir() {
    return { entries: null, pending: false, truncated: false };
  }

  function enterFiles() {
    enterTree('files');
  }

  function enterDirectories() {
    enterTree('directories');
  }

  /**
   * Go from the attach actions to the project root. ONE transition, and the only one in this feature.
   *
   * The tree is built fresh rather than resumed: the previous one belonged to the previous opening of the
   * menu, and re-showing it would be showing a listing of a directory the user may have changed since.
   */
  function enterTree(mode) {
    var menu = CX.openMenu && CX.openMenu.pill === '__attach' ? CX.openMenu.el : null;
    if (!menu) return;
    view = 'tree';
    tree = {
      mode: mode,
      multi: false,
      query: '',
      dirs: { '': newDir() }, // path → { entries, pending, truncated }; "" is the project root
      open: {}, // path → true, the folders the USER opened; the filter never writes here
      sel: {}, // path → true, the marks, which is why folding cannot lose them
      exp: {}, // folder path → the paths marking it drags in, as the host answered
      capped: {}, // folder path → true, refused for holding more than the host's ceiling
    };
    requestChildren('');
    renderAttachMenu(menu, 'attach-from-right');
  }

  /** Back to the attach actions. Leaving the tree discards the selection: Done is the only way to attach. */
  function leaveTree() {
    var menu = CX.openMenu && CX.openMenu.pill === '__attach' ? CX.openMenu.el : null;
    if (!menu) return;
    view = 'root';
    tree = null;
    renderAttachMenu(menu, 'attach-from-left');
  }

  /**
   * Ask the host for one folder's children, at most once while the menu is open.
   *
   * `pending` covers the flight and a filled `entries` covers everything after it, so unfolding a folder that
   * was already unfolded once costs nothing — which is the whole reason the load is per folder and not per
   * tree.
   */
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
        // The glyph is hidden and the name is spelled out: an arrow is not a word, so a speech-input user
        // has nothing to say to it and a screen reader nothing to read (WCAG 4.1.2, 2.5.3).
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
        // The mode changes what pressing a row DOES, so it is a state and not a highlight (WCAG 4.1.2).
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
      // The field was searching recent files a moment ago and is searching the project now. A placeholder
      // that did not follow it would be a field that lies about what it does.
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
          // Announced only while it is true: a tree that always claimed to be multi-selectable would be
          // describing a mode the rows are not in.
          'aria-multiselectable': tree.multi ? 'true' : 'false',
        },
      })
    );

    body.appendChild(head);
    body.appendChild(search);
    body.appendChild(list);
    renderTree();
  }

  /** The count is the EXPANDED one: "Attach 1" for a folder holding 214 files would be a lie about the press. */
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
    // Leaving the mode discards, because Done is the only path that attaches — a selection that survived the
    // mode would attach itself later from somewhere the user was not looking.
    if (!tree.multi) tree.sel = {};
    var body = bodyEl();
    if (!body) return;
    body.innerHTML = '';
    buildTreeView(body);
    reposition();
    if (CC.announce) CC.announce(tree.multi ? 'Multiple selection on' : 'Multiple selection off');
  }

  /** One message for the whole batch: N attachments are one act, not N of them. */
  function confirmSelection() {
    var paths = Object.keys(tree.sel);
    if (!paths.length) return;
    CX.closeMenu();
    send({ type: 'attachPaths', paths: paths });
  }

  function selectedCount() {
    return Object.keys(tree.sel).length;
  }

  // ---- what is drawn ---------------------------------------------------------
  var matchCache = null;

  function matchesQuery(entry) {
    return (
      String(entry.name || '')
        .toLowerCase()
        .indexOf(tree.query) !== -1
    );
  }

  /** Whether anything LOADED under [path] matches the query. Memoised per render: it is walked per row. */
  function hasMatch(path) {
    // Lazily, because `openFor` is also asked outside a render — by a press — where there is no live cache.
    if (!matchCache) matchCache = {};
    if (Object.prototype.hasOwnProperty.call(matchCache, path)) return matchCache[path];
    matchCache[path] = false; // a cycle cannot happen through the index, but the guard costs one assignment
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

  /**
   * Whether a folder is drawn open.
   *
   * The query is consulted here and NEVER written into `tree.open`, which is what makes clearing the filter
   * restore the tree exactly: what the filter opened was never recorded as the user's, so there is nothing to
   * put back.
   */
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

  /**
   * Rebuild the tree, keeping the reader where they were.
   *
   * Two things do not survive a rebuild on their own and both are restored around it: the scroll offset of the
   * container (a fresh subtree is born at offset 0) and the focused row (the DOM has no move, so a rebuild
   * blurs whatever was inside it). The row is found again by PATH, not by position, because the point is to
   * survive the reorder a filter or a fold produces.
   */
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

  /**
   * The rows of one folder, appended into [into].
   *
   * A closed folder contributes NOTHING here — not a hidden row, nothing — so its children are unreachable by
   * the arrows and by Tab because they are not in the document, rather than because two rules agree that they
   * should not be visited.
   */
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
      // A listing that came back at the ceiling is "the first N of more", and the page has to say so: nothing
      // on screen distinguishes a complete answer from a cut one.
      var more = 'Only the first ' + shown.length + ' are shown — this folder holds more.';
      into.appendChild(noteRow(more, level));
    }
  }

  function noteRow(text, level) {
    // An ENTRY, not loose text: tree navigation visits entries, so anything else is text a screen-reader user
    // never arrives at — the same reason the ⚙ menu's "No quick settings yet" is a `menuitem`.
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
        tabindex: '-1', // roving: exactly one row is in the tab order (see setRoving)
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
      // On the folder's own row and at the moment it is marked, never after Done: a refusal the user reads
      // once the batch is already gone is a refusal they cannot act on.
      row.appendChild(h('span', { class: 'tree-cap', text: 'Too many' }));
    }
    applyRowState(row, entry);

    if (isDir) {
      var id = 'tree-group-' + ++groupSeq;
      row.setAttribute('aria-expanded', open ? 'true' : 'false');
      row.setAttribute('aria-controls', id);
      // Emitted even while closed, and left EMPTY: `aria-controls` may not name an element that is absent,
      // and an empty group exposes nothing.
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

  // ---- selection -------------------------------------------------------------
  /**
   * How much of a folder is marked: `all`, `mixed` or `none`.
   *
   * `mixed` is the honest answer whenever the whole is unknown — the expansion is only cached once a folder
   * has actually been marked — and the asymmetry is deliberate: `mixed` never claims that everything under a
   * folder is going, while a wrong `all` would.
   */
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

  /**
   * Repaint the marks without rebuilding anything.
   *
   * Marking one folder changes the state of every ancestor row, and a rebuild for that would blur the row the
   * user just pressed. Same discipline as the ⚙ menu's state-only path.
   */
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
    // The caret folds, the label acts. One focusable element per row (the ARIA tree pattern), two mouse
    // zones — which is how the IDE's own trees behave.
    var onCaret = !!(e && e.target && e.target.classList && e.target.classList.contains('tree-caret'));
    if (entry.directory && (onCaret || (!tree.multi && tree.mode === 'files'))) {
      setOpen(entry.path, !openFor(entry.path));
      return;
    }
    if (!tree.multi) {
      // A single press attaches exactly what was pressed and closes: a file in the file picker, the folder
      // itself in the folder picker — the same thing the IDE's own chooser would have handed back.
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

  /**
   * Mark or unmark a folder and everything under it.
   *
   * Unmarking reads the cached expansion — the only way a folder can be marked is through one — while marking
   * asks the host, because the count on the button has to be the real one before the user can press it.
   */
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

  // ---- keyboard --------------------------------------------------------------
  /** Every row on screen. A closed folder contributes none, because it rendered none. */
  function visibleRows() {
    var root = treeEl();
    return root ? Array.prototype.slice.call(root.querySelectorAll('.tree-row')) : [];
  }

  /** Roving tabindex: exactly one row is in the tab order, so Tab enters the tree once and then leaves it. */
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

  /** The row of the folder [path] belongs to, or null at the top level. */
  function parentRow(path) {
    var cut = String(path).lastIndexOf('/');
    return cut > 0 ? rowByPath(String(path).slice(0, cut)) : null;
  }

  /**
   * The keyboard model the `role="tree"` promises, plus the one rule the popup adds on top of it: **Escape in
   * the tree goes BACK**, and only the attach actions close the menu. Escape as an unconditional dismissal
   * would make the way out of a mistaken *Files…* the same press as the way out of the menu entirely.
   */
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
      // In the filter field: Down is the way into the results it just produced.
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
    // A note row (loading, empty, "more than these") is an entry the arrows visit and nothing else: it names
    // no path, so there is nothing to open and nothing to walk up to.
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

  // ---- opening the popup -----------------------------------------------------
  CX.toggleAttachMenu = function (anchorEl) {
    if (CX.openMenu && CX.openMenu.pill === '__attach') {
      CX.closeMenu();
      return;
    }
    CX.closeMenu();
    // Every opening starts on the attach actions with no tree behind it: a tree kept across openings would be
    // a listing of a directory the user has had every chance to change.
    view = 'root';
    tree = null;
    var menu = h('div', { class: 'menu attach-menu' });
    menu.addEventListener('keydown', onMenuKey);
    document.body.appendChild(menu);
    CX.openMenu = { el: menu, pill: '__attach', anchor: anchorEl };
    renderAttachMenu(menu);
    anchorEl.classList.add('pill-open');
    send({ type: 'requestAttachData' }); // refresh recents + context; cc.attachData re-renders
  };

  // ---- host → page -----------------------------------------------------------
  // Host pushes recent files + available context; re-render the menu if it's open.
  cc.attachData = function (payload) {
    if (payload && typeof payload === 'object') {
      lastAttachData = {
        recent: Array.isArray(payload.recent) ? payload.recent : [],
        hasSelection: !!payload.hasSelection,
        hasFile: !!payload.hasFile,
      };
    }
    // Only the view this payload is about. A push landing while the tree is up would otherwise throw the
    // whole browse away — the folders opened, the filter typed, the selection made — to redraw a list of
    // recent files nobody is looking at.
    if (view === 'root' && CX.openMenu && CX.openMenu.pill === '__attach' && CX.openMenu.el) {
      renderAttachMenu(CX.openMenu.el);
    }
  };

  /** One folder's children: `{path, mode, entries:[{name,path,directory}], truncated}`. */
  cc.treeChildren = function (payload) {
    if (!tree || !payload || typeof payload !== 'object') return;
    // A reply for the OTHER picker is a reply to a question that is no longer being asked: the user pressed
    // Back and came in through the other door while it was in flight.
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

  /** What marking a folder drags in: `{path, mode, paths:[…], truncated}`. Answered only for a mark. */
  cc.treeExpansion = function (payload) {
    if (!tree || !payload || typeof payload !== 'object') return;
    if (String(payload.mode || '') !== tree.mode) return;
    var path = payload.path != null ? String(payload.path) : '';
    var paths = (Array.isArray(payload.paths) ? payload.paths : []).filter(isText);
    if (payload.truncated) {
      // REFUSED, not trimmed. Attaching the first N of a folder and saying nothing is the failure this
      // branch exists to prevent; the row now carries the reason and keeps it while the menu is open.
      tree.capped[path] = true;
      renderTree();
      announceCap({ name: path.slice(path.lastIndexOf('/') + 1), path: path });
      return;
    }
    tree.exp[path] = paths;
    markPaths(paths, true);
    syncSelection();
  };

  // ---- attachments ----------------------------------------------------------
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
  /** Re-render the chip row from the last payload — called by ensureBuilt for attachments that predate it. */
  CX.renderAttachments = function () {
    renderAttachments(attachmentsList);
  };

  // Read an image File as raw base64 (no data: prefix) and emit {type:'attach'}.
  function attachImageFile(file) {
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var result = reader.result;
      if (typeof result !== 'string') return;
      // strip "data:<mime>;base64," prefix → raw payload
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
    } catch (e) {
      /* ignore unreadable file */
    }
  }

  function isImageFile(f) {
    return !!(f && typeof f.type === 'string' && f.type.indexOf('image/') === 0);
  }

  // drag image file onto the composer card → attach
  CX.wireImageDrop = function (card) {
    if (!card) return;
    card.addEventListener('dragover', function (e) {
      // signal we accept a drop (and stop the browser navigating to the file)
      if (e.preventDefault) e.preventDefault();
      if (e.dataTransfer) {
        try {
          e.dataTransfer.dropEffect = 'copy';
        } catch (x) {
          /* ignore */
        }
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

  // paste an image into the textarea → attach; non-image paste falls through
  // Insert text at the caret (JCEF's native paste is unreliable, so we do it ourselves).
  CX.insertAtCursor = function (input, text) {
    var start = input.selectionStart != null ? input.selectionStart : input.value.length;
    var end = input.selectionEnd != null ? input.selectionEnd : input.value.length;
    var v = input.value;
    input.value = v.slice(0, start) + text + v.slice(end);
    var pos = start + text.length;
    try {
      input.setSelectionRange(pos, pos);
    } catch (e) {
      /* ignore */
    }
    CX.autosize(input);
  };

  // One paste handler, mutually-exclusive branches, exactly ONE action — never duplicates.
  CX.wireImagePaste = function (input) {
    if (!input) return;
    input.addEventListener('paste', function (e) {
      // Native-Wayland toolkit: CEF's web clipboard is isolated from the system clipboard, so
      // `clipboardData` only ever exposes what was copied *inside* the web view — never the system
      // selection. Ignore it entirely and let the host read the real clipboard via wl-paste.
      if (CX.hostClipboard) {
        e.preventDefault();
        send({ type: 'pasteClipboard' });
        return;
      }

      var cd = e.clipboardData || window.clipboardData;
      if (!cd) return;

      // 1) Image already in the web clipboard (X11 / Chromium path).
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

      // 2) Plain text already in the web clipboard → insert it ourselves (one insert, no double-paste).
      var text;
      try {
        text = (cd.getData && (cd.getData('text/plain') || cd.getData('text'))) || '';
      } catch (x) {
        text = ''; // a DataTransfer that refuses getData — treat it as "no text on the clipboard"
      }
      if (text) {
        e.preventDefault();
        CX.insertAtCursor(input, text);
        return;
      }

      // 3) Web clipboard empty (the Wayland case for BOTH text and images) → let the host read the
      //    system clipboard (text via AWT, image via wl-paste) and either attach or insert text.
      e.preventDefault();
      send({ type: 'pasteClipboard' });
    });
  };

  cc.attachments = function (list) {
    attachmentsList = Array.isArray(list) ? list.slice() : [];
    if (!CX.ensureBuilt()) return; // will render on build via attachmentsList
    renderAttachments(attachmentsList);
  };
})();
