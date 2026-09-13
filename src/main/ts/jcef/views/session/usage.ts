(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const h = D.h;
  const num = D.num;
  const fmtInt = D.fmtInt;
  const card = D.card;

  interface UsageWindowSpec {
    label?: unknown;
    pct?: unknown;
    resetsAt?: string | null;
    exhausted?: boolean;
  }

  interface ExtraCredit {
    enabled?: boolean;
    spent?: unknown;
    currency?: unknown;
    limitReached?: boolean;
  }

  interface UsageSpec {
    windows?: (UsageWindowSpec | null)[];
    extra?: ExtraCredit | null;
    plan?: unknown;
  }

  interface ContextCategory {
    name?: unknown;
    tokens?: unknown;
  }

  interface ContextSpec {
    categories?: (ContextCategory | null)[];
    used?: unknown;
    max?: unknown;
    pct?: unknown;
  }

  function buildUsageCard(usage: unknown): HTMLElement | null {
    if (!usage || typeof usage !== 'object') return null;
    const u = usage as UsageSpec;
    const windows = Array.isArray(u.windows) ? u.windows : [];
    if (!windows.length && !u.extra) return null;

    const rows: (HTMLElement | null)[] = [];
    for (let i = 0; i < windows.length; i++) {
      const w = windows[i] || {};
      rows.push(usageBar(w.label, num(w.pct), w.resetsAt, !!w.exhausted));
    }
    if (u.extra && u.extra.enabled) {
      rows.push(extraCreditRow(u.extra));
    }
    const title = u.plan ? 'Plan limits · ' + String(u.plan) : 'Plan limits';
    return card(title, rows, true);
  }

  function usageBar(
    label: unknown,
    pct: number | null,
    resetsAt: string | null | undefined,
    exhausted: boolean
  ) {
    const known = pct != null;
    const level = exhausted ? 'lvl-high' : known ? usageLevel(pct) : 'lvl-low';
    const fill = h('div', {
      class: 'usage-fill ' + level,
      style: { width: (known ? pct.toFixed(1) : 0) + '%' },
    });
    const reset = CC.resetIn(resetsAt);
    return h(
      'div',
      { class: 'usage-row' },
      h(
        'div',
        { class: 'usage-head' },
        h('span', { class: 'usage-label', text: label == null ? '' : String(label) }),
        h('span', { class: 'usage-pct', text: known ? pct.toFixed(1) + '% used' : '—' })
      ),
      h('div', { class: 'usage-track' }, fill),
      reset ? h('div', { class: 'usage-reset', text: reset }) : null
    );
  }

  function extraCreditRow(extra: ExtraCredit): HTMLElement {
    const spent = num(extra.spent);
    const text =
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

  function usageLevel(pct: number): string {
    if (pct >= 85) return 'lvl-high';
    if (pct >= 65) return 'lvl-mid';
    return 'lvl-low';
  }

  function buildContextCard(ctx: unknown): HTMLElement | null {
    if (!ctx || typeof ctx !== 'object') return null;
    const c = ctx as ContextSpec;
    const cats = Array.isArray(c.categories) ? c.categories : [];
    const used = num(c.used);
    const max = num(c.max);
    const pct = num(c.pct);

    if (!cats.length && used == null && max == null) return null;

    let total = 0;
    let i: number;
    for (i = 0; i < cats.length; i++) {
      const cat = cats[i];
      const t = num(cat && cat.tokens);
      if (t != null && t > 0) total += t;
    }

    const children: HTMLElement[] = [];

    const headlineBits: string[] = [];
    if (used != null || max != null) {
      const u = fmtInt(used);
      const m = fmtInt(max);
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

    if (cats.length && total > 0) {
      const segs: HTMLElement[] = [];
      const legendItems: HTMLElement[] = [];
      for (i = 0; i < cats.length; i++) {
        const cat = cats[i] || {};
        const name = cat.name != null ? String(cat.name) : '';
        const tok = num(cat.tokens);
        if (tok == null || tok <= 0) continue;
        const widthPct = (tok / total) * 100;
        const idx = String((i % 8) + 1);
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

  D.buildUsageCard = buildUsageCard;
  D.buildContextCard = buildContextCard;
})();
