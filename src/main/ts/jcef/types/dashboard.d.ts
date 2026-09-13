interface SessionPayload {
  model?: unknown;
  cwd?: unknown;
  home?: unknown;
  account?: { email?: unknown; org?: unknown; plan?: unknown; provider?: unknown } | null;
  [name: string]: unknown;
}

interface WorkloadAgent {
  agentId?: string | null;
  parent?: string | null;
  label?: unknown;
  type?: unknown;
  status?: unknown;
  running?: boolean;
  chain?: string | null;
}

interface WorkloadTask {
  id?: unknown;
  agentId?: string | null;
  type?: unknown;
  desc?: unknown;
  status?: string | null;
  running?: boolean;
  chain?: string | null;
}

interface WorkloadChat {
  chatId?: string | number | null;
  title?: unknown;
  selected?: boolean;
  tree?: (WorkloadAgent | null)[];
  tasks?: (WorkloadTask | null)[];
}

interface WorkloadWindowSpec {
  minutes?: number | null;
  options?: ({ minutes?: unknown; label?: unknown } | null)[];
}

interface WorkloadsNs {
  chatNode(chat: WorkloadChat): DiagramNode & { children: DiagramNode[] };
  taskNode(t: WorkloadTask, chatId: unknown): DiagramNode;
}

interface GitAction {
  id?: unknown;
  label?: unknown;
  group?: unknown;
  kind?: unknown;
  hint?: unknown;
  status?: unknown;
}

interface GitRef {
  name?: unknown;
  kind?: unknown;
  hash?: unknown;
  current?: boolean;
}

interface GitCommit {
  hash?: unknown;
  short?: unknown;
  parents?: unknown[];
  author?: unknown;
  authoredAtMillis?: unknown;
  files?: unknown;
  subject?: unknown;
}

interface GitRepo {
  present?: boolean;
  root?: unknown;
  branch?: unknown;
  head?: unknown;
}

interface GitPayload {
  available?: boolean;
  repo?: GitRepo | null;
  actions?: (GitAction | null)[];
  commitActions?: (GitAction | null)[];
  changes?: unknown[];
  commits?: (GitCommit | null)[];
  refs?: (GitRef | null)[];
  topology?: { upstream?: unknown; ahead?: unknown; behind?: unknown; mergeBase?: unknown } | null;
}

interface LaneEntry {
  hash: string;
  parents: string[];
  changes?: string[];
  commit?: GitCommit;
}

interface LaneRow {
  item: LaneEntry;
  hash: string;
  index: number;
  lane: number;
  up: number[];
  down: number[];
  through: number[];
  parents: string[];
  merge: boolean;
}

interface LaneModel {
  rows: LaneRow[];
  lanes: number;
}

interface GitNs {
  BRANCHES_ACTION: string;
  gitOf(git: unknown): GitPayload | null;
  repoOf(g: GitPayload): GitRepo;
  list<T>(value: unknown): T[];
  text(value: unknown, fallback: string): string;
  textOrNull(value: unknown): string | null;
  actionById(g: GitPayload, id: string): GitAction | null;
  actionButton(action: GitAction): HTMLElement;
  gutter(row: LaneRow, lanes: number): HTMLElement;
  fileCount(n: unknown): string | null;
  ageSince(atMillis: unknown): string | null;
  ageText(ms: unknown): string | null;
}

interface GuardLogEntry {
  id?: unknown;
  tab?: unknown;
  rule?: unknown;
  ruleLabel?: unknown;
  category?: unknown;
  categoryId?: unknown;
  command?: unknown;
  detail?: unknown;
  tool?: unknown;
  verdict?: unknown;
  verdictLabel?: unknown;
  viaLabel?: unknown;
  at?: unknown;
  explainable?: boolean;
}

interface GuardLogTab {
  id?: unknown;
  label?: unknown;
  count?: unknown;
}

interface GuardLogCategory {
  id?: unknown;
  label?: unknown;
  rules?: ({ id?: unknown; label?: unknown } | null)[];
}

interface GuardLogPayload {
  tabs?: (GuardLogTab | null)[];
  entries?: (GuardLogEntry | null)[];
  catalog?: (GuardLogCategory | null)[];
  recording?: boolean;
  window?: { kept?: unknown; max?: unknown; dropped?: unknown; missing?: unknown } | null;
}

interface GuardLogNs {
  payload: GuardLogPayload | null;
  tab: string;
  queryRaw: string;
  query: string;
  pickedCategories: string[] | null;
  pickedRules: string[] | null;
  text(value: unknown, fallback: string): string;
  textOrNull(value: unknown): string | null;
  list<T>(value: unknown): T[];
  num(value: unknown): number;
  tabs(): GuardLogTab[];
  catalog(): GuardLogCategory[];
  rulesOfCategories(picked: string[] | null): { id: string; label: string }[];
  visibleEntries(id: string): GuardLogEntry[];
  currentTab(): string;
  when(at: unknown): string;
  filtering(): boolean;
  repaint(): void;
  buildFiltersCard(): HTMLElement | null;
  entryNode(entry: GuardLogEntry): HTMLElement;
}

interface VulnFinding {
  id?: unknown;
  tier?: unknown;
  tierLabel?: unknown;
  name?: unknown;
  version?: unknown;
  ecosystem?: unknown;
  originLabel?: unknown;
  manifest?: unknown;
  summary?: unknown;
  cvss?: unknown;
  cvssType?: unknown;
  fixed?: unknown[];
  details?: unknown;
  references?: unknown[];
}

interface VulnReport {
  asOfMillis?: unknown;
  findings?: VulnFinding[];
  counts?: ({ tier?: unknown; label?: unknown; count?: unknown } | null)[];
  queried?: unknown;
  total?: unknown;
  shown?: unknown;
}

interface VulnPayload {
  available?: boolean;
  state?: unknown;
  status?: unknown;
  operator?: unknown;
  endpoint?: unknown;
  note?: unknown;
  inventory?: { components?: unknown } | null;
  disclosure?: { sent?: unknown[]; caveats?: unknown[] } | null;
  progress?: { done?: unknown; total?: unknown } | null;
  report?: VulnReport | null;
}

interface VulnInventory {
  endpoint?: unknown;
  components?: ({ ecosystem?: unknown; name?: unknown; version?: unknown; originLabel?: unknown } | null)[];
  truncated?: boolean;
  total?: unknown;
}

interface VulnNs {
  inventory: VulnInventory | null;
  expanded: Record<string, boolean>;
  pickedTiers: string[];
  text(v: unknown): string;
  num(v: unknown): number;
  repaint(): void;
  announce(message: string): void;
  inv(v: VulnPayload): { components?: unknown };
  bullets(title: string, list: unknown): HTMLElement | null;
  button(label: string, variant: string, onPress: () => void): HTMLElement;
  inventoryButton(v: VulnPayload): HTMLElement;
  consentCard(v: VulnPayload, state: string): HTMLElement | null;
  statusCard(v: VulnPayload, state: string): HTMLElement | null;
  findingsCard(v: VulnPayload): HTMLElement | null;
}

interface LogLine {
  seq?: unknown;
  at?: unknown;
  level?: unknown;
  category?: unknown;
  text?: unknown;
}

interface LogPayload {
  debug?: boolean;
  reset?: boolean;
  ring?: { max?: unknown; dropped?: unknown } | null;
  lines?: (LogLine | null)[];
}

interface LogNs {
  entriesEl: HTMLElement | null;
  emptyEl: HTMLElement | null;
  lines: LogLine[];
  lastSeq: number;
  level: string;
  debug: boolean;
  ring: { max: number; dropped: number };
  listEl: HTMLElement | null;
  text(value: unknown, fallback: string): string;
  num(value: unknown): number;
  request(): void;
  shows(line: LogLine): boolean;
  absorb(payload: LogPayload): void;
  setVisible(visible: boolean): void;
  when(at: unknown): string;
  repaint(): void;
  list(): HTMLElement;
  lineNode(line: LogLine): HTMLElement;
  append(lines: LogLine[]): void;
  reset(): void;
  applyFilter(): void;
}

interface DashView {
  title: string;
  empty: string;
  cards(s: SessionPayload): (HTMLElement | null)[];
  visible?(shown: boolean): void;
}

interface DashPanelState {
  lastSession: SessionPayload | null;
  lastMcp: unknown;
  toggleBtn: HTMLElement | null;
  planBtn: HTMLElement | null;
  gitBtn: HTMLElement | null;
  vulnBtn: HTMLElement | null;
  panel: HTMLElement | null;
  inner: HTMLElement | null;
  toggles: HTMLElement | null;
  shown: boolean;
  built: boolean;
  gitTab: boolean;
  gitOpened: boolean;
  gitSub: string;
  currentView: string;
}

interface DashNs {
  core(): CcShared | null;
  conversation(): HTMLElement | null;
  appRoot(): HTMLElement | null;
  h(tag: string, props?: HProps | null, ...children: Child[]): HTMLElement;
  send(obj: unknown): void;
  num(v: unknown): number | null;
  fmtInt(v: unknown): string | null;
  fmtUsd(v: unknown): string | null;
  statRow(label: string, value: unknown): HTMLElement | null;
  card(title: string, body: unknown, wide?: boolean, anchor?: string): HTMLElement | null;
  leaveDashboard(): void;
  buildPlanCard(plan: unknown): HTMLElement | null;
  buildUsageCard(usage: unknown): HTMLElement | null;
  buildContextCard(ctx: unknown): HTMLElement | null;
  buildCostCard(cost: unknown): HTMLElement | null;
  buildAccountCard(acct: unknown): HTMLElement | null;
  buildEnvCard(payload: SessionPayload): HTMLElement | null;
  buildMcpCard(payload: unknown): HTMLElement | null;
  workloads: WorkloadsNs;
  buildWorkloadsCard(payload: SessionPayload): HTMLElement | null;
  git: GitNs;
  gitViewTabs(current: string): HTMLElement;
  gitLanes(entries: unknown): LaneModel;
  buildGitHeadCard(git: unknown): HTMLElement | null;
  buildGitActionsCard(git: unknown): HTMLElement | null;
  buildGitHistoryCard(git: unknown): HTMLElement | null;
  buildGitTopologyCard(git: unknown): HTMLElement | null;
  gitChatPane(): HTMLElement | null;
  gitChatShown(): void;
  guardLog: GuardLogNs;
  buildGuardCards(): (HTMLElement | null)[];
  guardTab(): string;
  guardVisible(visible: boolean): void;
  repaintGuard(): void;
  log: LogNs;
  buildLogCards(): (HTMLElement | null)[];
  logVisible(visible: boolean): void;
  repaintLog(): void;
  vuln: VulnNs;
  buildVulnCards(v: unknown): (HTMLElement | null)[];
  state: DashPanelState;
  VIEWS: Record<string, DashView>;
  defaultView(): string;
  gitChatOpen(): boolean;
  gitSubView(): string;
  setGitSubView(view: string): void;
  lastSession(): SessionPayload | null;
  reconcile(container: HTMLElement, cards: HTMLElement[]): void;
  syncViewVisibility(): void;
  render(): void;
  renderIfShown(): void;
  repaint(): void;
  viewButton(label: string, view: string | null): HTMLElement;
  announceView(): void;
  markActiveButton(): void;
  mountToggles(): void;
  ensureBuilt(): void;
  applyVisibility(): void;
  toggle(): void;
  toggleDashboard(): void;
  dashboardShown(): boolean;
  [name: string]: unknown;
}
