(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const W = (D.workloads = D.workloads || ({} as WorkloadsNs));
  const h = D.h;
  const send = D.send;
  const card = D.card;

  const WINDOW_SELECT_ID = 'cc-workload-window';

  function windowControl(spec: WorkloadWindowSpec | null | undefined): HTMLElement | null {
    const options = spec && Array.isArray(spec.options) ? spec.options.filter(Boolean) : [];
    if (!options.length || !spec) return null;
    const current = spec.minutes == null ? null : Number(spec.minutes);

    const select = h('select', {
      class: 'wl-window-select',
      attrs: { id: WINDOW_SELECT_ID },
      on: {
        change: function (ev: Event) {
          const minutes = Number((ev.target as HTMLSelectElement).value);
          if (!isFinite(minutes)) return;
          send({ type: 'setWorkloadWindow', minutes: minutes });
        },
      },
    });

    options.forEach(function (option) {
      if (!option) return;
      const minutes = Number(option.minutes);
      if (!isFinite(minutes)) return;
      const text = option.label != null ? String(option.label) : String(minutes);
      const attrs: Record<string, string> = { value: String(minutes) };
      if (current != null && minutes === current) attrs.selected = 'selected';
      select.appendChild(h('option', { text: text, attrs: attrs }));
    });

    return h(
      'div',
      { class: 'wl-window' },
      h('label', {
        class: 'wl-window-label',
        attrs: { for: WINDOW_SELECT_ID },
        text: 'Keep finished workloads listed for',
      }),
      select
    );
  }

  function buildWorkloadsCard(payload: SessionPayload): HTMLElement | null {
    const chats: WorkloadChat[] =
      Array.isArray(payload.workloads) && payload.workloads.length
        ? (payload.workloads.filter(Boolean) as WorkloadChat[])
        : [
            {
              chatId: null,
              title: 'This chat',
              selected: true,
              tree: Array.isArray(payload.agentTree)
                ? (payload.agentTree.filter(Boolean) as WorkloadAgent[])
                : [],
              tasks: Array.isArray(payload.backgroundTasks)
                ? (payload.backgroundTasks.filter(Boolean) as WorkloadTask[])
                : [],
            },
          ];

    const control = windowControl(payload.workloadWindow as WorkloadWindowSpec | null | undefined);
    const roots = chats.map(W.chatNode).filter(function (n) {
      return n.children.length > 0;
    });

    const canvas = roots.length ? CC.diagram(roots) : null;
    if (!canvas) return control ? card('Workloads', [control, emptyNote()], true, 'workloads') : null;
    const view = CC.panView(canvas, 'Workloads diagram — drag to move, wheel to zoom', 'workloads');
    requestAnimationFrame(function () {
      if (view.__fit) view.__fit();
      requestAnimationFrame(function () {
        if (view.__fit) view.__fit();
      });
    });
    return card('Workloads', [control, view], true, 'workloads');
  }

  function emptyNote(): HTMLElement {
    return h(
      'div',
      { class: 'stat-row' },
      h('span', { class: 'stat-label', text: 'Nothing to show in this window.' })
    );
  }

  D.buildWorkloadsCard = buildWorkloadsCard;
})();
