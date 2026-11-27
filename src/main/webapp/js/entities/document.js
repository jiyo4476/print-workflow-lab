/*
 * Documents, as the API returns them. A job's documents come from GET api/jobs/{id}/documents;
 * a search across jobs comes from GET api/documents?property=&value=&limit=&offset= (PRD-14).
 */
(function (root) {
  'use strict';

  const { Backbone } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  /** The page size of a document search (PRD-14 FR-4 and the dashboard's paging). */
  PrintLab.SEARCH_PAGE_SIZE = 50;

  /** The most documents the job detail asks for at once (the API's cap). */
  PrintLab.JOB_DOCUMENT_LIMIT = 500;

  PrintLab.Document = Backbone.Model.extend({
    /** The document's file, served by the API. Relative, so the context path doesn't matter. */
    fileUrl() {
      return 'api/documents/' + encodeURIComponent(this.id) + '/file';
    },

    structureHref() {
      return '#documents/' + encodeURIComponent(this.id) + '/structure';
    },
  });

  /** The documents of one job, in sequence order, optionally filtered by the view. */
  PrintLab.JobDocumentCollection = Backbone.Collection.extend({
    model: PrintLab.Document,

    initialize(models, options) {
      this.jobId = options.jobId;
    },

    url() {
      return 'api/jobs/' + encodeURIComponent(this.jobId) + '/documents?limit='
        + PrintLab.JOB_DOCUMENT_LIMIT;
    },
  });

  /**
   * A search across jobs. {@code criteria} is {property, value, offset}, set by the controller
   * before each fetch; the page size is {@link PrintLab.SEARCH_PAGE_SIZE}.
   */
  PrintLab.DocumentCollection = Backbone.Collection.extend({
    model: PrintLab.Document,

    url() {
      const c = this.criteria || {};
      const params = new URLSearchParams();
      params.set('property', c.property || '');
      params.set('value', c.value || '');
      params.set('limit', String(PrintLab.SEARCH_PAGE_SIZE));
      params.set('offset', String(c.offset || 0));
      return 'api/documents?' + params.toString();
    },
  });
})(window);
