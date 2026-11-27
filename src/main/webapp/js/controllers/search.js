/*
 * Document search. Each search (one page of one search) aborts the request before it. The total
 * comes from the X-Total-Count header, which is read from the response's xhr, so the request
 * goes through Backbone.ajax rather than collection.fetch (fetch sets its xhr only after the
 * request is started, too late for a response that arrives at once).
 */
(function (root) {
  'use strict';

  const { Backbone, Marionette } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  /** The properties a search can match on (the statement layout's index fields). */
  PrintLab.SEARCH_PROPERTIES = ['accountNumber', 'name', 'zip'];

  PrintLab.SearchController = Marionette.MnObject.extend({
    initialize() {
      this.documents = new PrintLab.DocumentCollection();
      this.token = 0;
      this.request = null;
    },

    /** Runs one page of a search: {@code criteria} is {property, value, offset}. */
    search(criteria) {
      const token = ++this.token;
      if (this.request) this.request.abort();
      this.documents.criteria = criteria;
      this.request = Backbone.ajax({
        url: this.documents.url(),
        dataType: 'json',
        success: (body, status, xhr) => {
          if (token !== this.token) return;
          this.documents.reset(body);
          const header = xhr && xhr.getResponseHeader('X-Total-Count');
          this.trigger('loaded', {
            criteria,
            total: Number(header) || 0,
            offset: criteria.offset || 0,
          });
        },
        error: (xhr) => {
          if (token === this.token) this.trigger('error', xhr, criteria);
        },
      });
    },

    onDestroy() {
      this.token++;
      if (this.request) this.request.abort();
      this.request = null;
    },
  });
})(window);
