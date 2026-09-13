(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const fmtInt = D.fmtInt;
  const fmtUsd = D.fmtUsd;
  const statRow = D.statRow;
  const card = D.card;

  interface CostSpec {
    input?: unknown;
    output?: unknown;
    cacheWrite?: unknown;
    cacheRead?: unknown;
    usd?: unknown;
  }

  interface AccountSpec {
    email?: unknown;
    org?: unknown;
    plan?: unknown;
    provider?: unknown;
    loggedIn?: boolean;
  }

  interface PlanSpec {
    body?: unknown;
    path?: unknown;
  }

  function buildCostCard(cost: unknown): HTMLElement | null {
    if (!cost || typeof cost !== 'object') return null;
    const c = cost as CostSpec;
    const rows = [
      statRow('Input', fmtInt(c.input)),
      statRow('Output', fmtInt(c.output)),
      statRow('Cache write', fmtInt(c.cacheWrite)),
      statRow('Cache read', fmtInt(c.cacheRead)),
      statRow('Cost', fmtUsd(c.usd)),
    ];
    return card('Usage & cost', rows);
  }

  function buildAccountCard(acct: unknown): HTMLElement | null {
    if (!acct || typeof acct !== 'object') return null;
    const a = acct as AccountSpec;
    const rows: (HTMLElement | null)[] = [
      statRow('Email', a.email),
      statRow('Organization', a.org),
      statRow('Plan', a.plan),
      statRow('Provider', a.provider),
    ];
    if (a.loggedIn === true || a.loggedIn === false) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn account-auth-btn';
      btn.textContent = a.loggedIn ? 'Log out' : 'Sign in';
      btn.addEventListener('click', function () {
        CC.send({ type: a.loggedIn ? 'logout' : 'loginSubscription' });
      });
      const row = document.createElement('div');
      row.className = 'account-auth-row';
      row.appendChild(btn);
      rows.push(row);
    }
    return card('Account', rows);
  }

  function buildEnvCard(payload: SessionPayload): HTMLElement | null {
    const rows = [
      statRow('Model', payload.model),
      statRow('Working dir', payload.cwd),
      statRow('Version', payload.version),
    ];
    return card('Session', rows);
  }

  function buildPlanCard(plan: unknown): HTMLElement | null {
    const p = plan as PlanSpec | null;
    if (!p || typeof p !== 'object' || !p.body) return null;
    const body = document.createElement('div');
    body.className = 'plan-md';
    body.innerHTML = CC.markdown(String(p.body));
    const parts: HTMLElement[] = [body];
    if (p.path) {
      const where = document.createElement('div');
      where.className = 'plan-path';
      where.textContent = String(p.path);
      parts.push(where);
    }
    return card('Plan', parts, true, 'plan');
  }

  D.buildPlanCard = buildPlanCard;
  D.buildCostCard = buildCostCard;
  D.buildAccountCard = buildAccountCard;
  D.buildEnvCard = buildEnvCard;
})();
