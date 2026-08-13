/* app-composer-base.js — the composer family's shared plumbing.
 *
 * One subject: what every composer file needs before it can do anything — the `CC.composer` namespace (there
 * is no module system here, so that object IS the interface between these scripts), the local `h`/`send`
 * fallbacks, and the two pieces of state more than one of them reads.
 *
 * Load order inside the family is deliberate: this file FIRST (it creates the namespace), then the one-subject
 * modules, then `app-composer.js` LAST — the composer builds itself eagerly at the bottom of that file, so
 * everything `ensureBuilt` touches has to exist by the time it runs.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  // ---- small helpers (fall back if core's h/escape arrive late) -------------
  // `children` are read off `arguments` below (variadic), not from a named parameter.
  function h(tag, props) {
    if (CC && typeof CC.h === 'function') {
      var args = [tag, props || null];
      if (arguments.length > 2) {
        for (var i = 2; i < arguments.length; i++) args.push(arguments[i]);
      }
      return CC.h.apply(CC, args);
    }
    // minimal local fallback
    var el = document.createElement(tag);
    props = props || {};
    if (props.class) el.className = props.class;
    if (props.text != null) el.textContent = props.text;
    if (props.html != null) el.innerHTML = props.html;
    if (props.title != null) el.title = props.title;
    if (props.attrs)
      for (var k in props.attrs)
        if (Object.prototype.hasOwnProperty.call(props.attrs, k)) el.setAttribute(k, props.attrs[k]);
    if (props.on)
      for (var ev in props.on)
        if (Object.prototype.hasOwnProperty.call(props.on, ev)) el.addEventListener(ev, props.on[ev]);
    var rest = Array.prototype.slice.call(arguments, 2);
    for (var j = 0; j < rest.length; j++) {
      var c = rest[j];
      if (c == null) continue;
      if (Array.isArray(c)) {
        for (var m = 0; m < c.length; m++)
          if (c[m] != null) el.appendChild(typeof c[m] === 'string' ? document.createTextNode(c[m]) : c[m]);
      } else el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    }
    return el;
  }
  function send(obj) {
    if (CC && typeof CC.send === 'function') CC.send(obj);
  }
  CX.h = h;
  CX.send = send;

  // ---- shared state, one writer each ---------------------------------------
  /** The built composer's nodes — { card, input, send, pills, queue, ghost, readout, … }. Set by ensureBuilt. */
  CX.els = null;
  /** The last cc.state payload. Set by cc.state; read by the pill menus and the send/Escape handling. */
  CX.lastState = null;
  /** From cc.meta: native-Wayland toolkit → route paste through the host (wl-paste). Read by every field. */
  CX.hostClipboard = false;
})();
