import assert from 'node:assert/strict';
import { loadDashboard, routedAjax, deferredAjax } from './support/dashboard-dom.mjs';

const job = {
  id: 'job-1',
  state: 'COMPLETE',
  phase: 'COMPLETE',
  currentStep: null,
  errorMessage: null,
  properties: { accountNumber: '1234567890' },
  documentCount: 3,
  steps: [{ name: 'split', phase: 'PREPARE', outcome: 'COMPLETED' }],
};

const documents = [
  { id: 1, jobId: 'job-1', sequence: 1, queue: 'standard', properties: { name: 'A' } },
  { id: 2, jobId: 'job-1', sequence: 2, queue: 'standard', properties: { name: 'B' } },
  { id: 3, jobId: 'job-1', sequence: 3, queue: 'exceptions', properties: { name: 'C' } },
];

function detailFor(window) {
  const { Job, StepCollection, JobDocumentCollection, JobDetailView } = window.PrintLab;
  const model = new Job(job);
  const docs = new JobDocumentCollection(documents, { jobId: 'job-1' });
  const view = new JobDetailView({
    model,
    documents: docs,
    steps: new StepCollection(job.steps),
  });
  view.render();
  return view;
}

describe('job detail', () => {
  it('switchingQueueFiltersDocumentsWithoutRefetch', () => {
    const ajax = deferredAjax();
    const window = loadDashboard({ ajax });
    window.Backbone.ajax = ajax;
    const view = detailFor(window);
    assert.equal(view.el.querySelectorAll('.document-row').length, 3, 'all documents at first');
    const requestsBefore = ajax.requests.length;

    view.el.querySelector('[data-queue="exceptions"]').click();

    const rows = view.el.querySelectorAll('.document-row');
    assert.equal(rows.length, 1);
    assert.match(rows[0].textContent, /C/);
    assert.equal(
      view.el.querySelector('[data-queue="exceptions"]').getAttribute('aria-pressed'),
      'true',
    );
    assert.equal(ajax.requests.length, requestsBefore, 'no request for a queue switch');

    view.el.querySelector('[data-queue=""]').click();
    assert.equal(view.el.querySelectorAll('.document-row').length, 3);
  });

  it('showsTheJobsPropertiesAndStepTimeline', () => {
    const window = loadDashboard();
    const view = detailFor(window);

    assert.match(view.el.querySelector('.properties dd').textContent, /1234567890/);
    assert.equal(view.el.querySelectorAll('.step-row').length, 1);
    assert.match(view.el.querySelector('.step-row').textContent, /split/);
  });

  it('failedLoadShowsErrorWithRetry', () => {
    const routes = {
      'api/health': { body: { status: 'UP' } },
      'api/jobs/job-1': { status: 503 },
      'api/jobs/job-1/documents': { body: [] },
    };
    const ajax = routedAjax(routes);
    const window = loadDashboard({ ajax, url: 'http://localhost/#jobs/job-1' });
    new window.PrintLab.DashboardApp().start();
    const main = () => window.document.querySelector('#app .shell-main');

    assert.match(main().querySelector('.error-state').textContent, /HTTP 503/);
    const retry = main().querySelector('.error-state .retry');
    assert.ok(retry, 'a Retry button is offered');

    routes['api/jobs/job-1'] = { body: job };
    const before = ajax.calls.filter((c) => c.url === 'api/jobs/job-1').length;
    retry.click();

    assert.equal(ajax.calls.filter((c) => c.url === 'api/jobs/job-1').length, before + 1);
    assert.match(main().querySelector('h2').textContent, /Job job-1/);
  });
});
