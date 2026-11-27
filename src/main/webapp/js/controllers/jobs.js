/*
 * The jobs list and one job's detail. Controllers own the data, the requests and the timers;
 * they tell the app what happened by events ('loaded', 'error') and never touch a View.
 *
 * Every load takes a token. Starting a new load aborts the one in flight, and a callback whose
 * token is no longer current is ignored, so an old response never overwrites a newer one.
 */
(function (root) {
  'use strict';

  const { Backbone, Marionette } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  /** How often the jobs list refreshes (PRD-16 FR-3). */
  PrintLab.JOBS_POLL_MS = 5000;

  /**
   * The jobs list. {@code start()} loads it now and then every {@code pollMs}. {@code timers}
   * (default: the window's setInterval and clearInterval) can be swapped for a test clock.
   */
  PrintLab.JobsController = Marionette.MnObject.extend({
    initialize(options) {
      const o = options || {};
      this.timers = o.timers || root;
      this.pollMs = o.pollMs || PrintLab.JOBS_POLL_MS;
      this.jobs = new PrintLab.JobCollection(null, { state: o.state, limit: o.limit });
      this.token = 0;
      this.request = null;
      this.timer = null;
    },

    start() {
      this.refresh();
      this.timer = this.timers.setInterval(() => this.refresh(), this.pollMs);
    },

    /** Fetches the list. Aborts a refresh that is still in flight. */
    refresh() {
      const token = ++this.token;
      if (this.request) this.request.abort();
      this.request = this.jobs.fetch({
        success: () => {
          if (token === this.token) this.trigger('loaded', this.jobs);
        },
        error: (collection, xhr) => {
          if (token === this.token) this.trigger('error', xhr);
        },
      });
    },

    onDestroy() {
      if (this.timer !== null) this.timers.clearInterval(this.timer);
      this.timer = null;
      this.token++;
      if (this.request) this.request.abort();
      this.request = null;
    },
  });

  /**
   * One job's detail: the job and its documents, loaded together. {@code load()} runs it again,
   * which is what Retry does after a failure.
   */
  PrintLab.JobDetailController = Marionette.MnObject.extend({
    initialize(options) {
      this.jobId = options.id;
      this.token = 0;
      this.requests = [];
    },

    load() {
      const token = ++this.token;
      this.abortRequests();

      const job = new PrintLab.Job({ id: this.jobId });
      const documents = new PrintLab.JobDocumentCollection(null, { jobId: this.jobId });
      let waiting = 2;
      let failed = false;
      const settle = () => {
        waiting -= 1;
        if (token === this.token && waiting === 0 && !failed) {
          this.trigger('loaded', {
            job,
            documents,
            steps: new PrintLab.StepCollection(job.get('steps') || []),
          });
        }
      };
      const fail = (xhr) => {
        if (token !== this.token || failed) return;
        failed = true;
        this.abortRequests();
        this.trigger('error', xhr);
      };
      const options = {
        success: settle,
        error: (model, xhr) => fail(xhr),
      };
      this.requests = [job.fetch(options), documents.fetch(options)];
    },

    abortRequests() {
      this.requests.forEach((request) => request && request.abort());
      this.requests = [];
    },

    onDestroy() {
      this.token++;
      this.abortRequests();
    },
  });
})(window);
