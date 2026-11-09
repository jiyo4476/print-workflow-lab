// The browser loads libraries from WebJars (pom.xml) via index.html; the tests load them from
// npm (package.json). If the three ever disagree, the tests would pass against a different
// version than users get — so fail loudly instead.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { scriptSources, parseWebJar } from './support/dashboard-dom.mjs';

const pkg = JSON.parse(readFileSync(new URL('../../../package.json', import.meta.url), 'utf8'));
const pom = readFileSync(new URL('../../../pom.xml', import.meta.url), 'utf8');

const POM_PROPERTY = {
  jquery: 'jquery.version',
  underscore: 'underscore.version',
  backbone: 'backbone.version',
  'backbone.radio': 'backbone.radio.version',
  'backbone.marionette': 'marionette.version',
};

const webJars = scriptSources().map(parseWebJar).filter(Boolean);

test('indexHtmlLoadsEveryPinnedLibrary', () => {
  assert.deepEqual(webJars.map((w) => w.name).sort(), Object.keys(POM_PROPERTY).sort());
});

for (const { name, version } of webJars) {
  test(`${name} version matches in index.html, pom.xml and package.json`, () => {
    const prop = POM_PROPERTY[name].replace(/\./g, '\\.');
    const pomVersion = pom.match(new RegExp(`<${prop}>([^<]+)</${prop}>`))?.[1];
    assert.equal(pomVersion, version, `pom.xml ${POM_PROPERTY[name]}`);
    assert.equal(pkg.devDependencies[name], version, `package.json devDependencies.${name}`);
  });
}

test('marionetteIsVersion4', () => {
  assert.match(pkg.devDependencies['backbone.marionette'], /^4\./);
});
