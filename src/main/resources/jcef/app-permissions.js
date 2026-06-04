
/* app-permissions.js — A4 (permissions)
 * Renders cc.permissions(list) into CC.els.permissions per §PERMISSIONS.
 * Consumes app-core.js globals (window.CC: h, escape, markdown, send).
 * Vanilla ES2019, addEventListener only, no external resources, themeable via classes only.
 */
(function () {
  'use strict';

  // Safe accessors — every method must be callable before core fully initializes.
  function core() { return window.CC || null; }
  function mount() { var c = core(); return (c && c.els && c.els.permissions) || null; }
  function h() {
    var c = core();
    if (c && typeof c.h === 'function') return c.h.apply(c, arguments);
    return null;
  }
  function esc(s) {
    var c = core();
    if (c && typeof c.escape === 'function') return c.escape(s == null ? '' : String(s));
    return s == null ? '' : String(s);
  }
  function md(s) {
    var c = core();
    if (c && typeof c.markdown === 'function') return c.markdown(s == null ? '' : String(s));
    return esc(s);
  }
  function send(obj) {
    var c = core();
    if (c && typeof c.send === 'function') c.send(obj);
  }

  function isHttpUrl(u) {
    if (!u || typeof u !== 'string') return false;
    // Anchor at start; only http/https schemes are permitted.
    return /^https?:\/\//i.test(u.trim());
  }

  // ---- card builders ---------------------------------------------------------

  function buildQuestionCard(card) {
    var id = card.id;
    var questions = Array.isArray(card.questions) ? card.questions : [];
    // selections[questionText] = [labels...]
    var selections = {};

    var qBlocks = questions.map(function (q) {
      var qText = q && q.question != null ? String(q.question) : '';
      var multi = !!(q && q.multiSelect);
      var options = (q && Array.isArray(q.options)) ? q.options : [];
      if (!(qText in selections)) selections[qText] = [];

      var optionEls = options.map(function (opt) {
        var label = opt && opt.label != null ? String(opt.label) : '';
        var desc = opt && opt.description != null ? String(opt.description) : '';
        var preview = opt && opt.preview != null ? String(opt.preview) : '';

        var children = [h('span', { class: 'q-option-label', text: label })];
        if (desc) children.push(h('div', { class: 'q-desc', text: desc }));

        var props = {
          class: 'q-option',
          on: {
            click: function () {
              var arr = selections[qText];
              if (multi) {
                var i = arr.indexOf(label);
                if (i >= 0) arr.splice(i, 1);
                else arr.push(label);
              } else {
                selections[qText] = [label];
              }
              // re-paint selected state within this question block
              syncSelected();
            }
          }
        };
        if (preview) props.title = preview;

        var el = h('button', props, children[0], children[1] || null);
        // tag for sync
        if (el) {
          el.__qText = qText;
          el.__label = label;
        }
        return el;
      }).filter(Boolean);

      var block = h('div', { class: 'q-block' },
        q && q.header ? h('div', { class: 'q-header', text: String(q.header) }) : null,
        h('div', { class: 'q-question', text: qText }),
        h('div', { class: 'q-options' }, optionEls)
      );
      return block;
    }).filter(Boolean);

    var root = h('div', { class: 'perm-card q-card' },
      h('div', { class: 'perm-head', text: card.title || card.headline || 'Question' }),
      h('div', { class: 'perm-body' }, qBlocks),
      h('div', { class: 'perm-actions' },
        h('button', {
          class: 'btn primary',
          text: 'Submit',
          on: {
            click: function () {
              var answers = {};
              Object.keys(selections).forEach(function (qText) {
                answers[qText] = selections[qText].join(', ');
              });
              send({ type: 'resolveQuestion', id: id, answers: answers });
            }
          }
        }),
        // Cancel = deny the AskUserQuestion tool (the model continues without an answer).
        h('button', {
          class: 'btn ghost',
          text: 'Cancel',
          on: { click: function () { send({ type: 'resolvePermission', id: id, allow: false }); } }
        })
      )
    );

    // Reflect current selection state onto option buttons.
    function syncSelected() {
      if (!root) return;
      var opts = root.querySelectorAll('.q-option');
      for (var i = 0; i < opts.length; i++) {
        var el = opts[i];
        var arr = selections[el.__qText] || [];
        if (arr.indexOf(el.__label) >= 0) el.classList.add('selected');
        else el.classList.remove('selected');
      }
    }
    syncSelected();
    return root;
  }

  // Normalize a field's declared type to one of: string | number | integer | boolean.
  function fieldKind(f) {
    var t = (f && f.type != null) ? String(f.type).toLowerCase() : 'string';
    if (t === 'number') return 'number';
    if (t === 'integer' || t === 'int') return 'integer';
    if (t === 'boolean' || t === 'bool') return 'boolean';
    return 'string';
  }

  function buildElicitCard(card) {
    var id = card.id;
    var e = card.elicitation || {};

    // A field is { name, title?, type?, required? }.  Render form when the
    // elicitation explicitly asks for a form OR when fields are supplied.
    var fields = Array.isArray(e.fields) ? e.fields.filter(function (f) {
      return f && f.name != null && String(f.name) !== '';
    }) : [];
    var isForm = e.mode === 'form' || fields.length > 0;
    var isUrl = e.mode === 'url' && isHttpUrl(e.url);

    var bodyChildren = [];
    if (e.description) bodyChildren.push(h('div', { class: 'elicit-desc', text: String(e.description) }));

    // Safe URL link (http/https only), routed through Kotlin (never navigated).
    if (isUrl) {
      var url = String(e.url).trim();
      bodyChildren.push(h('a', {
        text: url,
        attrs: { href: '#' },
        on: {
          click: function (ev) {
            if (ev && ev.preventDefault) ev.preventDefault();
            send({ type: 'open', url: url });
          }
        }
      }));
    }

    // name -> { input, kind, required } for value collection + validation.
    var fieldMeta = {};
    var acceptBtn = null;

    if (isForm && fields.length) {
      var fieldEls = fields.map(function (f) {
        var name = String(f.name);
        var kind = fieldKind(f);
        var required = !!(f && f.required);
        var titleText = (f && f.title != null && f.title !== '') ? String(f.title) : name;
        if (required) titleText += ' *';

        var inputType = 'text';
        if (kind === 'number' || kind === 'integer') inputType = 'number';
        else if (kind === 'boolean') inputType = 'checkbox';

        var input = h('input', { attrs: { type: inputType, name: name } });
        if (input) {
          input.addEventListener('input', refreshAcceptState);
          input.addEventListener('change', refreshAcceptState);
          fieldMeta[name] = { input: input, kind: kind, required: required };
        }

        return h('label', null,
          h('span', { class: 'elicit-field-label', text: titleText }),
          input
        );
      }).filter(Boolean);
      bodyChildren.push(h('div', { class: 'elicit-fields' }, fieldEls));
    }

    // Required fields must be satisfied before Accept is enabled (form only).
    function requiredSatisfied() {
      var names = Object.keys(fieldMeta);
      for (var i = 0; i < names.length; i++) {
        var meta = fieldMeta[names[i]];
        if (!meta || !meta.required) continue;
        var input = meta.input;
        if (meta.kind === 'boolean') {
          // A required checkbox must be checked.
          if (!input.checked) return false;
        } else if (String(input.value == null ? '' : input.value).trim() === '') {
          return false;
        }
      }
      return true;
    }

    function refreshAcceptState() {
      if (!acceptBtn) return;
      var ok = !isForm || requiredSatisfied();
      acceptBtn.disabled = !ok;
      if (ok) acceptBtn.removeAttribute('disabled');
      else acceptBtn.setAttribute('disabled', '');
    }

    function collectContent() {
      var content = {};
      Object.keys(fieldMeta).forEach(function (name) {
        var meta = fieldMeta[name];
        if (!meta) return;
        var input = meta.input;
        if (meta.kind === 'boolean') {
          content[name] = !!input.checked;
        } else if (meta.kind === 'number' || meta.kind === 'integer') {
          var raw = input.value;
          content[name] = (raw == null || String(raw).trim() === '') ? null : Number(raw);
        } else {
          content[name] = input.value != null ? String(input.value) : '';
        }
      });
      return content;
    }

    function resolve(action) {
      var msg = { type: 'resolveElicitation', id: id, action: action };
      if (action === 'accept') msg.content = collectContent();
      send(msg);
    }

    var serverName = e.serverName != null ? String(e.serverName) : (card.title || 'Server');
    var message = e.message != null ? String(e.message) : (card.summary || '');

    acceptBtn = h('button', {
      class: 'btn primary',
      text: 'Accept',
      on: { click: function () { if (!acceptBtn.disabled) resolve('accept'); } }
    });

    var root = h('div', { class: 'perm-card elicit-card' },
      h('div', { class: 'perm-head', text: 'MCP request' }),
      serverName ? h('div', { class: 'elicit-server', text: serverName }) : null,
      h('div', { class: 'perm-body' },
        message ? h('div', { class: 'elicit-msg', text: message }) : null,
        bodyChildren.length ? h('div', { class: 'elicit-extra' }, bodyChildren) : null
      ),
      h('div', { class: 'perm-actions' },
        acceptBtn,
        h('button', { class: 'btn ghost', text: 'Decline', on: { click: function () { resolve('decline'); } } }),
        h('button', { class: 'btn ghost', text: 'Cancel', on: { click: function () { resolve('cancel'); } } })
      )
    );

    refreshAcceptState();
    return root;
  }

  function buildPlanCard(card) {
    var id = card.id;
    var body = h('div', { class: 'perm-body' });
    if (body) {
      var planHtml = md(card.planText || '');
      body.innerHTML = planHtml;
    }
    return h('div', { class: 'perm-card plan-card' },
      h('div', { class: 'perm-head', text: card.title || card.headline || 'Plan' }),
      body,
      h('div', { class: 'perm-actions' },
        h('button', {
          class: 'btn primary',
          text: 'Approve plan',
          on: { click: function () { send({ type: 'resolvePermission', id: id, allow: true }); } }
        }),
        h('button', {
          class: 'btn ghost',
          text: 'Keep planning',
          on: { click: function () { send({ type: 'resolvePermission', id: id, allow: false }); } }
        })
      )
    );
  }

  function buildPermCard(card) {
    var id = card.id;
    var tool = card.tool;

    var bodyChildren = [];
    var summary = card.summary != null ? String(card.summary) : '';
    var description = card.description != null ? String(card.description) : '';
    if (summary) bodyChildren.push(h('div', { class: 'perm-summary', text: summary }));
    if (description && description !== summary) {
      var descEl = h('div', { class: 'perm-desc' });
      if (descEl) descEl.innerHTML = md(description);
      bodyChildren.push(descEl);
    }
    if (card.blockedPath) bodyChildren.push(h('div', { class: 'perm-blocked', text: 'Blocked path: ' + String(card.blockedPath) }));
    if (card.decisionReason) bodyChildren.push(h('div', { class: 'perm-reason', text: String(card.decisionReason) }));

    var actions = [
      h('button', {
        class: 'btn primary',
        text: 'Accept',
        on: { click: function () { send({ type: 'resolvePermission', id: id, allow: true }); } }
      }),
      h('button', {
        class: 'btn danger',
        text: 'Reject',
        on: { click: function () { send({ type: 'resolvePermission', id: id, allow: false }); } }
      })
    ];
    if (card.reviewable) {
      actions.push(h('button', {
        class: 'btn ghost',
        text: 'View diff',
        on: { click: function () { send({ type: 'viewDiff', id: id }); } }
      }));
    }
    if (tool) {
      actions.push(h('button', {
        class: 'btn ghost perm-always',
        text: 'Always allow',
        on: { click: function () { send({ type: 'alwaysAllow', tool: tool }); } }
      }));
    }

    return h('div', { class: 'perm-card' },
      h('div', { class: 'perm-head', text: card.headline || card.title || (tool || 'Permission') }),
      h('div', { class: 'perm-body' }, bodyChildren),
      h('div', { class: 'perm-actions' }, actions)
    );
  }

  function buildCard(card) {
    if (!card || typeof card !== 'object') return null;
    // First match wins.
    if (Array.isArray(card.questions) && card.questions.length) return buildQuestionCard(card);
    if (card.elicitation) return buildElicitCard(card);
    if (card.isPlan) return buildPlanCard(card);
    return buildPermCard(card);
  }

  // ---- public API ------------------------------------------------------------

  function permissions(list) {
    var region = mount();
    if (!region) return;
    // Re-render on each call: clear then rebuild (simple + correct).
    region.innerHTML = '';
    if (!list || !Array.isArray(list) || list.length === 0) return;
    for (var i = 0; i < list.length; i++) {
      var el = buildCard(list[i]);
      if (el) region.appendChild(el);
    }
  }

  window.cc = window.cc || {};
  window.cc.permissions = permissions;
})();
