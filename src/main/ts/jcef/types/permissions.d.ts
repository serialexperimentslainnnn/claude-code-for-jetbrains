interface QuestionOption {
  label?: unknown;
  description?: unknown;
  preview?: unknown;
}

interface QuestionSpec {
  question?: unknown;
  header?: unknown;
  multiSelect?: boolean;
  options?: QuestionOption[];
}

interface QuestionOptionEl extends HTMLElement {
  __qText?: string;
  __label?: string;
}

interface ElicitField {
  name?: unknown;
  type?: unknown;
  required?: boolean;
  title?: unknown;
}

interface Elicitation {
  mode?: string;
  url?: unknown;
  description?: unknown;
  fields?: ElicitField[];
  serverName?: unknown;
  message?: unknown;
}

interface GuardAlertSpec {
  rule?: unknown;
  label?: unknown;
  category?: unknown;
  reason?: unknown;
}

interface PermissionCard {
  id?: unknown;
  tool?: string;
  title?: string;
  headline?: string;
  summary?: unknown;
  description?: unknown;
  blockedPath?: unknown;
  decisionReason?: unknown;
  diff?: unknown;
  reviewable?: boolean;
  guard?: GuardAlertSpec | null;
  questions?: QuestionSpec[];
  elicitation?: Elicitation | null;
  isPlan?: boolean;
  planText?: unknown;
  scope?: unknown;
}

interface PermissionsNs {
  mount(): HTMLElement | null;
  esc(s: unknown): string;
  md(s: unknown): string;
  send(obj: unknown): void;
  sendFor(card: PermissionCard, obj: Record<string, unknown>): void;
  isHttpUrl(u: unknown): boolean;
  button(props: HProps, onClick: () => void): HTMLElement;
  buildQuestionCard(card: PermissionCard): HTMLElement;
  buildElicitCard(card: PermissionCard): HTMLElement;
  buildPlanCard(card: PermissionCard): HTMLElement;
  buildPermCard(card: PermissionCard): HTMLElement;
  buildCard(card: unknown): HTMLElement | null;
  render(list: unknown, into?: HTMLElement | null): void;
}
