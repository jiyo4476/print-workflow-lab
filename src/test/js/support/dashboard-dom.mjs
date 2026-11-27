// Loads the dashboard into a jsdom window the way index.html does: the same scripts, in the same
// order. WebJar URLs (webjars/<name>/<version>/<path>) map to node_modules/<name>/<path>, which
// package.json pins to the same versions as pom.xml.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
const webapp = join(root, 'src', 'main', 'webapp');

const openWindows = [];

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
export function loadDashboard({ ajax, url } = {}) {
  const dom = new JSDOM('<!doctype html><div id="app"></div>', {
    url: url || 'http://localhost/',
    runScripts: 'outside-only',
  });
  const { window } = dom;
  openWindows.push(window);
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
    return { abort() {} };
  };
  ajax.calls = calls;
  return ajax;
}

/** A Backbone.ajax stand-in that fails every request like a network error. */
export function failWith(status = 0) {
  return (options) => {
    const xhr = { status, responseText: '', abort() {} };
    options.error(xhr, 'error', 'Service Unavailable');
    return xhr;
  };
}

/**
 * A Backbone.ajax stand-in that answers by URL, synchronously. `routes` maps a path (no query)
 * to {body, headers} or {status}; the longest matching path wins. Routes can change between
 * requests, so a test can make a request fail and then succeed on Retry. Every call is kept in
 * `ajax.calls` as {url, method}.
 */
export function routedAjax(routes) {
  const calls = [];
  const ajax = (options) => {
    const url = options.url;
    const path = url.split('?')[0];
    calls.push({ url, method: options.type || 'GET' });
    const match = Object.keys(routes)
      .filter((key) => path === key)
      .sort((a, b) => b.length - a.length)[0];
    const route = match === undefined ? { status: 404 } : routes[match];
    const xhr = fakeXhr(route.headers || {}, route.status ?? 200);
    if (xhr.status >= 200 && xhr.status < 300) {
      options.success(route.body, 'success', xhr);
    } else {
      options.error(xhr, 'error', 'failed');
    }
    return xhr;
  };
  ajax.calls = calls;
  return ajax;
}

function fakeXhr(headers, status) {
  return {
    status,
    getResponseHeader: (name) => headers[name] ?? null,
    abort() {},
  };
}

/**
 * A Backbone.ajax stand-in that holds every request until the test answers it. A request
 * aborted by the code under test fails with status 0 at once, as jQuery does. Use respond(body,
 * headers), fail(status) or leave it pending.
 */
export function deferredAjax() {
  const requests = [];
  const ajax = (options) => {
    const request = { url: options.url, options, aborted: false, settled: false };
    request.xhr = {
      status: 0,
      getResponseHeader: (name) => (request.headers || {})[name] ?? null,
      abort() {
        if (request.settled) return;
        request.aborted = true;
        request.settled = true;
        options.error(request.xhr, 'abort', 'abort');
      },
    };
    request.respond = (body, headers = {}) => {
      if (request.settled) return;
      request.settled = true;
      request.headers = headers;
      request.xhr.status = 200;
      options.success(body, 'success', request.xhr);
    };
    request.fail = (status) => {
      if (request.settled) return;
      request.settled = true;
      request.xhr.status = status;
      options.error(request.xhr, 'error', 'failed');
    };
    requests.push(request);
    return request.xhr;
  };
  ajax.requests = requests;
  return ajax;
}

/** Closes every window the tests opened, which stops any timers the dashboard started. */
export function closeDashboards() {
  for (const window of openWindows.splice(0)) window.close();
}
