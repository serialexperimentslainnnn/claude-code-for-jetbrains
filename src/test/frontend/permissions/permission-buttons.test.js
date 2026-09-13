const { loadFrontend } = require('../helpers/load');

const cards = [
  { id: 'p1', tool: 'Edit', title: 'Edit', summary: 'Edit a.txt', reviewable: true, diff: '@@\n-a\n+b' },
  {
    id: 'g1',
    tool: 'Bash',
    title: 'Bash',
    summary: 'cat x',
    guard: { rule: 'CREDENTIALS', label: 'Block credential files', reason: 'reads credentials' },
  },
  { id: 'plan1', title: 'Plan', isPlan: true, planText: 'Do the thing.' },
  {
    id: 'q1',
    title: 'Question',
    questions: [{ question: 'Which?', options: [{ label: 'A' }, { label: 'B', description: 'the other' }] }],
  },
  {
    id: 'e1',
    title: 'srv',
    elicitation: {
      mode: 'form',
      message: 'Fill in',
      fields: [{ name: 'token', type: 'string', required: true }],
    },
  },
];

describe('every button on a permission card', () => {
  it('declares itself type=button, so no card can ever submit a form it lands inside', () => {
    const win = loadFrontend(['app-permissions.js']);
    win.cc.permissions(cards);
    const buttons = [...win.CC.els.permissions.querySelectorAll('button')];
    expect(buttons.length).toBeGreaterThan(12);
    const submitters = buttons.filter((b) => b.getAttribute('type') !== 'button').map((b) => b.textContent);
    expect(submitters).toEqual([]);
  });
});
