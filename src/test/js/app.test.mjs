import assert from 'node:assert/strict';
import { loadDashboard, routedAjax, failWith } from './support/dashboard-dom.mjs';

function start(window) {
  const app = new window.PrintLab.DashboardApp();
  app.start();
  return app;
}

const healthy = {
  'api/health': { body: { status: 'UP' } },
  'api/jobs': { body: [] },
};

describe('DashboardApp', () => {
  it('startShowsShellWithHeaderNavigationAndJobs', () => {
    const window = loadDashboard({ ajax: routedAjax(healthy) });
    start(window);
    const root = window.document.getElementById('app');
    assert.equal(root.querySelector('h1').textContent, 'Print Workflow Lab');
    const links = [...root.querySelectorAll('.shell-nav a')].map((a) => a.getAttribute('href'));
    assert.deepEqual(links, ['#jobs', '#search']);
    assert.ok(root.querySelector('.shell-main .jobs-list'), 'the default route is the jobs list');
  });

  it('startChecksHealthAndShowsUp', () => {
    const ajax = routedAjax(healthy);
    const window = loadDashboard({ ajax });
    start(window);
    const healthCalls = ajax.calls.filter((c) => c.url === 'api/health');
    assert.equal(healthCalls.length, 1);
    const badge = window.document.querySelector('#app .shell-health .badge');
    assert.equal(badge.textContent, 'API: UP');
  });

  it('unreachableApiStillShowsShell', () => {
    const window = loadDashboard({ ajax: failWith(0) });
    start(window);
    const doc = window.document;
    assert.equal(doc.querySelector('#app h1').textContent, 'Print Workflow Lab');
    assert.equal(doc.querySelector('#app .shell-health .badge').textContent, 'API: UNREACHABLE');
    assert.ok(doc.querySelector('#app .shell-main .error-state'), 'the jobs list shows its error');
  });

  it('applicationRegionHoldsTheShellView', () => {
    const window = loadDashboard({ ajax: routedAjax(healthy) });
    const app = start(window);
    assert.ok(app.getView() instanceof window.PrintLab.ShellView);
    assert.ok(app.getView().getChildView('health') instanceof window.PrintLab.HealthView);
  });

  it('leavingTheJobsListStopsItsPolling', () => {
    const ajax = routedAjax(healthy);
    const window = loadDashboard({ ajax });
    const app = start(window);
    const controller = app.controller;
    assert.ok(controller instanceof window.PrintLab.JobsController);

    app.router.navigate('search', { trigger: true });

    assert.equal(controller.timer, null, 'the timer is cleared when the feature ends');
  });
});
