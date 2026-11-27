/*
 * Jobs, as the API returns them: GET api/jobs (the list), GET api/jobs/{id} (one job, with its
 * properties and step history) and the steps shown in its timeline.
 */
(function (root) {
  'use strict';

  const { Backbone } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  /** One job. {@code urlRoot} makes it GET api/jobs/{id}. */
  PrintLab.Job = Backbone.Model.extend({
    urlRoot: 'api/jobs',
  });

  /**
   * The jobs list, newest first. {@code state} (a JobState name) and {@code limit} go in the
   * query string; the server caps the limit.
   */
  PrintLab.JobCollection = Backbone.Collection.extend({
    model: PrintLab.Job,

    initialize(models, options) {
      const o = options || {};
      this.state = o.state || null;
      this.limit = o.limit || 50;
    },

    url() {
      const params = [];
      if (this.state) params.push('state=' + encodeURIComponent(this.state));
      params.push('limit=' + encodeURIComponent(this.limit));
      return 'api/jobs?' + params.join('&');
    },
  });

  /** One step of a job's history: name, phase, outcome and times, as the API gives them. */
  PrintLab.Step = Backbone.Model.extend({});

  PrintLab.StepCollection = Backbone.Collection.extend({
    model: PrintLab.Step,
  });
})(window);
