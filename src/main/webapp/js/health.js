/*
 * API health: a Backbone Model for GET api/health and a Marionette View showing it as a badge.
 * Templates use <%- %> (escaped) for every value — data is rendered as text, never as HTML.
 */
(function (root) {
  'use strict';

  const { Backbone, Marionette, _ } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  const BADGES = { CHECKING: 'pending', UP: 'ok' };

  PrintLab.HealthModel = Backbone.Model.extend({
    // Relative, so it works whatever context path Tomcat deploys the WAR under.
    url: 'api/health',
    defaults: { status: 'CHECKING' },

    /** Fetches status; any failure (network, 5xx, bad JSON) shows as UNREACHABLE. */
    check() {
      return this.fetch({
        error: () => this.set({ status: 'UNREACHABLE' }),
      });
    },
  });

  PrintLab.HealthView = Marionette.View.extend({
    tagName: 'span',
    className: 'health',
    template: _.template('<span class="badge badge-<%- badge %>">API: <%- status %></span>'),
    templateContext() {
      return { badge: BADGES[this.model.get('status')] || 'error' };
    },
    modelEvents: {
      'change:status': 'render',
    },
  });
})(window);
