import assert from 'node:assert/strict';
import { loadDashboard, routedAjax } from './support/dashboard-dom.mjs';

// Structured fields in order: a document (BDT..EDT) holding a group (BNG..ENG) holding a page
// (BPG..EPG) with one PGD field. The middle byte of each type id is A8 for a Begin, A9 for an End.
const afpFields = [
  { type: 'BDT', typeId: 'D3A8A8', offset: 0, dataLength: 3 },
  { type: 'BNG', typeId: 'D3A8AD', offset: 11, dataLength: 4 },
  { type: 'BPG', typeId: 'D3A8AF', offset: 25, dataLength: 4 },
  { type: 'PGD', typeId: 'D3A6AF', offset: 39, dataLength: 12 },
  { type: 'EPG', typeId: 'D3A9AF', offset: 61, dataLength: 0 },
  { type: 'ENG', typeId: 'D3A9AD', offset: 71, dataLength: 0 },
  { type: 'EDT', typeId: 'D3A9A8', offset: 81, dataLength: 0 },
];

describe('structure', () => {
  it('rendersNestedAfpGroupsAndPdfObjects', () => {
    const window = loadDashboard();
    const { roots } = window.PrintLab.buildStructure({
      format: 'afp', fields: afpFields, truncated: false,
    });

    assert.equal(roots.length, 1);
    const document = roots.first();
    assert.equal(document.get('label'), 'BDT');
    const group = document.children.first();
    assert.equal(group.get('label'), 'BNG');
    const page = group.children.first();
    assert.equal(page.get('label'), 'BPG');
    assert.equal(page.children.first().get('label'), 'PGD');
    assert.equal(group.children.length, 1, 'the End fields close their groups');

    const pdf = window.PrintLab.buildStructure({
      format: 'pdf',
      pages: [{ number: 1, operators: [
        { offset: 0, operator: 'BT', operands: 0 },
        { offset: 5, operator: 'Tf', operands: 2 },
      ] }],
      truncated: true,
    });
    assert.equal(pdf.truncated, true);
    const pdfPage = pdf.roots.first();
    assert.equal(pdfPage.get('label'), 'Page 1');
    // Spread into this realm's array: pluck returns one from the window, which strict equal rejects.
    assert.deepEqual([...pdfPage.children.pluck('label')], ['BT', 'Tf']);
  });

  it('collapsingANodeHidesItsChildren', () => {
    const window = loadDashboard();
    const { roots } = window.PrintLab.buildStructure({ format: 'afp', fields: afpFields });
    const view = new window.PrintLab.StructureView({ collection: roots });
    view.render();
    assert.equal(view.el.querySelectorAll('.node').length, 4, 'every node shows when expanded');

    view.el.querySelector('.toggle').click();

    assert.equal(view.el.querySelectorAll('.node').length, 1, 'only the document is left');
    assert.equal(view.el.querySelector('.toggle').getAttribute('aria-expanded'), 'false');

    view.el.querySelector('.toggle').click();
    assert.equal(view.el.querySelectorAll('.node').length, 4, 'expanding shows them again');
  });

  it('structureViewLoadsTheEndpointAndShowsTheTree', () => {
    const ajax = routedAjax({
      'api/health': { body: { status: 'UP' } },
      'api/documents/7/structure': { body: { format: 'afp', fields: afpFields, truncated: true } },
    });
    const window = loadDashboard({ ajax, url: 'http://localhost/#documents/7/structure' });
    new window.PrintLab.DashboardApp().start();

    const main = window.document.querySelector('#app .shell-main');
    assert.match(main.querySelector('h2').textContent, /document 7/);
    assert.equal(main.querySelectorAll('.node').length, 4);
    assert.match(main.querySelector('.muted').textContent, /Only the first items/);
  });
});
