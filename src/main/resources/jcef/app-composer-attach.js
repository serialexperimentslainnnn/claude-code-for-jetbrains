/* app-composer-attach.js — attachments: the 📎 menu, the chip row, and images.
 *
 * One subject: everything the composer carries alongside the prompt — where an attachment comes from (the
 * attach menu, a drag, a paste), how it is shown as a chip, and how it is removed. The popup machinery it
 * borrows (`CX.openMenu`/`closeMenu`/`positionMenu`) lives in app-composer-menus.js.
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

  // ---- attach menu (📎) ------------------------------------------------------
  // The 📎 button opens a small popup of context sources rather than jumping
  // straight to a file picker: files, a directory, the current editor selection,
  // the current file, and a Wayland-safe "paste image from clipboard" (read by
  // the host via AWT, since JCEF's web clipboard is unreliable under Wayland).
  // Rich attach menu (AI-Assistant-style): a search box, the attach actions, and a filterable
  // "Recent files" list. Recent files + available-context flags come from the host via cc.attachData.
  var lastAttachData = { recent: [], hasSelection: false, hasFile: false };

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

  function renderAttachMenu(menu) {
    menu.innerHTML = '';
    var search = h('input', {
      class: 'attach-search',
      attrs: { type: 'text', placeholder: 'Search recent files…' },
    });
    var list = h('div', { class: 'attach-list' });

    function paint(q) {
      list.innerHTML = '';
      var actions = [
        {
          label: 'Files…',
          fn: function () {
            send({ type: 'pickFiles' });
          },
        },
        {
          label: 'Directory…',
          fn: function () {
            send({ type: 'pickDirectory' });
          },
        },
        {
          label: 'Image…',
          fn: function () {
            send({ type: 'pasteClipboardImage', notify: true });
          },
        },
      ];
      if (lastAttachData.hasSelection)
        actions.push({
          label: 'Current selection',
          fn: function () {
            send({ type: 'attachSelection' });
          },
        });
      if (lastAttachData.hasFile)
        actions.push({
          label: 'Current file',
          fn: function () {
            send({ type: 'attachCurrentFile' });
          },
        });
      actions.forEach(function (a) {
        list.appendChild(
          attachMenuItem(a.label, function () {
            CX.closeMenu();
            a.fn();
          })
        );
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

    menu.appendChild(search);
    menu.appendChild(list);
    search.addEventListener('input', function () {
      paint(search.value);
      if (CX.openMenu && CX.openMenu.anchor) CX.positionMenu(menu, CX.openMenu.anchor);
    });
    search.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        e.preventDefault();
        CX.closeMenu();
      }
    });
    paint('');
    setTimeout(function () {
      try {
        search.focus();
      } catch (e) {
        /* ignore */
      }
    }, 0);
  }

  CX.toggleAttachMenu = function (anchorEl) {
    if (CX.openMenu && CX.openMenu.pill === '__attach') {
      CX.closeMenu();
      return;
    }
    CX.closeMenu();
    var menu = h('div', { class: 'menu attach-menu' });
    document.body.appendChild(menu);
    CX.openMenu = { el: menu, pill: '__attach', anchor: anchorEl };
    renderAttachMenu(menu);
    CX.positionMenu(menu, anchorEl);
    anchorEl.classList.add('pill-open');
    send({ type: 'requestAttachData' }); // refresh recents + context; cc.attachData re-renders
  };

  // Host pushes recent files + available context; re-render the menu if it's open.
  cc.attachData = function (payload) {
    if (payload && typeof payload === 'object') {
      lastAttachData = {
        recent: Array.isArray(payload.recent) ? payload.recent : [],
        hasSelection: !!payload.hasSelection,
        hasFile: !!payload.hasFile,
      };
    }
    if (CX.openMenu && CX.openMenu.pill === '__attach' && CX.openMenu.el) {
      renderAttachMenu(CX.openMenu.el);
      CX.positionMenu(CX.openMenu.el, CX.openMenu.anchor);
    }
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
