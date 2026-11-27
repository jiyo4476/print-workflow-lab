import assert from 'node:assert/strict';
import { deferredAjax, loadDashboard, routedAjax } from './support/dashboard-dom.mjs';

const hit = (id, name = 'Smith') => ({
  id,
  jobId: 'job-1',
  sequence: id,
  queue: 'standard',
  properties: { accountNumber: '100', name, zip: '12345' },
});

function startAt(url, routes) {
  const ajax = routedAjax(routes);
  const window = loadDashboard({ ajax, url });
  new window.PrintLab.DashboardApp().start();
  return { window, ajax, main: () => window.document.querySelector('#app .shell-main') };
}

describe('search', () => {
  it('newSearchAbortsPreviousRequest', () => {
    const ajax = deferredAjax();
    const window = loadDashboard({ ajax });
    window.Backbone.ajax = ajax;
    const controller = new window.PrintLab.SearchController();
    const loaded = [];
    controller.on('loaded', (result) => loaded.push(result.criteria.value));

    controller.search({ property: 'name', value: 'Smith', offset: 0 });
    controller.search({ property: 'name', value: 'Jones', offset: 0 });

    assert.equal(ajax.requests[0].aborted, true);
    ajax.requests[0].respond([hit(1)], { 'X-Total-Count': '1' });
    assert.deepEqual(loaded, [], 'the aborted search never loads');
    ajax.requests[1].respond([hit(2, 'Jones')], { 'X-Total-Count': '1' });
    assert.deepEqual(loaded, ['Jones']);
  });

  it('encodesQueryValues', () => {
    const ajax = deferredAjax();
    const window = loadDashboard({ ajax });
    window.Backbone.ajax = ajax;
    const controller = new window.PrintLab.SearchController();

    controller.search({ property: 'name', value: "O'Brien & Sons", offset: 0 });

    assert.match(ajax.requests[0].url, /value=O%27Brien\+%26\+Sons/);
    assert.match(ajax.requests[0].url, /property=name/);
  });

  it('emptyResultShowsMessage', () => {
    const { main } = startAt('http://localhost/#search?property=zip&value=00000', {
      'api/health': { body: { status: 'UP' } },
      'api/documents': { body: [], headers: { 'X-Total-Count': '0' } },
    });

    assert.match(main().querySelector('.search-summary').textContent, /No documents match/);
    assert.equal(main().querySelectorAll('.document-row').length, 0);
  });

  it('showsRangeAndTotalFromHeader', () => {
    const { main } = startAt('http://localhost/#search?property=name&value=Smith&offset=50', {
      'api/health': { body: { status: 'UP' } },
      'api/documents': { body: [hit(51), hit(52)], headers: { 'X-Total-Count': '120' } },
    });

    assert.equal(main().querySelector('.search-summary').textContent,
      'Showing 51–100 of 120.');
    assert.equal(main().querySelectorAll('.document-row').length, 2);
  });

  it('nextAndPreviousChangeOffset', () => {
    const routes = {
      'api/health': { body: { status: 'UP' } },
      'api/documents': { body: [hit(1)], headers: { 'X-Total-Count': '120' } },
    };
    const { window, ajax, main } = startAt('http://localhost/#search?property=name&value=Smith',
      routes);
    const offsets = () => ajax.calls.filter((c) => c.url.startsWith('api/documents'))
      .map((c) => new URL(c.url, 'http://x/').searchParams.get('offset'));

    main().querySelector('.page-next').click();
    assert.match(window.location.hash, /offset=50/);
    main().querySelector('.page-next').click();
    assert.match(window.location.hash, /offset=100/);
    main().querySelector('.page-prev').click();

    assert.deepEqual(offsets(), ['0', '50', '100', '50']);
  });

  it('pagingButtonsDisabledAtEnds', () => {
    const first = startAt('http://localhost/#search?property=name&value=Smith', {
      'api/health': { body: { status: 'UP' } },
      'api/documents': { body: [hit(1)], headers: { 'X-Total-Count': '120' } },
    });
    assert.equal(first.main().querySelector('.page-prev').disabled, true);
    assert.equal(first.main().querySelector('.page-next').disabled, false);

    const last = startAt('http://localhost/#search?property=name&value=Smith&offset=100', {
      'api/health': { body: { status: 'UP' } },
      'api/documents': { body: [hit(101)], headers: { 'X-Total-Count': '120' } },
    });
    assert.equal(last.main().querySelector('.page-prev').disabled, false);
    assert.equal(last.main().querySelector('.page-next').disabled, true);
  });

  it('offsetInUrlRestoresPage', () => {
    const { ajax, main } = startAt('http://localhost/#search?property=zip&value=12345&offset=50', {
      'api/health': { body: { status: 'UP' } },
      'api/documents': { body: [hit(51)], headers: { 'X-Total-Count': '60' } },
    });

    const documentCalls = ajax.calls.filter((c) => c.url.startsWith('api/documents'));
    assert.equal(documentCalls.length, 1);
    assert.match(documentCalls[0].url, /property=zip/);
    assert.match(documentCalls[0].url, /offset=50/);
    assert.match(main().querySelector('.search-summary').textContent, /Showing 51–60 of 60/);
  });

  it('submittingTheFormSearchesFromTheRoute', () => {
    const { window, main } = startAt('http://localhost/#search', {
      'api/health': { body: { status: 'UP' } },
      'api/documents': { body: [hit(1)], headers: { 'X-Total-Count': '1' } },
    });
    const form = main().querySelector('form.search-form');
    form.elements.property.value = 'accountNumber';
    form.elements.value.value = '100';

    form.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));

    assert.match(window.location.hash, /property=accountNumber/);
    assert.match(window.location.hash, /value=100/);
    assert.equal(main().querySelectorAll('.document-row').length, 1);
  });
});
