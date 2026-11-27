// Every value in a template must go through <%- %> (escaped). <%= %> inserts raw HTML, which
// would let a scraped value such as a customer name inject markup into the dashboard.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadDashboard } from './support/dashboard-dom.mjs';

const jsDir = fileURLToPath(new URL('../../main/webapp/js/', import.meta.url));

function jsFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return jsFiles(path);
    return entry.name.endsWith('.js') ? [path] : [];
  });
}

// A value that would run script if it reached innerHTML.
const HOSTILE = '<img src=x onerror="window.pwned=1">';

describe('template safety', () => {
  it('noUnescapedTemplateTagsInSource', () => {
    const files = jsFiles(jsDir);
    assert.ok(files.length > 0, 'expected dashboard sources under src/main/webapp/js');
    const offenders = files.filter((file) => readFileSync(file, 'utf8').includes('<%='));
    assert.deepEqual(offenders, [], 'use <%- %> instead of <%= %>');
  });

  it('rendersValuesAsTextNotHtml', () => {
    const window = loadDashboard();
    const { JobCollection, JobsListView, Job, StepCollection, JobDetailView,
      JobDocumentCollection, DocumentsView, StructureView, ErrorView } = window.PrintLab;
    const job = new Job({
      id: HOSTILE,
      state: HOSTILE,
      phase: HOSTILE,
      currentStep: HOSTILE,
      errorMessage: HOSTILE,
      properties: { name: HOSTILE },
      steps: [{ name: HOSTILE, outcome: HOSTILE }],
    });
    const docs = new JobDocumentCollection([{ id: 1, jobId: HOSTILE, queue: HOSTILE,
      properties: { name: HOSTILE, accountNumber: HOSTILE, zip: HOSTILE } }],
    { jobId: 'job-1' });

    const views = [
      new JobsListView({ collection: new JobCollection([job]) }),
      new JobDetailView({ model: job, documents: docs, steps: new StepCollection(job.get('steps')) }),
      new DocumentsView({ collection: docs }),
      new StructureView({ collection: new window.PrintLab.StructureNodes([
        { label: HOSTILE, detail: HOSTILE }]) }),
      new ErrorView({ message: HOSTILE }),
    ];
    for (const view of views) {
      view.render();
      assert.equal(view.el.querySelector('img'), null, view.constructor.name);
      assert.equal(window.pwned, undefined, view.constructor.name);
    }
    assert.match(views[0].el.textContent, /<img src=x/);
  });
});
