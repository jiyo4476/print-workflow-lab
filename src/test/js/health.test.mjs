import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadDashboard, respondWith, failWith } from './support/dashboard-dom.mjs';

function renderHealth(window, attrs) {
  const model = new window.PrintLab.HealthModel(attrs);
  const view = new window.PrintLab.HealthView({ model }).render();
  return { model, view };
}

test('startsAsCheckingWithPendingBadge', () => {
  const window = loadDashboard();
  const { view } = renderHealth(window);
  const badge = view.el.querySelector('.badge');
  assert.equal(badge.textContent, 'API: CHECKING');
  assert.ok(badge.classList.contains('badge-pending'));
});

test('rerendersWhenStatusChanges', () => {
  const window = loadDashboard();
  const { model, view } = renderHealth(window);
  model.set({ status: 'UP' });
  const badge = view.el.querySelector('.badge');
  assert.equal(badge.textContent, 'API: UP');
  assert.ok(badge.classList.contains('badge-ok'));
});

test('checkRequestsApiHealthWithRelativeUrl', () => {
  const ajax = respondWith({ status: 'UP', service: 'print-workflow-lab' });
  const window = loadDashboard({ ajax });
  const model = new window.PrintLab.HealthModel();
  model.check();
  assert.equal(ajax.calls.length, 1);
  assert.equal(ajax.calls[0].url, 'api/health');
  assert.equal(model.get('status'), 'UP');
});

test('failedCheckShowsUnreachableWithErrorBadge', () => {
  const window = loadDashboard({ ajax: failWith(503) });
  const { model, view } = renderHealth(window);
  model.check();
  const badge = view.el.querySelector('.badge');
  assert.equal(badge.textContent, 'API: UNREACHABLE');
  assert.ok(badge.classList.contains('badge-error'));
});

test('rendersValuesAsTextNotHtml', () => {
  const window = loadDashboard();
  const hostile = '<img src=x onerror="alert(1)">';
  const { view } = renderHealth(window, { status: hostile });
  assert.equal(view.el.querySelector('img'), null);
  assert.equal(view.el.querySelector('.badge').textContent, `API: ${hostile}`);
});
