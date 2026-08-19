const { loadFrontend } = require('./helpers/load');

function commands() {
  return [
    { name: 'compact-history', description: 'Summarise the conversation so far' },
    { name: 'review', description: 'Review the working tree' },
    { name: 'git:commit', description: 'Commit the staged changes' },
    { name: 'btw', description: 'Ask a side question about commit conventions' },
    { name: 'commit', description: 'Commit with a generated message' },
    { name: 'precommit', description: 'Run the pre-commit checks' },
  ];
}

function idleState(extra) {
  return {
    turnActive: false,
    interrupting: false,
    running: true,
    provider: { id: 'anthropic', label: 'Anthropic', options: [] },
    model: { label: 'Opus', options: [] },
    mode: { wire: 'default', label: 'Default', options: [] },
    effort: { label: 'Default', options: [] },
    thinking: { on: true, options: [] },
    queue: [],
    ...extra,
  };
}

let win;
let input;
let sent;

beforeAll(() => {
  win = loadFrontend(['app-composer.js']);
  input = win.CC.composer.els.input;
});

beforeEach(() => {
  sent = [];
  win.CC.send = (m) => sent.push(m);
  win.cc.state(idleState());
  win.cc.meta({ commands: commands() });
  type('');
});

const palette = () => win.CC.els.palette;
const isOpen = () => !palette().hasAttribute('hidden');
const rows = () => [...palette().querySelectorAll('.palette-item')];
const names = () => rows().map((r) => r.querySelector('.palette-name').textContent.replace('/', ''));

function type(value) {
  input.value = value;
  input.dispatchEvent(new win.Event('input', { bubbles: true }));
}

function press(key) {
  const e = new win.KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
  input.dispatchEvent(e);
  return e;
}

describe('palette — one text box, and it is the composer', () => {
  it('has no text field of its own — the palette is a list, nothing else', () => {
    type('/');
    expect(isOpen()).toBe(true);
    expect(palette().querySelector('input')).toBeNull();
    expect(palette().querySelector('textarea')).toBeNull();
  });

  it('keeps focus in the composer instead of moving it into the overlay', () => {
    input.focus();
    type('/');
    expect(win.document.activeElement).toBe(input);
  });

  it('filters from what the composer holds, with no second query to keep in sync', () => {
    type('/');
    expect(rows().length).toBe(commands().length);
    type('/precom');
    expect(names()).toEqual(['precommit']);
  });

  it('closes when the query stops being a command token', () => {
    type('/rev');
    expect(isOpen()).toBe(true);
    type('/review something');
    expect(isOpen()).toBe(false);
  });

  it('says so when nothing matches, instead of showing a stale list', () => {
    type('/zzzz');
    expect(rows()).toEqual([]);
    expect(palette().querySelector('.palette-empty').textContent).toBe('No matching commands');
  });
});

describe('palette — matching is scored', () => {
  it('ranks the exact name first, then prefixes, then segments, then the rest', () => {
    type('/commit');
    expect(names()).toEqual(['commit', 'git:commit', 'precommit', 'btw']);
  });

  it('a name match outranks a description match', () => {
    type('/review');
    expect(names()[0]).toBe('review');
  });

  it('an unqueried palette is alphabetical, not whatever order the host listed', () => {
    type('/');
    expect(names()).toEqual([...commands().map((c) => c.name)].sort());
  });

  it('ties break on the shorter name — the more specific answer wins', () => {
    win.cc.meta({
      commands: [
        { name: 'plan-and-execute-everything', description: '' },
        { name: 'plan', description: '' },
      ],
    });
    type('/plan');
    expect(names()).toEqual(['plan', 'plan-and-execute-everything']);
  });
});

describe('palette — the keyboard enters the list, it is never taken by it', () => {
  const activeEl = () => palette().querySelector('.palette-item.active .palette-name');
  const active = () => (activeEl() ? activeEl().textContent : null);

  it('has no selected row until an arrow says so', () => {
    type('/commit');
    expect(rows().length).toBeGreaterThan(1);
    expect(active()).toBeNull();
    expect(input.getAttribute('aria-activedescendant')).toBeNull();
  });

  it('the first arrow lands on the end it points at, and then it wraps', () => {
    type('/commit');
    press('ArrowDown');
    expect(active()).toBe('/commit');
    expect(input.getAttribute('aria-activedescendant')).toBe('palette-opt-0');
    press('ArrowDown');
    expect(active()).toBe('/git:commit');
    press('ArrowUp');
    expect(active()).toBe('/commit');
    press('ArrowUp');
    expect(active()).toBe('/btw');
  });

  it('Enter picks the row the arrows selected, instead of sending the turn', () => {
    type('/commit');
    press('ArrowDown');
    press('ArrowDown');
    press('Enter');
    expect(input.value).toBe('/git:commit ');
    expect(sent.filter((m) => m.type === 'send')).toEqual([]);
    expect(isOpen()).toBe(false);
  });

  it('Enter SENDS a command that was typed out — one press, not two', () => {
    type('/commit');
    press('Enter');
    expect(sent.filter((m) => m.type === 'send')).toEqual([{ type: 'send', text: '/commit' }]);
    expect(isOpen()).toBe(false);
  });

  it('typing again takes the keyboard back out of the list', () => {
    type('/comm');
    press('ArrowDown');
    expect(active()).toBe('/commit');
    type('/commi');
    expect(active()).toBeNull();
    press('Enter');
    expect(sent.filter((m) => m.type === 'send')).toEqual([{ type: 'send', text: '/commi' }]);
  });

  it('hovering a row does not arm Enter', () => {
    type('/commit');
    rows()[2].dispatchEvent(new win.MouseEvent('mouseenter', { bubbles: true }));
    expect(active()).toBeNull();
  });

  it('Escape closes the list instead of interrupting the turn', () => {
    win.cc.state(idleState({ turnActive: true }));
    type('/commit');
    press('Escape');
    expect(isOpen()).toBe(false);
    expect(sent.filter((m) => m.type === 'interrupt')).toEqual([]);
  });

  it('a dismissed palette stays dismissed while the query is still being typed', () => {
    type('/com');
    press('Escape');
    type('/comm');
    expect(isOpen()).toBe(false);
  });

  it('clicking a row drops the command into the composer, ready for its argument', () => {
    type('/pre');
    rows()[0].dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(input.value).toBe('/precommit ');
    expect(isOpen()).toBe(false);
  });

  it('cc.openPalette seeds the composer with the "/" the list is answering', () => {
    win.cc.openPalette();
    expect(input.value).toBe('/');
    expect(isOpen()).toBe(true);
    expect(win.document.activeElement).toBe(input);
  });
});

describe('palette — accessibility (WCAG 2.2 AA)', () => {
  it('the composer field carries the combobox role while the list is open (4.1.2)', () => {
    type('/com');
    expect(input.getAttribute('role')).toBe('combobox');
    expect(input.getAttribute('aria-expanded')).toBe('true');
    expect(input.getAttribute('aria-controls')).toBe('palette-list');
    expect(input.getAttribute('aria-autocomplete')).toBe('list');
  });

  it('the combobox semantics come off again when the list closes', () => {
    type('/com');
    press('Escape');
    expect(input.getAttribute('role')).toBeNull();
    expect(input.getAttribute('aria-expanded')).toBeNull();
    expect(input.getAttribute('aria-activedescendant')).toBeNull();
  });

  it('the active option is named by aria-activedescendant, once there is one', () => {
    type('/commit');
    expect(input.getAttribute('aria-activedescendant')).toBeNull();
    press('ArrowDown');
    expect(input.getAttribute('aria-activedescendant')).toBe(rows()[0].id);
    press('ArrowDown');
    expect(input.getAttribute('aria-activedescendant')).toBe(rows()[1].id);
    expect(rows()[1].getAttribute('aria-selected')).toBe('true');
    expect(rows()[0].getAttribute('aria-selected')).toBe('false');
  });

  it('the list is a listbox of options, with a name of its own', () => {
    type('/');
    const list = palette().querySelector('#palette-list');
    expect(list.getAttribute('role')).toBe('listbox');
    expect(list.getAttribute('aria-label')).toBe('Slash commands');
    expect(rows()[0].getAttribute('role')).toBe('option');
  });

  it('points at nothing when there is no option to point at', () => {
    type('/zzzz');
    expect(input.getAttribute('aria-activedescendant')).toBeNull();
  });
});
