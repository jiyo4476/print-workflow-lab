import assert from 'node:assert/strict';
import sinon from 'sinon';
import { deferredAjax, loadDashboard } from './support/dashboard-dom.mjs';

function job(id, state, extra = {}) {
  return { id, state, phase: 'PRINT', currentStep: 'write-output', documentCount: 3, ...extra };
}

function renderList(window, collection, selectedId) {
  const view = new window.PrintLab.JobsListView({ collection, selectedId });
  view.render();
  return view;
}

describe('jobs', () => {
  it('listRendersOneRowPerJobWithStateBadge', () => {
    const window = loadDashboard();
    const { JobCollection, JobsListView } = window.PrintLab;
    const jobs = new JobCollection([job('job-1', 'COMPLETE'), job('job-2', 'ERROR')]);

    const view = renderList(window, jobs);

    const rows = view.el.querySelectorAll('.job-row');
    assert.equal(rows.length, 2);
    assert.equal(rows[0].querySelector('.badge').textContent, 'COMPLETE');
    assert.ok(rows[0].querySelector('.badge-ok'));
    assert.ok(rows[1].querySelector('.badge-error'), 'a failed job is red');
    assert.equal(rows[1].querySelector('.job-link').getAttribute('href'), '#jobs/job-2');
    assert.ok(view instanceof JobsListView);
  });

  it('emptyListShowsEmptyView', () => {
    const window = loadDashboard();
    const view = renderList(window, new window.PrintLab.JobCollection());

    assert.equal(view.el.querySelectorAll('.job-row').length, 0);
    assert.match(view.el.querySelector('.empty-row').textContent, /No jobs yet/);
  });

  it('selectingARowNavigatesToJobDetail', () => {
    const window = loadDashboard();
    const jobs = new window.PrintLab.JobCollection([job('job-1', 'COMPLETE'),
      job('job-2', 'ERROR')]);
    const view = renderList(window, jobs);
    const selected = [];
    view.on('job:select', (row) => selected.push(row.model.id));

    view.el.querySelectorAll('.job-link')[1].click();

    assert.deepEqual(selected, ['job-2']);
  });

  it('refreshAbortsPreviousRequest', () => {
    const window = loadDashboard();
    const ajax = deferredAjax();
    window.Backbone.ajax = ajax;
    const clock = sinon.useFakeTimers();
    try {
      const controller = new window.PrintLab.JobsController({ timers: clock });
      const loaded = sinon.spy();
      controller.on('loaded', loaded);

      controller.refresh();
      controller.refresh();

      assert.equal(ajax.requests.length, 2);
      assert.equal(ajax.requests[0].aborted, true, 'the older request is aborted');
      assert.equal(ajax.requests[1].aborted, false);

      ajax.requests[1].respond([job('job-9', 'COMPLETE')]);
      assert.equal(loaded.callCount, 1, 'only the newest request loads the list');
      assert.equal(controller.jobs.first().id, 'job-9');
    } finally {
      clock.restore();
    }
  });

  it('refreshKeepsSelectedJob', () => {
    const window = loadDashboard();
    const ajax = deferredAjax();
    window.Backbone.ajax = ajax;
    const clock = sinon.useFakeTimers();
    try {
      const controller = new window.PrintLab.JobsController({ timers: clock });
      const view = renderList(window, controller.jobs);
      view.setSelected('job-2');
      controller.refresh();
      ajax.requests[0].respond([job('job-1', 'COMPLETE'), job('job-2', 'PROCESSING')]);

      controller.refresh();
      ajax.requests[1].respond([job('job-1', 'COMPLETE'), job('job-2', 'COMPLETE')]);

      const selected = view.el.querySelectorAll('.is-selected');
      assert.equal(selected.length, 1);
      assert.equal(selected[0].querySelector('.job-link').textContent, 'job-2');
    } finally {
      clock.restore();
    }
  });

  it('pollingStopsWhenControllerIsDestroyed', () => {
    const window = loadDashboard();
    const ajax = deferredAjax();
    window.Backbone.ajax = ajax;
    const clock = sinon.useFakeTimers();
    try {
      const controller = new window.PrintLab.JobsController({ timers: clock, pollMs: 5000 });
      controller.start();
      assert.equal(ajax.requests.length, 1);

      clock.tick(5000);
      assert.equal(ajax.requests.length, 2, 'a refresh every 5 seconds');

      controller.destroy();
      clock.tick(60000);
      assert.equal(ajax.requests.length, 2, 'no requests after teardown');
    } finally {
      clock.restore();
    }
  });
});
