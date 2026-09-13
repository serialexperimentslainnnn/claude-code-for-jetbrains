const { loadFrontend } = require('../helpers/load');

function documentListeners(spy, type) {
  return spy.mock.calls.filter((call) => call[0] === type).length;
}

describe('the diagram pan view and the document', () => {
  it('rendering the view twice adds no listener to the document', () => {
    const win = loadFrontend([]);
    const added = vi.spyOn(win.document, 'addEventListener');

    win.CC.panView(win.CC.h('div'), 'Diagram', 'a');
    win.CC.panView(win.CC.h('div'), 'Diagram', 'b');

    expect(documentListeners(added, 'mousemove')).toBe(0);
    expect(documentListeners(added, 'mouseup')).toBe(0);
  });

  it('a drag listens on the document only while the button is down', () => {
    const win = loadFrontend([]);
    const added = vi.spyOn(win.document, 'addEventListener');
    const removed = vi.spyOn(win.document, 'removeEventListener');
    const view = win.CC.panView(win.CC.h('div'), 'Diagram', 'c');

    view.dispatchEvent(
      new win.MouseEvent('mousedown', { button: 0, clientX: 10, clientY: 10, bubbles: true })
    );
    expect(documentListeners(added, 'mousemove')).toBe(1);
    expect(documentListeners(added, 'mouseup')).toBe(1);

    win.document.dispatchEvent(new win.MouseEvent('mouseup', { bubbles: true }));
    expect(documentListeners(removed, 'mousemove')).toBe(1);
    expect(documentListeners(removed, 'mouseup')).toBe(1);
  });
});
