const { loadFrontend } = require('./helpers/load');

const ENDPOINT = 'https://api.osv.dev/v1/querybatch';
const OPERATOR = 'OSV.dev, run by the Open Source Security Foundation';

function vuln(over) {
  return Object.assign(
    {
      available: true,
      state: 'unconsented',
      status: 'stopped',
      consent: 'unasked',
      endpoint: ENDPOINT,
      operator: OPERATOR,
      disclosure: {
        sent: ['The name of every dependency this project resolves.'],
        caveats: ['It travels from your IP address.'],
      },
      inventory: { components: 412, manifests: ['package-lock.json'], ecosystems: ['npm'] },
      progress: { done: 0, total: 0 },
      reason: null,
      note: null,
      report: null,
    },
    over || {}
  );
}

function finding(over) {
  return Object.assign(
    {
      id: 'GHSA-1234-abcd-5678',
      tier: 'high',
      tierLabel: 'High',
      malicious: false,
      name: 'left-pad',
      version: '1.3.0',
      ecosystem: 'npm',
      origin: 'direct',
      originLabel: 'direct dependency',
      manifest: 'package-lock.json',
      summary: 'A padding flaw.',
      details: null,
      cvss: 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N',
      cvssType: 'CVSS_V3',
      published: null,
      fixed: ['1.3.1'],
      aliases: [],
      references: [],
    },
    over || {}
  );
}

function report(findings, over) {
  return Object.assign(
    {
      asOfMillis: Date.now() - 3600 * 1000,
      ageMillis: 3600000,
      endpoint: ENDPOINT,
      queried: 412,
      total: findings.length,
      shown: findings.length,
      counts: [{ tier: 'high', label: 'High', count: findings.length }],
      findings: findings,
    },
    over || {}
  );
}

describe('the Vulnerabilities view', () => {
  let win;
  let sent;

  const panel = () => win.document.querySelector('.dashboard');
  const securityBtn = () => win.document.querySelector('.dash-toggle[data-view="security"]');
  const openSecurity = () => securityBtn().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  const press = (label) =>
    Array.from(panel().querySelectorAll('button')).filter((b) => b.textContent === label)[0];
  const show = (payload) => {
    win.cc.session({ vuln: payload });
    if (!panel() || panel().hasAttribute('hidden')) openSecurity();
  };

  beforeEach(() => {
    win = loadFrontend(['app-session.js', 'app-composer.js'], { vendor: false });
    sent = [];
    win.__ccSend = (json) => sent.push(JSON.parse(json));
  });

  describe('the consent gate', () => {
    it('sends nothing at all just because the view was opened', () => {
      show(vuln());

      expect(sent).toEqual([]);
    });

    it('names who would receive the list, where, and how big it is', () => {
      show(vuln());
      const words = panel().textContent;

      expect(words).toContain(OPERATOR);
      expect(words).toContain(ENDPOINT);
      expect(words).toContain('412');
      expect(words).toContain('The name of every dependency this project resolves.');
      expect(words).toContain('It travels from your IP address.');
    });

    it('offers no Scan button before consent — the only way through records it', () => {
      show(vuln());

      expect(press('Scan now')).toBeUndefined();
      expect(press('Allow and scan now')).toBeTruthy();
    });

    it('records the consent BEFORE it asks for a scan', () => {
      show(vuln());
      press('Allow and scan now').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

      expect(sent).toEqual([{ type: 'vulnConsent', granted: true }, { type: 'vulnScan' }]);
    });

    it('a withdrawn consent goes back to the gate and says the last result was dropped', () => {
      show(vuln({ state: 'withdrawn', consent: 'withdrawn' }));

      expect(panel().textContent).toContain('withdrew consent');
      expect(press('Scan now')).toBeUndefined();
      expect(press('Scan again')).toBeUndefined();
    });
  });

  describe('the literal list', () => {
    it('is asked for on demand, never pushed with the view', () => {
      show(vuln());
      press('Show the exact list that would be sent (412)').dispatchEvent(
        new win.MouseEvent('click', { bubbles: true })
      );

      expect(sent).toEqual([{ type: 'vulnInventory' }]);
      expect(panel().querySelectorAll('.vuln-inv-row').length).toBe(0);
    });

    it('renders exactly what the host answered with, and says where it would go', () => {
      show(vuln());
      win.cc.vulnInventory({
        endpoint: ENDPOINT,
        operator: OPERATOR,
        total: 2,
        truncated: false,
        components: [
          {
            ecosystem: 'npm',
            name: 'left-pad',
            version: '1.3.0',
            origin: 'direct',
            originLabel: 'direct dependency',
          },
          {
            ecosystem: 'Go',
            name: 'golang.org/x/sys',
            version: 'v0.25.0',
            origin: 'unknown',
            originLabel: 'origin not recorded by this manifest',
          },
        ],
      });

      const rows = Array.from(panel().querySelectorAll('.vuln-inv-row')).map((r) => r.textContent);
      expect(rows.length).toBe(2);
      expect(rows[0]).toContain('left-pad');
      expect(rows[0]).toContain('1.3.0');
      expect(rows[1]).toContain('golang.org/x/sys');
      expect(rows[1]).toContain('origin not recorded by this manifest');
      expect(panel().textContent).toContain('has not left this machine');
    });
  });

  describe('the states', () => {
    it('never scanned offers a scan and shows no result', () => {
      show(vuln({ state: 'never', consent: 'granted' }));

      expect(press('Scan now')).toBeTruthy();
      expect(panel().querySelector('.vuln-finding')).toBeNull();
    });

    it('scanning shows progress a screen reader can read, and a cancel that stops it', () => {
      show(
        vuln({ state: 'scanning', status: 'running', consent: 'granted', progress: { done: 40, total: 412 } })
      );

      const bar = panel().querySelector('.vuln-track');
      expect(bar.getAttribute('role')).toBe('progressbar');
      expect(bar.getAttribute('aria-valuenow')).toBe('40');
      expect(bar.getAttribute('aria-valuemax')).toBe('412');
      expect(panel().textContent).toContain('40 of 412 components');

      press('Cancel').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
      expect(sent.pop()).toEqual({ type: 'vulnCancel' });
    });

    it('results list each finding with where it came from and what fixes it', () => {
      show(vuln({ state: 'results', status: 'completed', consent: 'granted', report: report([finding()]) }));

      const row = panel().querySelector('.vuln-finding');
      expect(row.textContent).toContain('left-pad@1.3.0');
      expect(row.textContent).toContain('GHSA-1234-abcd-5678');
      expect(row.textContent).toContain('direct dependency');
      expect(row.textContent).toContain('package-lock.json');
      expect(row.textContent).toContain('Patched in 1.3.1');
    });

    it('offline keeps the previous result and dates it', () => {
      show(
        vuln({
          state: 'offline',
          consent: 'granted',
          reason: 'unreachable',
          note: 'The vulnerability database could not be reached.',
          report: report([finding()]),
        })
      );

      expect(panel().textContent).toContain('As of ');
      expect(panel().textContent).toContain('1h ago');
      expect(panel().textContent).toContain('could not be reached');
      expect(panel().querySelector('.vuln-finding')).not.toBeNull();
    });

    it('paints the state word the host decided, and derives none of its own', () => {
      show(vuln({ state: 'scanning', status: 'running', consent: 'granted' }));
      expect(panel().querySelector('.vuln-state').getAttribute('data-status')).toBe('running');

      show(vuln({ state: 'results', status: 'completed', consent: 'granted', report: report([]) }));
      expect(panel().querySelector('.vuln-state').getAttribute('data-status')).toBe('completed');

      show(vuln({ state: 'failed', status: 'failed', consent: 'granted', reason: 'unreachable' }));
      expect(panel().querySelector('.vuln-state').getAttribute('data-status')).toBe('failed');
    });

    it('a failed scan says why in the words the host chose', () => {
      show(
        vuln({
          state: 'failed',
          status: 'failed',
          consent: 'granted',
          reason: 'noScanner',
          note: 'This build carries no vulnerability-database client yet, so nothing was sent anywhere.',
        })
      );

      expect(panel().textContent).toContain('nothing was sent anywhere');
      expect(press('Scan again')).toBeTruthy();
    });

    it('a clean result says what was asked about rather than showing an empty list', () => {
      show(
        vuln({ state: 'results', consent: 'granted', report: report([], { counts: [], total: 0, shown: 0 }) })
      );

      expect(panel().textContent).toContain('No advisory matched 412 components');
    });

    it('withdrawing consent is one press away from the results', () => {
      show(vuln({ state: 'results', consent: 'granted', report: report([finding()]) }));
      press('Withdraw consent').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

      expect(sent.pop()).toEqual({ type: 'vulnConsent', granted: false });
    });
  });

  describe('a malicious package', () => {
    const malicious = () =>
      finding({
        id: 'MAL-2024-0001',
        tier: 'malicious',
        tierLabel: 'Malicious package',
        malicious: true,
        cvss: null,
        cvssType: null,
        fixed: [],
      });

    it('is labelled as its own tier and carries no score anywhere on screen', () => {
      show(
        vuln({
          state: 'results',
          consent: 'granted',
          report: report([malicious()], {
            counts: [{ tier: 'malicious', label: 'Malicious package', count: 1 }],
          }),
        })
      );

      const row = panel().querySelector('.vuln-finding');
      expect(row.getAttribute('data-tier')).toBe('malicious');
      expect(row.textContent).toContain('Malicious package');
      expect(row.querySelector('.vuln-cvss')).toBeNull();
      expect(row.textContent).toContain('No patched version is published.');
    });

    it('is drawn first, above anything the host rated', () => {
      show(
        vuln({
          state: 'results',
          consent: 'granted',
          report: report([malicious(), finding()]),
        })
      );

      const tiers = Array.from(panel().querySelectorAll('.vuln-finding')).map((r) =>
        r.getAttribute('data-tier')
      );
      expect(tiers[0]).toBe('malicious');
    });
  });

  describe('advisory text is third-party content', () => {
    it('a summary is written as text, so its markup is never parsed', () => {
      show(
        vuln({
          state: 'results',
          consent: 'granted',
          report: report([finding({ summary: '<img src=x onerror="window.__pwned = 1">' })]),
        })
      );

      expect(panel().querySelector('.vuln-summary img')).toBeNull();
      expect(panel().querySelector('.vuln-summary').textContent).toContain('<img');
      expect(win.__pwned).toBeUndefined();
    });

    it('the long advisory goes through the markdown sanitiser, never straight into the DOM', () => {
      win = loadFrontend(['app-session.js', 'app-composer.js']);
      sent = [];
      win.__ccSend = (json) => sent.push(JSON.parse(json));
      show(
        vuln({
          state: 'results',
          consent: 'granted',
          report: report([
            finding({ details: 'Bad <script>window.__pwned = 1</script> and <img src=x onerror="1">' }),
          ]),
        })
      );
      press('Read advisory').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

      const details = panel().querySelector('.vuln-details');
      expect(details).not.toBeNull();
      expect(details.querySelector('script')).toBeNull();
      expect(details.innerHTML).not.toContain('onerror');
      expect(win.__pwned).toBeUndefined();
    });
  });

  describe('the way in', () => {
    it('the Security button appears only once the host says the view has something to say', () => {
      win.cc.session({});
      expect(securityBtn().hidden).toBe(true);

      win.cc.session({ vuln: vuln() });
      expect(securityBtn().hidden).toBe(false);
    });

    it('the host can open the view directly, without the user finding the button', () => {
      win.cc.session({ vuln: vuln() });
      win.cc.showVulnView();

      expect(panel().hasAttribute('hidden')).toBe(false);
      expect(panel().textContent).toContain(ENDPOINT);
    });

    it('asking Claude to fix one names that finding and leaves the dashboard', () => {
      show(vuln({ state: 'results', consent: 'granted', report: report([finding()]) }));
      press('Ask Claude to update this dependency').dispatchEvent(
        new win.MouseEvent('click', { bubbles: true })
      );

      expect(sent.pop()).toEqual({ type: 'vulnFix', findingId: 'GHSA-1234-abcd-5678' });
      expect(panel().hasAttribute('hidden')).toBe(true);
    });
  });
});
