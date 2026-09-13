interface TranscriptEntry {
  id?: string | number | null;
  speaker?: string;
  text?: string | null;
  meta?: string | null;
  state?: string | null;
  toolUseId?: string | null;
  open?: boolean;
  command?: string | null;
  filePath?: string | null;
  title?: string | null;
  message?: string | null;
  elapsed?: number | null;
  reviewable?: boolean;
  places?: CardPlace[] | null;
  parent?: string | null;
  order?: number | null;
  blockedRule?: string | null;
  bypassedRule?: string | null;
  bypassAction?: string | null;
}

interface CardPlace {
  label?: string;
  href?: string;
}

interface BodyEl extends HTMLElement {
  __rawText?: string;
}

interface RowEl extends HTMLElement {
  __outNode?: HTMLElement | null;
  __toolUseId?: string | null;
  __isAgentCard?: boolean;
  __cmdNode?: HTMLElement | null;
  __msgNode?: HTMLElement | null;
  __liveTail?: HTMLElement | null;
  __nameNode?: HTMLElement | null;
  __childrenNode?: HTMLElement | null;
  __elapsedNode?: HTMLElement | null;
  __filePath?: string | null;
  __diffBtn?: HTMLElement | null;
  __restoreBtn?: HTMLElement | null;
  __placesNode?: HTMLElement | null;
  __placesKey?: string;
  __label?: HTMLElement | null;
  __order?: number | null;
  __autoOpenedOnError?: boolean;
}

interface RowRec {
  el: RowEl;
  bodyNode: BodyEl | null;
  kind: string;
  outNode?: HTMLElement | null;
  speaker?: string;
  toolUseId?: string | null;
  text?: string | null;
  meta?: string | null;
  state?: string | null;
  bodyKey?: string;
  settled?: boolean;
}

interface LinkHit {
  token?: unknown;
  path?: unknown;
  line?: unknown;
}

interface TranscriptNs {
  el(tag: string, props?: HProps | null): HTMLElement;
  safeSend(obj: unknown): void;
  conversationEl(): HTMLElement | null;
  rows: Map<unknown, RowRec>;
  toolCards: Map<string, RowEl>;
  setBody(rec: RowRec, text: unknown): void;
  createRow(entry: TranscriptEntry, cards?: Map<string, RowEl>): RowRec;
  updateRow(rec: RowRec, entry: TranscriptEntry, links?: boolean): void;
  builderFor(speaker: string | undefined, entry: TranscriptEntry): RowRec;
  buildBlockNotice(rule: unknown, command: unknown): RowRec;
  buildBypassNotice(entry: TranscriptEntry): RowRec;
  buildTool(entry: TranscriptEntry | null | undefined): RowRec;
  toolIconSvg(meta: unknown): string;
  applyToolElapsed(node: RowEl, state: string | null | undefined, elapsedSecs: unknown): void;
  applyToolState(node: RowEl, state: string | null | undefined, meta: string | null | undefined): void;
  routeToolOutput(entry: TranscriptEntry, cards?: Map<string, RowEl>): boolean;
  scrollLiveToEnd(card: HTMLElement): void;
  renderCommandBlock(cmdNode: HTMLElement | null | undefined, commandText: unknown): void;
  renderPlaces(node: RowEl, places: CardPlace[] | null | undefined): void;
  jbHref(relPath: unknown, line?: unknown): string;
  renderToon(root: HTMLElement, json: string): unknown;
  renderToolLabel(nameEl: HTMLElement | null, text: unknown, filePath: unknown): void;
  requestLinks(rec: RowRec, entry: TranscriptEntry): void;
  runSearch(q: string | null | undefined, silent: boolean): void;
  refreshSearch(): void;
  resetSearch(): void;
  findNext(): void;
  findPrev(): void;
  hitCount(): number;
  activeHit(): number;
  updateFindCount(): void;
  resetFindBar(): void;
  scheduleScroll(stick: boolean): void;
  stickToBottom(): boolean;
  [name: string]: unknown;
}
