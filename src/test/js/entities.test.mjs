import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadDashboard, routedAjax } from './support/dashboard-dom.mjs';

// The job the API returns (PRD-14 NFR-2): the dashboard's side of that contract.
const fixtureJob = JSON.parse(
  readFileSync(new URL('../resources/fixtures/api/job.json', import.meta.url), 'utf8'),
);

describe('entities', () => {
  it('jobCollectionUrlIncludesStateAndLimit', () => {
    const window = loadDashboard();
    const { JobCollection } = window.PrintLab;
    assert.equal(new JobCollection(null, { state: 'ERROR', limit: 20 }).url(),
      'api/jobs?state=ERROR&limit=20');
    assert.equal(new JobCollection().url(), 'api/jobs?limit=50');
  });

  it('parsesFixtureJobJsonIntoModels', () => {
    const window = loadDashboard({ ajax: routedAjax({ 'api/jobs': { body: [fixtureJob] } }) });
    const jobs = new window.PrintLab.JobCollection();

    jobs.fetch();

    assert.equal(jobs.length, 1);
    const job = jobs.first();
    assert.equal(job.id, 'job-1');
    assert.equal(job.get('state'), 'COMPLETE');
    assert.equal(job.get('documentCount'), 2);
    assert.equal(job.get('properties').accountNumber, '1234567890');
    assert.equal(job.get('steps')[0].name, 'split');
  });

  it('jobModelFetchesItsOwnUrl', () => {
    const ajax = routedAjax({ 'api/jobs/job-1': { body: fixtureJob } });
    const window = loadDashboard({ ajax });
    const job = new window.PrintLab.Job({ id: 'job-1' });

    job.fetch();

    assert.equal(ajax.calls[0].url, 'api/jobs/job-1');
    assert.equal(job.get('state'), 'COMPLETE');
  });

  it('searchUrlEncodesTheCriteriaAndPage', () => {
    const window = loadDashboard();
    const documents = new window.PrintLab.DocumentCollection();
    documents.criteria = { property: 'name', value: "O'Brien & Sons", offset: 50 };

    assert.equal(documents.url(),
      'api/documents?property=name&value=O%27Brien+%26+Sons&limit=50&offset=50');
  });

  it('jobDocumentsUrlIsScopedToTheJob', () => {
    const window = loadDashboard();
    const docs = new window.PrintLab.JobDocumentCollection(null, { jobId: 'job 1' });

    assert.equal(docs.url(), 'api/jobs/job%201/documents?limit=500');
  });

  it('documentLinksPointAtTheFileAndTheStructure', () => {
    const window = loadDashboard();
    const doc = new window.PrintLab.Document({ id: 7 });

    assert.equal(doc.fileUrl(), 'api/documents/7/file');
    assert.equal(doc.structureHref(), '#documents/7/structure');
  });
});
