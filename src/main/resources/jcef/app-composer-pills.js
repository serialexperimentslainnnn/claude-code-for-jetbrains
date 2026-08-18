/* app-composer-pills.js — the provider / model / mode / effort / thinking chips.
 *
 * One subject: the five pills on the composer's control bar — what each one is called, what it sends when you
 * pick something, what it looks like, and how an already-open menu is kept in step with a streaming state.
 * The popup they open lives in app-composer-menus.js.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  // pill key → which state field + how to map a chosen option to a message
  var PILL_DEFS = [
    {
      key: 'provider',
      field: 'provider',
      idKey: 'id',
      msg: function (o) {
        return { type: 'changeProvider', id: o.id };
      },
    },
    {
      key: 'model',
      field: 'model',
      idKey: 'value',
      msg: function (o) {
        return { type: 'changeModel', value: o.value };
      },
    },
    {
      key: 'mode',
      field: 'mode',
      idKey: 'wire',
      msg: function (o) {
        return { type: 'changeMode', wire: o.wire };
      },
    },
    {
      key: 'effort',
      field: 'effort',
      idKey: 'value',
      msg: function (o) {
        return { type: 'changeEffort', value: o.value == null ? null : o.value };
      },
    },
    {
      key: 'thinking',
      field: 'thinking',
      idKey: 'on',
      msg: function (o) {
        return { type: 'changeThinking', on: !!o.on };
      },
    },
  ];
  CX.PILL_DEFS = PILL_DEFS;

  // Inline chip icons for the composer pills (themeable via currentColor; ride Vibe Mode).
  // Ported from resources/icons/chip-*.svg + provider marks.
  var CHIP_ICONS = {
    model:
      '<rect x="5" y="5" width="6" height="6" rx="1.2"/><path d="M6.5 3v1.8M9.5 3v1.8M6.5 11.2V13M9.5 11.2V13M3 6.5h1.8M3 9.5h1.8M11.2 6.5H13M11.2 9.5H13"/>',
    mode: '<path d="M8 2.5 13 4.3v3.4c0 3-2.1 4.9-5 5.8-2.9-.9-5-2.8-5-5.8V4.3z"/><path d="M6 7.8 7.5 9.3 10.2 6.4"/>',
    effort: '<path d="M2.8 11.6a5.2 5.2 0 0 1 10.4 0"/><path d="M8 11.6 10.4 8.1"/>',
    thinking:
      '<path d="M7.5 2.4c.5 2.9 1.6 4 4.5 4.5-2.9.5-4 1.6-4.5 4.5-.5-2.9-1.6-4-4.5-4.5 2.9-.5 4-1.6 4.5-4.5z"/>',
  };
  // Provider brand marks keep their own colours (as in the previous UI), so they're separate
  // from the monochrome currentColor chips. provider.svg from resources/icons/provider-*.svg.
  var PROVIDER_MARKS = {
    anthropic:
      '<g stroke="#D97757" stroke-width="1.7" stroke-linecap="round" fill="none"><path d="M8 2.4v11.2M3.15 5.2l9.7 5.6M12.85 5.2l-9.7 5.6"/></g>',
    deepseek:
      '<path d="M2.4 8.4c0-2.1 1.9-3.8 4.4-3.8 2.3 0 4.2 1.4 4.6 3.4.6-.2 1.1-.6 1.5-1.1.1 1-.3 1.9-1 2.5.5.1 1 .05 1.5-.2-.5 1.2-1.8 2.1-3.4 2.1H6.8c-2.5 0-4.4-1.8-4.4-3.9z" fill="#4D6BFE"/><circle cx="6" cy="7.7" r=".75" fill="#FFFFFF"/>',
  };
  function providerMarkSvg(id) {
    var inner = PROVIDER_MARKS[id] || PROVIDER_MARKS.anthropic;
    return '<svg viewBox="0 0 16 16" aria-hidden="true">' + inner + '</svg>';
  }
  function chipIconSvg(key) {
    if (key === 'provider') return providerMarkSvg('anthropic'); // refreshed per selection in renderPills
    var inner = CHIP_ICONS[key];
    if (!inner) return null;
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      inner +
      '</svg>'
    );
  }

  CX.buildPill = function (def) {
    var label = h('span', { class: 'pill-label', text: '' });
    var caret = h('span', { class: 'pill-caret', text: '▾' });
    var iconMarkup = chipIconSvg(def.key);
    var icon = iconMarkup ? h('span', { class: 'pill-icon', html: iconMarkup }) : null;
    var el = h(
      'button',
      {
        class: 'pill',
        attrs: { type: 'button', 'data-pill': def.key },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            CX.togglePillMenu(def, el);
          },
        },
      },
      icon,
      label,
      caret
    );
    return { el: el, label: label, def: def, icon: icon };
  };

  // ---- pills label ----------------------------------------------------------
  function pillLabelFor(s, def) {
    var f = s[def.field];
    if (!f) return null;
    if (f.label != null) return String(f.label);
    return null;
  }

  CX.renderPills = function (s) {
    var els = CX.els;
    if (!els || !els.pills) return;
    for (var i = 0; i < PILL_DEFS.length; i++) {
      var def = PILL_DEFS[i];
      var pill = els.pills[def.key];
      if (!pill) continue;
      var label = pillLabelFor(s, def);
      var f = s[def.field];
      var hasOpts = f && Array.isArray(f.options) && f.options.length > 0;
      if (label == null) {
        pill.el.setAttribute('hidden', 'hidden');
      } else {
        pill.el.removeAttribute('hidden');
        pill.label.textContent = label;
        // The pill's own name, which it did not have: the chip shows a VALUE (`Opus 5 with 1M context`,
        // `High`) and nothing on it says which setting that value belongs to. It is the tooltip, and it is
        // also what the overflow menu reads when the pill does not fit on the bar — a row that says only
        // "High" there is a menu entry nobody can act on.
        pill.el.title = def.key.charAt(0).toUpperCase() + def.key.slice(1) + ': ' + label;
        if (hasOpts) pill.el.removeAttribute('disabled');
        else pill.el.setAttribute('disabled', 'disabled');
      }
      // Provider chip shows the active brand mark (Anthropic / DeepSeek).
      if (def.key === 'provider' && pill.icon && f && f.id) {
        pill.icon.innerHTML = providerMarkSvg(String(f.id));
      }
    }
  };

  // If a pill menu is open, only rebuild it when its selection actually changed — reopening on every state
  // push (frequent during streaming) made the menu flicker and de-selected the item under the cursor. The
  // attach menu (__attach) is never touched here; it has its own refresh path (cc.attachData).
  CX.syncOpenMenu = function () {
    var openMenu = CX.openMenu;
    if (openMenu && openMenu.pill !== '__attach') {
      var key = openMenu.pill;
      var def = null;
      for (var i = 0; i < PILL_DEFS.length; i++) if (PILL_DEFS[i].key === key) def = PILL_DEFS[i];
      if (def && CX.menuSig(def) !== openMenu.sig) {
        var anchor = openMenu.anchor;
        CX.closeMenu();
        CX.togglePillMenu(def, anchor);
      }
    }
  };
})();
