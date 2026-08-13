/* app-session-cards.js — the Session view's own cards.
 *
 * One subject: what this session costs and what is left of it — the plan-limit bars, the context breakdown,
 * usage & cost, the account, and the plain session facts (model, working dir, version). Every one of them is a
 * pure builder over its slice of the `cc.session` payload: given the data it returns a card, given nothing it
 * returns null and the card simply does not appear.
 *
 * MCP servers and the Workloads diagram are their own files; the shell (`app-session.js`) decides which view
 * shows what.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var num = D.num;
  var fmtInt = D.fmtInt;
  var fmtUsd = D.fmtUsd;
  var statRow = D.statRow;
  var card = D.card;

  /**
   * Plan limits: one labelled bar per window (current session, all models, per model), plus the extra-credit
   * balance. Sourced from the binary's `get_usage`, which returns every window at once.
   *
   * A window whose percentage is unknown renders its bar EMPTY and its value as "—", never as 0%. The binary
   * reports `utilization` only when the API returned it, and "we do not know" is a different statement from
   * "you have used none" — a bar cannot say both, so it says neither and the text carries the distinction.
   */
  function buildUsageCard(usage) {
    if (!usage || typeof usage !== 'object') return null;
    var windows = Array.isArray(usage.windows) ? usage.windows : [];
    if (!windows.length && !usage.extra) return null;

    var rows = [];
    for (var i = 0; i < windows.length; i++) {
      var w = windows[i] || {};
      rows.push(usageBar(w.label, num(w.pct), w.resetsAt, w.exhausted));
    }
    if (usage.extra && usage.extra.enabled) {
      rows.push(extraCreditRow(usage.extra));
    }
    var title = usage.plan ? 'Plan limits · ' + String(usage.plan) : 'Plan limits';
    return card(title, rows, true);
  }

  /** One window: label, a proportional bar, the percentage, and when it resets. */
  function usageBar(label, pct, resetsAt, exhausted) {
    var known = pct != null;
    // Same blue/amber/red scale as the composer's dot — see usageLevel there. `exhausted` (the binary told us
    // the window is spent) always wins over the percentage, which may be stale or absent.
    var level = exhausted ? 'lvl-high' : known ? usageLevel(pct) : 'lvl-low';
    var fill = h('div', {
      class: 'usage-fill ' + level,
      style: { width: (known ? pct.toFixed(1) : 0) + '%' },
    });
    var reset = resetIn(resetsAt);
    return h(
      'div',
      { class: 'usage-row' },
      h(
        'div',
        { class: 'usage-head' },
        h('span', { class: 'usage-label', text: label == null ? '' : String(label) }),
        // toFixed(1): one decimal, and it also kills the IEEE-754 tail (0.28*100 = 28.000000000000004).
        h('span', { class: 'usage-pct', text: known ? pct.toFixed(1) + '% used' : '—' })
      ),
      h('div', { class: 'usage-track' }, fill),
      reset ? h('div', { class: 'usage-reset', text: reset }) : null
    );
  }

  /** Pay-as-you-go balance, shown only once the user has actually enabled extra credits. */
  function extraCreditRow(extra) {
    var spent = num(extra.spent);
    var text =
      spent == null
        ? 'enabled'
        : spent.toFixed(2) + (extra.currency ? ' ' + String(extra.currency) : '') + ' used';
    return h(
      'div',
      { class: 'usage-row' },
      h(
        'div',
        { class: 'usage-head' },
        h('span', { class: 'usage-label', text: 'Extra credits' }),
        h('span', {
          class: 'usage-pct' + (extra.limitReached ? ' exhausted' : ''),
          text: extra.limitReached ? 'limit reached' : text,
        })
      )
    );
  }

  /** Quota severity. Kept identical to app-composer.js's usageLevel; the class names are the shared contract. */
  function usageLevel(pct) {
    if (pct >= 85) return 'lvl-high';
    if (pct >= 65) return 'lvl-mid';
    return 'lvl-low';
  }

  /** "Resets in 4h 50m" — one implementation, in app-core, shared with the composer's bar row. */
  function resetIn(iso) {
    return CC.resetIn(iso);
  }

  function buildContextCard(ctx) {
    if (!ctx || typeof ctx !== 'object') return null;
    var cats = Array.isArray(ctx.categories) ? ctx.categories : [];
    var used = num(ctx.used);
    var max = num(ctx.max);
    var pct = num(ctx.pct);

    if (!cats.length && used == null && max == null) return null;

    // Total tokens across categories, to compute proportional widths.
    var total = 0;
    var i;
    for (i = 0; i < cats.length; i++) {
      var t = num(cats[i] && cats[i].tokens);
      if (t != null && t > 0) total += t;
    }

    var children = [];

    // Headline: used/max · pct%
    var headlineBits = [];
    if (used != null || max != null) {
      var u = fmtInt(used);
      var m = fmtInt(max);
      headlineBits.push((u != null ? u : '?') + ' / ' + (m != null ? m : '?'));
    }
    if (pct != null) headlineBits.push(Math.round(pct) + '%');
    if (headlineBits.length) {
      children.push(
        h(
          'div',
          { class: 'stat-row' },
          h('span', { class: 'stat-label', text: 'Context' }),
          h('span', { class: 'stat-value', text: headlineBits.join(' · ') })
        )
      );
    }

    // Segmented bar. A simple, deterministic palette index for segment legend swatches: we do not hardcode
    // colors here — the CSS owns them via .seg:nth-of-type / data attrs.
    if (cats.length && total > 0) {
      var segs = [];
      var legendItems = [];
      for (i = 0; i < cats.length; i++) {
        var cat = cats[i] || {};
        var name = cat.name != null ? String(cat.name) : '';
        var tok = num(cat.tokens);
        if (tok == null || tok <= 0) continue;
        var widthPct = (tok / total) * 100;
        var idx = String((i % 8) + 1); // CSS may key swatch color off data-seg
        segs.push(
          h('div', {
            class: 'seg',
            dataset: { seg: idx },
            style: { width: widthPct.toFixed(3) + '%' },
            title: name + ' · ' + (fmtInt(tok) || tok),
          })
        );
        legendItems.push(
          h(
            'span',
            { class: 'legend-item' },
            h('span', { class: 'legend-swatch', dataset: { seg: idx } }),
            h('span', { class: 'legend-name', text: name }),
            h('span', { class: 'legend-tokens', text: fmtInt(tok) || String(tok) })
          )
        );
      }
      if (segs.length) {
        children.push(h('div', { class: 'seg-bar' }, segs));
        children.push(h('div', { class: 'legend' }, legendItems));
      }
    }

    return card('Context', children, true);
  }

  function buildCostCard(cost) {
    if (!cost || typeof cost !== 'object') return null;
    var rows = [
      statRow('Input', fmtInt(cost.input)),
      statRow('Output', fmtInt(cost.output)),
      statRow('Cache write', fmtInt(cost.cacheWrite)),
      statRow('Cache read', fmtInt(cost.cacheRead)),
      statRow('Cost', fmtUsd(cost.usd)),
    ];
    return card('Usage & cost', rows);
  }

  function buildAccountCard(acct) {
    if (!acct || typeof acct !== 'object') return null;
    var rows = [
      statRow('Email', acct.email),
      statRow('Organization', acct.org),
      statRow('Plan', acct.plan),
      statRow('Provider', acct.provider),
    ];
    // Sign in / Log out, from the VERIFIED auth state only (`loggedIn` comes from the host's
    // `auth status` probe). When it is unknown the row is omitted — a button must not claim a state
    // nobody checked. Signing in is a button here, not a slash command to know about.
    if (acct.loggedIn === true || acct.loggedIn === false) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn account-auth-btn';
      btn.textContent = acct.loggedIn ? 'Log out' : 'Sign in';
      btn.addEventListener('click', function () {
        CC.send({ type: acct.loggedIn ? 'logout' : 'loginSubscription' });
      });
      var row = document.createElement('div');
      row.className = 'account-auth-row';
      row.appendChild(btn);
      rows.push(row);
    }
    return card('Account', rows);
  }

  function buildEnvCard(payload) {
    var rows = [
      statRow('Model', payload.model),
      statRow('Working dir', payload.cwd),
      statRow('Version', payload.version),
    ];
    return card('Session', rows);
  }

  /**
   * The plan-mode plan, as the binary itself holds it (`get_plan`).
   *
   * Rendered as MARKDOWN through the same sanitizing path the transcript uses. A plan is written by the
   * model, so it is exactly as untrusted as anything else the model emits — never `innerHTML` of raw text.
   *
   * Wide, because a plan is prose and a numbered list, not a stat row: at column width it would wrap into an
   * unreadable ribbon. Absent when there is no plan, so the card omits itself rather than heading an
   * empty panel — `get_plan` is read-only and never creates one, so "no plan" is an ordinary answer.
   */
  function buildPlanCard(plan) {
    if (!plan || typeof plan !== 'object' || !plan.body) return null;
    var body = document.createElement('div');
    body.className = 'plan-md';
    body.innerHTML = CC.markdown(String(plan.body));
    var parts = [body];
    if (plan.path) {
      var where = document.createElement('div');
      where.className = 'plan-path';
      where.textContent = String(plan.path);
      parts.push(where);
    }
    return card('Plan', parts, true, 'plan');
  }

  D.buildPlanCard = buildPlanCard;
  D.buildUsageCard = buildUsageCard;
  D.buildContextCard = buildContextCard;
  D.buildCostCard = buildCostCard;
  D.buildAccountCard = buildAccountCard;
  D.buildEnvCard = buildEnvCard;
})();
