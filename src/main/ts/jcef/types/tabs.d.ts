interface TabChat {
  id?: unknown;
  title?: unknown;
  selected?: boolean;
  attention?: boolean;
}

interface TabNode {
  id?: string;
  parent?: string | null;
  label?: unknown;
  type?: unknown;
  status?: unknown;
  running?: boolean;
}

interface TabSelection {
  kind: string;
  id: string;
}

interface TabWork {
  kind: string;
  id: string;
  node: TabNode;
  depth: number;
  hasKids: boolean;
}

interface TabBranch {
  rootId: string;
  rootLabel: string;
  items: TabWork[];
}

interface TabPillOptions {
  label: unknown;
  title?: string | null;
  status?: string | null;
  selected?: boolean;
  expanded?: boolean | null;
  onClick: (ev: Event) => void;
  onClose?: (() => void) | null;
}

interface TabbarNs {
  state: { chats: TabChat[]; tree: TabNode[]; tasks: TabNode[] };
  selected: TabSelection | null;
  send(msg: unknown): void;
  bar(): HTMLElement | null;
  nodeById(id: string): TabNode | null;
  taskById(id: string): TabNode | null;
  pruneSelection(): void;
  isSelected(kind: string, id: string): boolean;
  chatWork(): TabWork[];
  openBranches(): TabBranch[];
  drawn: string | null;
  drawnSignature(): string;
  pill(opts: TabPillOptions): HTMLElement;
  scrollLeftTo(el: HTMLElement, x: number): void;
  dragToScroll(el: HTMLElement): void;
  wheelToScroll(capsule: HTMLElement): void;
  keepFocusVisible(capsule: HTMLElement): void;
  showChat(): void;
  showAgent(agentId: string): void;
  showTask(taskId: string): void;
}
