/*
 * One document's structure (PRD-14 FR-6), loaded from GET api/documents/{id}/structure and turned
 * into a tree by buildStructure.
 */
(function (root) {
  'use strict';

  const { Backbone, Marionette } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  PrintLab.StructureController = Marionette.MnObject.extend({
    initialize(options) {
      this.documentId = options.documentId;
      this.token = 0;
      this.request = null;
    },

    load() {
      const token = ++this.token;
      if (this.request) this.request.abort();
      this.request = Backbone.ajax({
        url: 'api/documents/' + encodeURIComponent(this.documentId) + '/structure',
        dataType: 'json',
        success: (body) => {
          if (token === this.token) this.trigger('loaded', PrintLab.buildStructure(body));
        },
        error: (xhr) => {
          if (token === this.token) this.trigger('error', xhr);
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
