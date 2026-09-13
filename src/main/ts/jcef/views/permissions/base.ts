(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const PM = (CC.permissions = CC.permissions || ({} as PermissionsNs));

  PM.mount = function (): HTMLElement | null {
    return (CC.els && CC.els.permissions) || null;
  };

  PM.esc = function (s: unknown): string {
    if (typeof CC.escape === 'function') return CC.escape(s == null ? '' : String(s));
    return s == null ? '' : String(s);
  };

  PM.md = function (s: unknown): string {
    if (typeof CC.markdown === 'function') return CC.markdown(s == null ? '' : String(s));
    return PM.esc(s);
  };

  PM.send = function (obj: unknown): void {
    if (typeof CC.send === 'function') CC.send(obj);
  };

  PM.sendFor = function (card: PermissionCard, obj: Record<string, unknown>): void {
    const scope = card && card.scope ? String(card.scope) : null;
    if (scope) obj.scope = scope;
    PM.send(obj);
  };

  PM.isHttpUrl = function (u: unknown): boolean {
    if (!u || typeof u !== 'string') return false;
    return /^https?:\/\//i.test(u.trim());
  };

  PM.button = function (props: HProps, onClick: () => void): HTMLElement {
    const withClick: HProps = { attrs: { type: 'button' }, on: { click: onClick } };
    for (const key in props)
      if (Object.prototype.hasOwnProperty.call(props, key)) withClick[key] = props[key];
    return CC.h('button', withClick);
  };
})();
