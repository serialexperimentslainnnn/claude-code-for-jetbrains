interface MarkedApi {
  (markdown: string, options?: object): string;
  parse(markdown: string, options?: object): string;
  use(...extensions: object[]): void;
}

interface DomPurifyApi {
  sanitize(dirty: string, config?: object): string;
  addHook(name: string, hook: (node: Element, data?: unknown) => void): void;
}

interface HighlightApi {
  highlight(code: string, options: { language: string; ignoreIllegals?: boolean }): { value: string };
  getLanguage(name: string): unknown;
  highlightElement(element: Element): void;
}

type CcMethod = (payload?: unknown) => void;

interface MarkdownOptions {
  hostLinks?: boolean;
}

interface CcHost {
  [method: string]: CcMethod | undefined;
}

type Child = Node | string | number | boolean | null | undefined | Child[];

interface HProps {
  [key: string]: unknown;
}

interface PickItem {
  value: string;
  label: string;
  checked?: boolean;
}

interface PickMenu {
  menu: HTMLElement;
  sync(): void;
  toggle(): void;
  close(): void;
}

interface DurationMenuOptions {
  anchor: HTMLElement;
  home: HTMLElement;
  label?: string;
  watch?: () => Node | null;
  onPick: (value: string) => void;
}

interface PickMenuOptions extends DurationMenuOptions {
  items?: PickItem[];
  checkable?: boolean;
  checkedOf?: (value: string) => boolean;
  menuClass?: string;
  itemClass?: string;
}

interface CcElements {
  app: HTMLElement | null;
  conversation: HTMLElement | null;
  permissions: HTMLElement | null;
  composer: HTMLElement | null;
  palette: HTMLElement | null;
  a11yStatus: HTMLElement | null;
}

interface DiagramAction {
  label: string;
  onClick: () => void;
}

interface DiagramNode {
  id?: string | number | null;
  label?: unknown;
  meta?: unknown;
  action?: DiagramAction | null;
  children?: (DiagramNode | null | undefined)[] | null;
  kind?: string | null;
  status?: string | null;
  selected?: boolean;
  title?: string | null;
  name?: string | null;
  onPick?: (ev: MouseEvent) => void;
  running?: boolean;
}

interface PanView extends HTMLDivElement {
  __fit?: () => void;
}

interface FlashEl extends HTMLElement {
  __ccFlashLabel?: string | null;
  __ccFlashTimer?: ReturnType<typeof setTimeout> | null;
}

interface CcShared {
  send(obj: unknown): void;
  escape(s: unknown): string;
  h(tag: string, props?: HProps | null, ...children: Child[]): HTMLElement;
  resetInShort(iso: string | null | undefined): string | null;
  resetIn(iso: string | null | undefined): string | null;
  on(event: string, fn: (...args: unknown[]) => void): () => void;
  emit(event: string, ...args: unknown[]): void;
  els: CcElements;
  announce(message: unknown): void;
  coverTranscript(owner: string, covered: boolean): void;
  placeMenu(menu: HTMLElement, anchor: HTMLElement): void;
  GUARD_DURATIONS: { token: string; label: string }[];
  durationMenu(opts: DurationMenuOptions): PickMenu;
  pickMenu(opts: PickMenuOptions): PickMenu;
  flashCopied(el: HTMLElement): void;
  selfCheck(): void;
  diagnostics(): void;
  markdown(text: unknown, opts?: MarkdownOptions): string;
  decorateOneCodeBlock(code: Element): void;
  languageForPath(path: unknown): string | null;
  diagramLabel(kind: string | null | undefined, depth: number, label: unknown): string;
  diagramShown(kind: string | null | undefined, depth: number, label: unknown): string;
  diagram(roots: unknown): HTMLElement | null;
  panView(canvas: HTMLElement, label?: string | null, key?: string | null): PanView;
  applyTheme(vars: unknown): void;
  __themeVars?: Record<string, string>;
  reducedMotion?: boolean;
  isVibe(): boolean;
  nyanSvg(): string;
  transcript: TranscriptNs;
  composer: ComposerNs;
  permissions: PermissionsNs;
  dash: DashNs;
  tabbar: TabbarNs;
  gitChatActive?(): boolean;
  [name: string]: unknown;
}

declare var marked: MarkedApi;
declare var DOMPurify: DomPurifyApi;
declare var hljs: HighlightApi;
declare var cc: CcHost;
declare var CC: CcShared;

interface Window {
  cc: CcHost;
  CC: CcShared;
  __ccSend?: (json: string) => void;
  marked?: MarkedApi;
  DOMPurify?: DomPurifyApi;
  hljs?: HighlightApi;
}
