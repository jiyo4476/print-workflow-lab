/*
 * Hash routes (PRD-16 FR-1). Each handler only calls an app method: the app picks the controller
 * and the View. Backbone decodes the :id parameters itself.
 */
(function (root) {
  'use strict';

  const { Backbone } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  PrintLab.DashboardRouter = Backbone.Router.extend({
    routes: {
      '': 'jobs',
      jobs: 'jobs',
      'jobs/:id': 'job',
      'search(?*query)': 'search',
      'documents/:id/structure': 'structure',
      '*unknown': 'jobs',
    },

    initialize(options) {
      this.app = options.app;
    },

    jobs() {
      this.app.showJobs();
    },

    job(id) {
      this.app.showJob(id);
    },

    search(query) {
      this.app.showSearch(query);
    },

    structure(id) {
      this.app.showStructure(id);
    },
  });
})(window);
