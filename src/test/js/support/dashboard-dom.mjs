// Loads the dashboard into a jsdom window the way index.html does: the same scripts, in the same
// order. WebJar URLs (webjars/<name>/<version>/<path>) map to node_modules/<name>/<path>, which
// package.json pins to the same versions as pom.xml.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const webapp = join(root, 'src', 'main', 'webapp');

export const indexHtml = readFileSync(join(webapp, 'index.html'), 'utf8');

/** Script srcs from index.html, in order. */
export function scriptSources() {
  return [...indexHtml.matchAll(/<script src="([^"]+)"><\/script>/g)].map((m) => m[1]);
}

/** Parses a WebJar src into { name, version, path }, or null for an app script. */
export function parseWebJar(src) {
  const m = src.match(/^webjars\/([^/]+)\/([^/]+)\/(.+)$/);
  return m ? { name: m[1], version: m[2], path: m[3] } : null;
}

function fileFor(src) {
  const webJar = parseWebJar(src);
  return webJar
    ? join(root, 'node_modules', webJar.name, webJar.path)
    : join(webapp, src);
}

/**
 * A window with the libraries and app scripts loaded, but not started (main.js is skipped).
 * Pass `ajax` to replace Backbone.ajax, so no test touches the network.
 */
export function loadDashboard({ ajax } = {}) {
  const dom = new JSDOM('<!doctype html><div id="app"></div>', {
    url: 'http://localhost/',
    runScripts: 'outside-only',
  });
  const { window } = dom;
  for (const src of scriptSources()) {
    if (src === 'js/main.js') continue;
    window.eval(readFileSync(fileFor(src), 'utf8'));
  }
  if (ajax) window.Backbone.ajax = ajax;
  return window;
}

/** A Backbone.ajax stand-in that answers every request with `body`, synchronously. */
export function respondWith(body) {
  const calls = [];
  const ajax = (options) => {
    calls.push(options);
    options.success(body);
    return {};
  };
  ajax.calls = calls;
  return ajax;
}

/** A Backbone.ajax stand-in that fails every request like a network error. */
export function failWith(status = 0) {
  return (options) => {
    const xhr = { status, responseText: '' };
    options.error(xhr, 'error', 'Service Unavailable');
    return xhr;
  };
}
