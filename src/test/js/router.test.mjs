import assert from 'node:assert/strict';
import { loadDashboard, routedAjax } from './support/dashboard-dom.mjs';

const routes = {
  'api/health': { body: { status: 'UP' } },
  'api/jobs': { body: [] },
  'api/jobs/job-1': { body: { id: 'job-1', state: 'COMPLETE', properties: {}, steps: [] } },
  'api/jobs/job-1/documents': { body: [] },
  'api/documents/7/structure': { body: { format: 'afp', fields: [] } },
};

describe('router', () => {
  it('eachRouteShowsItsView', () => {
    const window = loadDashboard({ ajax: routedAjax(routes) });
    const app = new window.PrintLab.DashboardApp();
    app.start();
    const main = () => window.document.querySelector('#app .shell-main');

    app.router.navigate('jobs', { trigger: true });
    assert.ok(main().querySelector('.jobs-list'), '#jobs shows the list');

    app.router.navigate('jobs/job-1', { trigger: true });
    assert.ok(main().querySelector('.job-detail'), '#jobs/:id shows the job detail');

    app.router.navigate('search', { trigger: true });
    assert.ok(main().querySelector('.search'), '#search shows the search form');

    app.router.navigate('documents/7/structure', { trigger: true });
    assert.ok(main().querySelector('.structure-page'), 'the structure route shows the tree');
  });

  it('unknownRouteShowsJobs', () => {
    const window = loadDashboard({ ajax: routedAjax(routes), url: 'http://localhost/#nope/x' });
    new window.PrintLab.DashboardApp().start();

    assert.ok(window.document.querySelector('#app .shell-main .jobs-list'));
  });
});

describe('focus', () => {
  it('navigationMovesFocusToTheNewPage', () => {
    const window = loadDashboard({ ajax: routedAjax({
      'api/health': { body: { status: 'UP' } },
      'api/jobs': { body: [] },
    }) });
    const app = new window.PrintLab.DashboardApp();
    app.start();

    app.router.navigate('search', { trigger: true });

    assert.equal(window.document.activeElement.className, 'shell-main');
  });
});
