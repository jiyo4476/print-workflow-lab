/*
 * Document search: a form (property and value), a status line ("Showing 1–50 of N", or a
 * message), Previous / Next paging, and the results table. The view emits 'search:submit' and
 * 'page:change'; the app turns each into a new #search URL, so the search runs from the route.
 */
(function (root) {
  'use strict';

  const { Backbone, Marionette, _ } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  const PROPERTY_LABELS = {
    accountNumber: 'Account number',
    name: 'Name',
    zip: 'Zip',
  };

  /**
   * The page model: {criteria, total, offset, limit, error}. The view re-renders when it
   * changes, so the summary and the buttons always match the last response.
   */
  PrintLab.SearchPage = Backbone.Model.extend({
    defaults: {
      criteria: null,
      total: null,
      offset: 0,
      limit: PrintLab.SEARCH_PAGE_SIZE,
      error: null,
    },
  });

  PrintLab.SearchView = Marionette.View.extend({
    className: 'search',
    template: _.template(
      '<form class="search-form">' +
        '<label>Search by ' +
          '<select name="property">' +
          '<% _.each(properties, function (p) { %>' +
          '<option value="<%- p.value %>"<% if (p.selected) { %> selected<% } %>><%- p.label %></option>' +
          '<% }); %>' +
          '</select>' +
        '</label>' +
        '<label>Value <input name="value" type="search" value="<%- value %>"></label>' +
        '<button type="submit">Search</button>' +
      '</form>' +
      '<% if (error) { %>' +
        '<div class="error-state" role="alert"><p><%- error %></p>' +
        '<button type="button" class="retry">Retry</button></div>' +
      '<% } else if (summary) { %>' +
        '<p class="search-summary" aria-live="polite"><%- summary %></p>' +
        '<div class="paging">' +
          '<button type="button" class="page-prev"<% if (!hasPrevious) { %> disabled<% } %>>Previous</button>' +
          '<button type="button" class="page-next"<% if (!hasNext) { %> disabled<% } %>>Next</button>' +
        '</div>' +
      '<% } %>' +
      '<div class="region-results"></div>'
    ),
    regions: {
      results: '.region-results',
    },
    events: {
      'submit .search-form': 'submitSearch',
      'click .page-prev': 'previousPage',
      'click .page-next': 'nextPage',
      'click .retry': 'retrySearch',
    },
    modelEvents: {
      change: 'render',
    },

    initialize(options) {
      this.documents = this.getOption('documents');
    },

    templateContext() {
      const page = this.model.toJSON();
      const criteria = page.criteria || {};
      const properties = PrintLab.SEARCH_PROPERTIES.map((p) => ({
        value: p,
        label: PROPERTY_LABELS[p] || p,
        selected: p === (criteria.property || PrintLab.SEARCH_PROPERTIES[0]),
      }));
      const hasResults = page.total !== null && !page.error;
      const first = page.total ? page.offset + 1 : 0;
      const last = Math.min(page.offset + page.limit, page.total || 0);
      return {
        properties,
        value: criteria.value || '',
        error: page.error,
        summary: hasResults
          ? (page.total === 0 ? 'No documents match.' :
            'Showing ' + first + '–' + last + ' of ' + page.total + '.')
          : '',
        hasPrevious: page.offset > 0,
        hasNext: page.offset + page.limit < (page.total || 0),
      };
    },

    onRender() {
      if (!this.model.get('error') && this.documents) {
        const searched = Boolean(this.model.get('criteria'));
        this.showChildView('results', new PrintLab.DocumentListView({
          collection: this.documents,
          emptyMessage: searched
            ? 'No documents match this search.'
            : 'Search for a document by account, name or zip.',
        }));
      }
    },

    submitSearch(event) {
      event.preventDefault();
      const form = event.currentTarget;
      this.triggerMethod('search:submit', {
        property: form.elements.property.value,
        value: form.elements.value.value.trim(),
      });
    },

    previousPage() {
      const page = this.model.toJSON();
      this.triggerMethod('page:change', Math.max(0, page.offset - page.limit));
    },

    nextPage() {
      const page = this.model.toJSON();
      this.triggerMethod('page:change', page.offset + page.limit);
    },

    retrySearch() {
      this.triggerMethod('retry');
    },
  });
})(window);
