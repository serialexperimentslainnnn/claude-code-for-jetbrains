interface PillOption {
  id?: string;
  value?: string | null;
  wire?: string;
  on?: boolean;
  label?: unknown;
  selected?: boolean;
  group?: string;
}

interface PillField {
  label?: unknown;
  id?: unknown;
  options?: PillOption[];
}

interface PillDef {
  key: string;
  field: string;
  idKey: string;
  msg(o: PillOption): unknown;
}

interface Pill {
  el: HTMLElement;
  label: HTMLElement;
  def: PillDef;
  icon: HTMLElement | null;
}

interface UsageWindow {
  key?: string;
  label?: string;
  pct?: number | null;
  resetsAt?: string | null;
}

interface ComposerState {
  running?: boolean;
  starting?: boolean;
  resuming?: boolean;
  binaryMissing?: boolean;
  needsLogin?: boolean;
  turnActive?: boolean;
  interrupting?: boolean;
  guardOn?: boolean;
  remoteControlOn?: boolean;
  remoteControlError?: string | null;
  godModeOn?: boolean;
  queue?: unknown[];
  suggestion?: unknown;
  thinkingStatus?: string | null;
  context?: { pct?: number } | null;
  tokensOut?: number;
  reasoningTokens?: number;
  costUsd?: number;
  usage?: UsageWindow[];
  [field: string]: unknown;
}

interface ComposerEls {
  card: HTMLElement;
  input: HTMLTextAreaElement;
  send: HTMLElement;
  pills: Record<string, Pill>;
  queue: HTMLElement;
  ghost: HTMLElement;
  readout: HTMLElement;
  usageBars: HTMLElement;
  attachments: HTMLElement;
  attachBtn: HTMLElement;
}

interface OverflowMetrics {
  available: number;
  overflowing: boolean;
  ends: number[];
  reserved: number;
  toggle: number;
}

interface OverflowPlan {
  visible: number;
  toggle: boolean;
}

interface OverflowOptions {
  row: HTMLElement;
  label: string;
  items(): HTMLElement[];
  reserved?(): HTMLElement[];
  place(btn: HTMLElement): void;
  activate?(el: HTMLElement, anchor: HTMLElement): boolean;
}

interface OverflowApi {
  update(force?: boolean): void;
  close(returnFocus: boolean): void;
  toggle: HTMLElement;
  collected(): HTMLElement[];
}

interface OverflowRow extends HTMLElement {
  __ccOverflow?: OverflowApi;
}

interface OpenMenu {
  el: HTMLElement;
  pill: string;
  anchor: HTMLElement;
  sig?: string;
}

interface Roving {
  set(row: HTMLElement | null | undefined): void;
  focus(row: HTMLElement | null | undefined): void;
  step(delta: number): void;
}

interface RecentFile {
  name?: string;
  path?: string;
  ext?: string;
}

interface AttachData {
  recent: RecentFile[];
  hasSelection: boolean;
  hasFile: boolean;
}

interface TreeEntry {
  name?: string;
  path: string;
  directory?: boolean;
}

interface TreeDir {
  entries: TreeEntry[] | null;
  pending: boolean;
  truncated: boolean;
}

interface TreeState {
  mode: string;
  multi: boolean;
  query: string;
  dirs: Record<string, TreeDir>;
  open: Record<string, boolean>;
  sel: Record<string, boolean>;
  exp: Record<string, string[]>;
  capped: Record<string, boolean>;
}

interface TreeRow extends HTMLElement {
  __ccPath?: string;
  __ccDir?: boolean;
}

interface Attachment {
  id?: unknown;
  kind?: unknown;
  label?: unknown;
}

interface AttachNs {
  data: AttachData;
  view: string;
  tree: TreeState | null;
  attIconGlyph(kind: string): string;
  folderGlyph(): string;
  fileIconGlyph(ext: string | undefined): string;
  extOf(name: unknown): string;
  isText(v: unknown): v is string;
  menuEl(): HTMLElement | null;
  bodyEl(): HTMLElement | null;
  reposition(): void;
  renderMenu(menu: HTMLElement, from?: string | null, focusSearch?: boolean): void;
  buildRootView(body: HTMLElement): void;
  enterTree(mode: string): void;
  leaveTree(): void;
  requestChildren(path: string): void;
  matchesQuery(entry: TreeEntry): boolean;
  hasMatch(path: string): boolean;
  openFor(path: string): boolean;
  visibleEntry(entry: TreeEntry): boolean;
  resetMatches(): void;
  buildTreeView(body: HTMLElement): void;
  doneButton(): HTMLElement;
  setMulti(on: boolean): void;
  renderTree(): void;
  treeEl(): HTMLElement | null;
  selectedCount(): number;
  confirmSelection(): void;
  dirState(path: string): string;
  applyRowState(row: HTMLElement, entry: TreeEntry): void;
  syncSelection(): void;
  markPaths(paths: string[], on: boolean): void;
  onRowPress(entry: TreeEntry, e: MouseEvent): void;
  setOpen(path: string, on: boolean): void;
  announceCap(entry: TreeEntry): void;
  rowByPath(path: string): TreeRow | null;
  visibleRows(): TreeRow[];
  rows: Roving;
  onMenuKey(e: KeyboardEvent): void;
  attachImageFile(file: File | null | undefined): void;
  isImageFile(f: File | null | undefined): boolean;
}

interface PaletteCommand {
  name?: unknown;
  description?: unknown;
}

interface PaletteItem {
  name: string;
  description: string;
  score: number;
}

interface PaletteState {
  items: PaletteItem[];
  active: number;
  navigated: boolean;
}

interface PaletteEl extends HTMLElement {
  __built?: boolean;
  __list?: HTMLElement;
}

interface PaletteNs {
  state: PaletteState;
  queryOf(value: unknown): string | null;
  setCommands(list: PaletteCommand[]): void;
  rank(q: string): PaletteItem[];
  composerInput(): HTMLTextAreaElement | null;
  paletteEl(): PaletteEl | null;
  isOpen(): boolean;
  ensureBuilt(): PaletteEl | null;
  linkInput(open: boolean): void;
  syncActiveDescendant(): void;
  renderList(onPick: (idx: number) => void): void;
  updateActiveClass(): void;
}

interface SettingItem {
  key: unknown;
  label?: unknown;
  group?: unknown;
  sub?: unknown;
  type?: unknown;
  on?: boolean;
  hostOwned?: boolean;
}

interface SettingsPanel {
  group: string;
  title: string;
  path: string;
  rows: SettingItem[];
  subs: SettingsSub[];
  list: SettingItem[];
}

interface SettingsSub {
  name: string;
  list: SettingItem[];
}

interface SettingsGroup extends SettingsSub {
  direct: SettingItem[];
  subs: SettingsSub[];
}

interface SettingsRow extends HTMLElement {
  __ccKey?: string;
  __ccGroup?: string;
  __ccFocusId?: string;
}

interface SettingsNs {
  SEP: string;
  payload: { items?: unknown } | null;
  view: string | null;
  items(): SettingItem[];
  labelOf(it: SettingItem): string;
  groupOf(it: SettingItem): string;
  isRadio(it: SettingItem): boolean;
  groups(): SettingsGroup[];
  panelFor(path: string): SettingsPanel | null;
  structureSig(): string;
  allRows(): SettingsRow[];
  applyState(row: HTMLElement, on: boolean): void;
  enterGroup(path: string): void;
  openGroup(path: string): void;
  leaveGroup(): void;
  close(returnFocus: boolean): void;
  buildBody(): DocumentFragment;
}

interface InstallMethod {
  id: string;
  label?: string;
  shell?: string;
  display: string;
}

interface AuthCard extends HTMLElement {
  __url?: string | null;
}

interface WiredEl extends HTMLElement {
  __wired?: boolean;
}

interface ComposerNs {
  h(tag: string, props?: HProps | null, ...children: Child[]): HTMLElement;
  send(obj: unknown): void;
  els: ComposerEls | null;
  lastState: ComposerState | null;
  hostClipboard: boolean;
  roving(rows: () => HTMLElement[]): Roving;
  overflowFit(m: OverflowMetrics | null | undefined): OverflowPlan;
  overflowMeasure(
    row: HTMLElement,
    items: HTMLElement[],
    reserved: HTMLElement[],
    toggle: HTMLElement | null
  ): OverflowMetrics;
  overflowLabel(el: HTMLElement): string;
  dotsGlyph(): string;
  createOverflow(opts: OverflowOptions): OverflowApi | null;
  refreshOverflow(force?: boolean): void;
  openMenu: OpenMenu | null;
  menuSig(def: PillDef): string;
  togglePillMenu(def: PillDef, anchorEl: HTMLElement): void;
  positionMenu(menu: HTMLElement, anchor: HTMLElement): void;
  closeMenu(): void;
  PILL_DEFS: PillDef[];
  buildPill(def: PillDef): Pill;
  renderPills(s: ComposerState): void;
  syncOpenMenu(): void;
  attach: AttachNs;
  attachGlyph(): string;
  toggleAttachMenu(anchorEl: HTMLElement): void;
  renderAttachments(): void;
  wireImageDrop(card: HTMLElement | null): void;
  insertAtCursor(input: HTMLInputElement | HTMLTextAreaElement, text: string): void;
  wireImagePaste(input: HTMLTextAreaElement | null): void;
  renderReadout(s: ComposerState): void;
  renderMini(): void;
  palette: PaletteNs;
  openPalette(): void;
  setCommands(list: PaletteCommand[]): void;
  renderBoot(s: ComposerState): void;
  setInstallMethods(methods: InstallMethod[]): void;
  authWanted(s: ComposerState): boolean;
  renderAuth(s: ComposerState): void;
  buildActionRows(): HTMLElement | null;
  viewsRow(): HTMLElement | null;
  mountSettingsButton(): HTMLElement | null;
  settings: SettingsNs;
  ensureBuilt(): boolean;
  autosize(input: HTMLTextAreaElement | HTMLInputElement | null): void;
  setGuardOn(on: boolean | undefined): void;
  setRemoteControlOn(on: boolean | undefined, error: unknown): void;
  setGodMode(on: boolean | undefined): void;
  flameGlyph(lit: boolean): string;
  buildToggles(barRight: HTMLElement): {
    follow: HTMLElement;
    guard: HTMLElement;
    rc: HTMLElement;
    flame: HTMLElement;
    vibe: HTMLElement;
  };
  applyFollow(): void;
  sendGlyph(): string;
  wireInput(input: HTMLTextAreaElement): void;
  onSendClick(e: Event): void;
  setGhost(text: string): void;
  renderGhost(): void;
  renderQueue(queue: unknown): void;
  renderSendMode(s: ComposerState): void;
  announceTurnState(s: ComposerState): void;
  [name: string]: unknown;
}
