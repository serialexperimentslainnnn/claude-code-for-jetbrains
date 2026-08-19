/* app-permissions.js — the permission cards.
 * Renders cc.permissions(list) into CC.els.permissions per §PERMISSIONS.
 * Consumes app-core.js globals (window.CC: h, escape, markdown, send).
 * Vanilla ES2019, addEventListener only, no external resources, themeable via classes only.
 */
(function () {
  'use strict';

  // Safe accessors — every method must be callable before core fully initializes.
  function core() {
    return window.CC || null;
  }
  function mount() {
    var c = core();
    return (c && c.els && c.els.permissions) || null;
  }
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

  /**
   * Sends a message ABOUT a card, tagged with whichever conversation the card belongs to.
   *
   * There are two now: the chat this browser was built for, and the one embedded in the Git view
   * (`app-session-gitchat.js`), which is a second `claude` process with permission requests of its own. Both
   * are drawn by the builders below — one renderer, so a card cannot look different depending on where it
   * appears — and `scope` is what tells the host which session to answer. It rides on the message rather than
   * on a second set of message types because the alternative, letting the host guess from the request id,
   * would resolve the wrong turn on any collision, silently and irreversibly.
   *
   * Absent scope means the panel's own session, so nothing about the ordinary path changes.
   */
  function sendFor(card, obj) {
    var scope = card && card.scope ? String(card.scope) : null;
    if (scope) obj.scope = scope;
    send(obj);
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

    var qBlocks = questions
      .map(function (q) {
        var qText = q && q.question != null ? String(q.question) : '';
        var multi = !!(q && q.multiSelect);
        var options = q && Array.isArray(q.options) ? q.options : [];
        if (!(qText in selections)) selections[qText] = [];

        var optionEls = options
          .map(function (opt) {
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
                },
              },
            };
            if (preview) props.title = preview;

            var el = h('button', props, children[0], children[1] || null);
            // tag for sync
            if (el) {
              el.__qText = qText;
              el.__label = label;
            }
            return el;
          })
          .filter(Boolean);

        var block = h(
          'div',
          { class: 'q-block' },
          q && q.header ? h('div', { class: 'q-header', text: String(q.header) }) : null,
          h('div', { class: 'q-question', text: qText }),
          h('div', { class: 'q-options' }, optionEls)
        );
        return block;
      })
      .filter(Boolean);

    var root = h(
      'div',
      { class: 'perm-card q-card' },
      h('div', { class: 'perm-head', text: card.title || card.headline || 'Question' }),
      h('div', { class: 'perm-body' }, qBlocks),
      h(
        'div',
        { class: 'perm-actions' },
        h('button', {
          class: 'btn primary',
          text: 'Submit',
          on: {
            click: function () {
              var answers = {};
              Object.keys(selections).forEach(function (qText) {
                answers[qText] = selections[qText].join(', ');
              });
              sendFor(card, { type: 'resolveQuestion', id: id, answers: answers });
            },
          },
        }),
        // Cancel = deny the AskUserQuestion tool (the model continues without an answer).
        h('button', {
          class: 'btn ghost',
          text: 'Cancel',
          on: {
            click: function () {
              sendFor(card, { type: 'resolvePermission', id: id, allow: false });
            },
          },
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
    var t = f && f.type != null ? String(f.type).toLowerCase() : 'string';
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
    var fields = Array.isArray(e.fields)
      ? e.fields.filter(function (f) {
          return f && f.name != null && String(f.name) !== '';
        })
      : [];
    var isForm = e.mode === 'form' || fields.length > 0;
    var isUrl = e.mode === 'url' && isHttpUrl(e.url);

    var bodyChildren = [];
    if (e.description) bodyChildren.push(h('div', { class: 'elicit-desc', text: String(e.description) }));

    // Safe URL link (http/https only), routed through Kotlin (never navigated).
    if (isUrl) {
      var url = String(e.url).trim();
      bodyChildren.push(
        h('a', {
          text: url,
          attrs: { href: '#' },
          on: {
            click: function (ev) {
              if (ev && ev.preventDefault) ev.preventDefault();
              send({ type: 'open', url: url });
            },
          },
        })
      );
    }

    // name -> { input, kind, required } for value collection + validation.
    var fieldMeta = {};
    var acceptBtn = null;

    if (isForm && fields.length) {
      var fieldEls = fields
        .map(function (f) {
          var name = String(f.name);
          var kind = fieldKind(f);
          var required = !!(f && f.required);
          var titleText = f && f.title != null && f.title !== '' ? String(f.title) : name;
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

          return h('label', null, h('span', { class: 'elicit-field-label', text: titleText }), input);
        })
        .filter(Boolean);
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
          content[name] = raw == null || String(raw).trim() === '' ? null : Number(raw);
        } else {
          content[name] = input.value != null ? String(input.value) : '';
        }
      });
      return content;
    }

    function resolve(action) {
      var msg = { type: 'resolveElicitation', id: id, action: action };
      if (action === 'accept') msg.content = collectContent();
      sendFor(card, msg);
    }

    var serverName = e.serverName != null ? String(e.serverName) : card.title || 'Server';
    var message = e.message != null ? String(e.message) : card.summary || '';

    acceptBtn = h('button', {
      class: 'btn primary',
      text: 'Accept',
      on: {
        click: function () {
          if (!acceptBtn.disabled) resolve('accept');
        },
      },
    });

    var root = h(
      'div',
      { class: 'perm-card elicit-card' },
      h('div', { class: 'perm-head', text: 'MCP request' }),
      serverName ? h('div', { class: 'elicit-server', text: serverName }) : null,
      h(
        'div',
        { class: 'perm-body' },
        message ? h('div', { class: 'elicit-msg', text: message }) : null,
        bodyChildren.length ? h('div', { class: 'elicit-extra' }, bodyChildren) : null
      ),
      h(
        'div',
        { class: 'perm-actions' },
        acceptBtn,
        h('button', {
          class: 'btn ghost',
          text: 'Decline',
          on: {
            click: function () {
              resolve('decline');
            },
          },
        }),
        h('button', {
          class: 'btn ghost',
          text: 'Cancel',
          on: {
            click: function () {
              resolve('cancel');
            },
          },
        })
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
    return h(
      'div',
      { class: 'perm-card plan-card' },
      h('div', { class: 'perm-head', text: card.title || card.headline || 'Plan' }),
      body,
      h(
        'div',
        { class: 'perm-actions' },
        h('button', {
          class: 'btn primary',
          text: 'Approve plan',
          on: {
            click: function () {
              sendFor(card, { type: 'resolvePermission', id: id, allow: true });
            },
          },
        }),
        h('button', {
          class: 'btn ghost',
          text: 'Keep planning',
          on: {
            click: function () {
              sendFor(card, { type: 'resolvePermission', id: id, allow: false });
            },
          },
        })
      )
    );
  }

  // Render a unified-diff string as a read-only, colour-coded block (red removed / green added / hunk headers).
  // Uses textContent per line (never innerHTML) so file contents can't inject markup. Bounded for very large diffs.
  function renderPermDiff(text) {
    var pre = h('pre', { class: 'perm-diff' });
    var code = h('code', {});
    var lines = String(text).split('\n');
    var MAX = 400;
    var n = Math.min(lines.length, MAX);
    for (var i = 0; i < n; i++) {
      var line = lines[i];
      var c0 = line.charAt(0);
      var cls = 'dl-ctx';
      if (line.indexOf('@@') === 0) cls = 'dl-hunk';
      else if (c0 === '+') cls = 'dl-add';
      else if (c0 === '-') cls = 'dl-del';
      code.appendChild(h('span', { class: 'diff-line ' + cls, text: line + '\n' }));
    }
    if (lines.length > MAX) {
      code.appendChild(
        h('span', {
          class: 'diff-line dl-ctx',
          text: '… (' + (lines.length - MAX) + ' more lines — use View diff)\n',
        })
      );
    }
    pre.appendChild(code);
    return pre;
  }

  /**
   * The banner on a card the security guard raised, naming the rule that matched.
   *
   * It can only appear for a rule the user switched OFF — an enforced rule is denied outright and never becomes
   * a card — so the action it offers is **re-enable**, never disable. That direction is the whole point: this
   * banner can only ever narrow the open surface, and there is no way to widen protection away from a card, in
   * the middle of a task, under the pressure of a stalled turn. Widening is a deliberate trip to Settings.
   *
   * The badge is TEXT. Colour and motion are not information: neither survives forced-colors mode, a screenshot,
   * or a user who cannot see the difference.
   */
  function buildGuardAlert(g) {
    var rule = g.rule != null ? String(g.rule) : '';
    var children = [
      h('span', { class: 'perm-guard-badge', text: 'Guard alert' }),
      h('span', {
        class: 'perm-guard-rule',
        text: String(g.label || rule) + (g.category ? ' — ' + String(g.category) : ''),
      }),
    ];
    if (g.reason) children.push(h('div', { class: 'perm-guard-reason', text: String(g.reason) }));
    if (rule) {
      children.push(
        h('button', {
          class: 'perm-guard-restore',
          text: 'Re-enable this rule',
          // `on: true` means ENFORCED — the inversion between the row and the stored disabled-set lives in
          // JcefSettingsMenu.applyRule and nowhere else, so this sends the row's meaning, not the field's.
          on: {
            click: function () {
              send({ type: 'settingsToggle', key: 'rule:' + rule, on: true });
            },
          },
        })
      );
    }
    return h('div', { class: 'perm-guard' }, children);
  }

  function buildPermCard(card) {
    var id = card.id;
    var tool = card.tool;

    var bodyChildren = [];
    // The guard alert goes FIRST, above the summary, because it changes what the card IS. An ordinary permission
    // card asks "may I"; this one says "a security rule you switched off just matched, and here is which".
    // It only ever appears for a DISABLED rule — an enforced one is denied outright and never reaches a card — so
    // its presence always means an open lock let something through, which must not be quiet.
    if (card.guard) bodyChildren.push(buildGuardAlert(card.guard));
    var summary = card.summary != null ? String(card.summary) : '';
    var description = card.description != null ? String(card.description) : '';
    if (summary) bodyChildren.push(h('div', { class: 'perm-summary', text: summary }));
    if (description && description !== summary) {
      var descEl = h('div', { class: 'perm-desc' });
      if (descEl) descEl.innerHTML = md(description);
      bodyChildren.push(descEl);
    }
    if (card.blockedPath)
      bodyChildren.push(
        h('div', { class: 'perm-blocked', text: 'Blocked path: ' + String(card.blockedPath) })
      );
    if (card.decisionReason)
      bodyChildren.push(h('div', { class: 'perm-reason', text: String(card.decisionReason) }));

    // Read-only unified diff for reviewable edits: shows exactly what changes (red removed / green added). No
    // per-line selection — the whole edit is accepted or rejected.
    if (card.diff != null && String(card.diff).length) {
      bodyChildren.push(renderPermDiff(String(card.diff)));
    }

    // Edits are ATOMIC: accept or reject the whole change. Per-hunk "apply this line, not that one" selection was
    // removed — picking a subset of a coherent edit produces broken code, and it rendered as a confusing checklist.
    // The full change is viewable via "View diff" (and the IDE auto-opens the diff tab when the card appears).
    var acceptBtn = h('button', {
      class: 'btn primary',
      text: 'Accept',
      on: {
        click: function () {
          sendFor(card, { type: 'resolvePermission', id: id, allow: true });
        },
      },
    });

    var actions = [
      acceptBtn,
      h('button', {
        class: 'btn danger',
        text: 'Reject',
        on: {
          click: function () {
            sendFor(card, { type: 'resolvePermission', id: id, allow: false });
          },
        },
      }),
    ];
    if (card.reviewable) {
      actions.push(
        h('button', {
          class: 'btn ghost',
          text: 'View diff',
          on: {
            click: function () {
              sendFor(card, { type: 'viewDiff', id: id });
            },
          },
        })
      );
    }
    // On a GUARD card the unit of the answer is the COMMAND, not the tool, and the label says so. "Always
    // allow" on a `terraform destroy` card must open that command and nothing else the rule stops — the tool
    // grain would be `Bash`, i.e. every command there is. It also lasts only while the rule stays open, so
    // re-enabling the rule takes the approval with it.
    if (card.guard) {
      actions.push(
        h('button', {
          class: 'btn ghost perm-always',
          text: 'Always allow this command',
          on: {
            click: function () {
              sendFor(card, { type: 'guardAllowAlways', id: id });
            },
          },
        })
      );
    } else if (tool) {
      actions.push(
        h('button', {
          class: 'btn ghost perm-always',
          text: 'Always allow',
          on: {
            click: function () {
              sendFor(card, { type: 'alwaysAllow', tool: tool, id: id });
            },
          },
        })
      );
    }

    return h(
      'div',
      { class: 'perm-card' },
      h('div', { class: 'perm-head', text: card.headline || card.title || tool || 'Permission' }),
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

  /**
   * Announce that Claude is blocked on the user (WCAG 2.2 AA — 4.1.3 Status Messages).
   *
   * This is the single most important announcement in the whole UI: a permission card appears in the dock
   * WITHOUT taking focus, so to a screen-reader user the turn simply stops with no explanation and no
   * indication that anything is waiting for them. Sighted users see a card slide in; everyone else got silence.
   *
   * Announces only the 0 -> n transition, and names the tool when there is exactly one card, since "Claude
   * needs your permission to run Bash" is actionable in a way that "Claude needs your response" is not.
   * Resolution is left silent on purpose — the user just acted, so they know.
   */
  // Counted PER REGION. There are two mounts now — the dock and the Git view's embedded chat — and a single
  // counter would make a card arriving in one of them cancel the announcement of a card arriving in the
  // other, which is exactly the case where the user is least likely to be looking at the right pane.
  var pendingCounts = new WeakMap();
  function announcePending(list, region) {
    var C = core();
    if (!C || typeof C.announce !== 'function') return;
    var count = list.length;
    var previous = pendingCounts.get(region) || 0;
    pendingCounts.set(region, count);
    if (count === 0 || count <= previous) return;
    if (count === 1) {
      var only = list[0] || {};
      var tool = only.tool ? String(only.tool) : '';
      if (only.questions) C.announce('Claude is asking you a question.');
      else if (only.isPlan) C.announce('Claude is proposing a plan for your approval.');
      else if (only.elicitation) C.announce('An MCP server is requesting input.');
      else
        C.announce(
          tool ? 'Claude needs your permission to use ' + tool + '.' : 'Claude needs your response.'
        );
      return;
    }
    C.announce(count + ' requests are waiting for your response.');
  }

  /**
   * Draws [list] into [into], or into the dock when no container is given.
   *
   * The container is a parameter because the Git view embeds a second conversation with permission requests
   * of its own (`app-session-gitchat.js`), and every turn in that chat runs with forced approval — a view
   * that could show the conversation but not its cards would be a view you cannot finish anything from. ONE
   * renderer with two mounts, never two renderers: the card is where the command is shown before it runs, so
   * a second implementation is a second place for that to go wrong.
   */
  function permissions(list, into) {
    var region = into || mount();
    if (!region) return;
    if (!list || !Array.isArray(list)) list = [];
    announcePending(list, region);

    // Reconcile by card id rather than wiping + rebuilding the whole region. A blunt innerHTML='' on every push
    // (the host re-pushes on ANY permission change — a second card arriving, one resolving) destroyed the
    // in-progress state of the OTHER cards: typed elicitation fields, AskUserQuestion selections, unticked hunk
    // checkboxes. Keeping the existing DOM node for an id already shown preserves all of that.
    var existing = {};
    var n = region.children.length;
    for (var i = 0; i < n; i++) {
      var node0 = region.children[i];
      var cid = node0.getAttribute ? node0.getAttribute('data-card-id') : null;
      if (cid != null) existing[cid] = node0;
    }

    var wanted = {};
    var ordered = [];
    for (var j = 0; j < list.length; j++) {
      var card = list[j];
      if (!card || card.id == null) continue;
      var key = String(card.id);
      wanted[key] = true;
      var node = existing[key];
      if (!node) {
        node = buildCard(card);
        if (node) node.setAttribute('data-card-id', key);
      }
      if (node) ordered.push(node);
    }

    // Drop cards that are no longer pending.
    for (var k = region.children.length - 1; k >= 0; k--) {
      var child = region.children[k];
      var ck = child.getAttribute ? child.getAttribute('data-card-id') : null;
      if (ck == null || !wanted[ck]) region.removeChild(child);
    }

    // Put each card in its place, and TOUCH NOTHING that is already in it.
    //
    // Reusing the node is not the same as leaving it alone: re-appending a node that is already there is not a
    // no-op, it is a removal followed by an insertion (both the browser and jsdom report the pair), and a
    // subtree that leaves the document comes back with its scroll offsets at zero and its focus gone. The
    // host re-pushes the whole list on every permission change — a second request arriving, another one
    // resolving — so the diff you were reading (`.perm-diff`, capped at 28vh, so anything real scrolls)
    // jumped back to its first line, and the elicitation field you were typing into lost the caret. Only the
    // cards whose position actually changed are moved; in the ordinary push, nothing moves at all.
    for (var m = 0; m < ordered.length; m++) {
      if (region.children[m] !== ordered[m]) region.insertBefore(ordered[m], region.children[m] || null);
    }
  }

  window.cc = window.cc || {};
  window.cc.permissions = permissions;
  // The renderer, for the Git view's embedded chat. Exposed rather than duplicated — see `permissions`.
  window.CC = window.CC || {};
  window.CC.permissions = { render: permissions };
})();
