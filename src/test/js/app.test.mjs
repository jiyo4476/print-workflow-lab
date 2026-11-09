import assert from 'node:assert/strict';
import { loadDashboard, respondWith, failWith } from './support/dashboard-dom.mjs';

function start(window) {
  const app = new window.PrintLab.DashboardApp();
  app.start();
  return app;
}

describe('DashboardApp', () => {
  it('startShowsShellWithHeaderAndPlaceholder', () => {
    const window = loadDashboard({ ajax: respondWith({ status: 'UP' }) });
    start(window);
    const root = window.document.getElementById('app');
    assert.equal(root.querySelector('h1').textContent, 'Print Workflow Lab');
    assert.match(root.querySelector('.shell-main').textContent, /PR 16/);
  });

  it('startChecksHealthAndShowsUp', () => {
    const ajax = respondWith({ status: 'UP' });
    const window = loadDashboard({ ajax });
    start(window);
    assert.equal(ajax.calls.length, 1);
    const badge = window.document.querySelector('#app .shell-health .badge');
    assert.equal(badge.textContent, 'API: UP');
  });

  it('unreachableApiStillShowsShell', () => {
    const window = loadDashboard({ ajax: failWith(0) });
    start(window);
    const doc = window.document;
    assert.equal(doc.querySelector('#app h1').textContent, 'Print Workflow Lab');
    assert.equal(doc.querySelector('#app .shell-health .badge').textContent, 'API: UNREACHABLE');
  });

  it('applicationRegionHoldsTheShellView', () => {
    const window = loadDashboard({ ajax: respondWith({ status: 'UP' }) });
    const app = start(window);
    assert.ok(app.getView() instanceof window.PrintLab.ShellView);
    assert.ok(app.getView().getChildView('health') instanceof window.PrintLab.HealthView);
  });
});
