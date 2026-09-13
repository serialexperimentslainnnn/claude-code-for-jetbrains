(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const toolCards = TX.toolCards;
  const setBody = TX.setBody;

  function createRow(entry: TranscriptEntry, cards?: Map<string, RowEl>): RowRec {
    const known = cards || toolCards;
    const rec = TX.builderFor(entry.speaker, entry);
    rec.speaker = entry.speaker;
    rec.toolUseId = entry.toolUseId || null;
    if (entry.speaker === 'TOOL' && entry.toolUseId) {
      rec.outNode = rec.el.__outNode || rec.el.querySelector<HTMLElement>('.tool-out');
      rec.el.__toolUseId = entry.toolUseId;
      known.set(entry.toolUseId, rec.el);
      if (entry.meta === 'Task' || entry.meta === 'Agent') {
        rec.el.__isAgentCard = true;
        rec.el.classList.add('agent-link');
      }
      if (entry.open) {
        rec.el.classList.add('open');
      }
    }
    if (entry.speaker === 'TOOL') {
      const icNode = rec.el.querySelector('.ic');
      if (icNode) {
        icNode.innerHTML = TX.toolIconSvg(entry.meta);
      }
      if (entry.command) {
        TX.renderCommandBlock(rec.el.__cmdNode, entry.command);
        rec.el.classList.add('cmd-tool');
      }
      rec.el.__filePath = entry.filePath || null;
    }
    return rec;
  }

  const OWN_RUN = /^mcp__[a-z]+__run$/;

  function commandLabel(entry: TranscriptEntry): string {
    const meta = entry.meta == null ? '' : String(entry.meta);
    return meta && !OWN_RUN.test(meta) ? meta : entry.text || '';
  }

  function bodyKey(rec: RowRec, entry: TranscriptEntry): string {
    if (rec.speaker === 'TOOL' && entry.title) {
      return 't:' + entry.title;
    }
    if (rec.speaker === 'TOOL' && entry.command) {
      return 'c:' + commandLabel(entry);
    }
    if (rec.speaker === 'TOOL' && entry.filePath) {
      return 'f:' + entry.filePath + '\n' + entry.text;
    }
    return 'x:' + entry.text;
  }

  function renderBody(rec: RowRec, entry: TranscriptEntry, links?: boolean): void {
    if (rec.speaker === 'TOOL' && entry.title) {
      setBody(rec, entry.title);
    } else if (rec.speaker === 'TOOL' && entry.command) {
      setBody(rec, commandLabel(entry));
    } else if (rec.speaker === 'TOOL' && entry.filePath) {
      TX.renderToolLabel(rec.bodyNode, entry.text, entry.filePath);
    } else {
      setBody(rec, entry.text);
      if (links !== false && rec.speaker === 'ASSISTANT' && entry.state !== 'RUNNING') {
        TX.requestLinks(rec, entry);
      }
    }
  }

  function updateRow(rec: RowRec, entry: TranscriptEntry, links?: boolean): void {
    const key = bodyKey(rec, entry);
    const settled = entry.state !== 'RUNNING';
    if (rec.bodyKey !== key) {
      renderBody(rec, entry, links);
    } else if (settled && !rec.settled && links !== false && rec.speaker === 'ASSISTANT') {
      TX.requestLinks(rec, entry);
    }
    rec.bodyKey = key;
    rec.settled = settled;
    rec.text = entry.text;
    rec.meta = entry.meta;
    rec.state = entry.state;
    if (rec.speaker === 'TOOL') {
      TX.applyToolState(rec.el, entry.state, entry.meta);
      TX.applyToolElapsed(rec.el, entry.state, entry.elapsed);
      if (rec.el.__diffBtn) {
        rec.el.__diffBtn.hidden = !entry.reviewable;
      }
      if (rec.el.__restoreBtn) {
        rec.el.__restoreBtn.hidden = !entry.reviewable;
      }
      TX.renderPlaces(rec.el, entry.places);
    }
    if (rec.speaker === 'MEMORY' && rec.el.__label) {
      const title = entry.meta && String(entry.meta).trim() ? String(entry.meta) : '🧠 Recalled memories';
      rec.el.__label.textContent = title;
    }
  }

  TX.createRow = createRow;
  TX.updateRow = updateRow;
})();
