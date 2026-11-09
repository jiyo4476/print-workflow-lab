// Every value in a template must go through <%- %> (escaped). <%= %> inserts raw HTML, which
// would let a scraped value such as a customer name inject markup into the dashboard.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const jsDir = fileURLToPath(new URL('../../main/webapp/js/', import.meta.url));

function jsFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return jsFiles(path);
    return entry.name.endsWith('.js') ? [path] : [];
  });
}

test('noUnescapedTemplateTagsInSource', () => {
  const files = jsFiles(jsDir);
  assert.ok(files.length > 0, 'expected dashboard sources under src/main/webapp/js');
  const offenders = files.filter((file) => readFileSync(file, 'utf8').includes('<%='));
  assert.deepEqual(offenders, [], 'use <%- %> instead of <%= %>');
});
