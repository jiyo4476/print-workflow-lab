/*
 * Documents: one row per document (file and structure links), a plain table for search results,
 * and a table with queue tabs for a job. Switching a tab filters the rows that are already
 * loaded; it does not fetch again.
 */
(function (root) {
  'use strict';

  const { Marionette, _ } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  PrintLab.DocumentRowView = Marionette.View.extend({
    tagName: 'tr',
    className: 'document-row',
    template: _.template(
      '<td><a href="<%- fileHref %>" target="_blank" rel="noopener">#<%- id %></a></td>' +
      '<td><a href="<%- jobHref %>"><%- jobId %></a></td>' +
      '<td><%- queue %></td>' +
      '<td><%- accountNumber %></td>' +
      '<td><%- name %></td>' +
      '<td><%- zip %></td>' +
      '<td><a href="<%- structureHref %>">Structure</a></td>'
    ),
    templateContext() {
      const doc = this.model;
      const props = doc.get('properties') || {};
      return {
        id: doc.id,
        fileHref: doc.fileUrl(),
        structureHref: doc.structureHref(),
        jobId: doc.get('jobId') || '',
        jobHref: '#jobs/' + encodeURIComponent(doc.get('jobId') || ''),
        queue: doc.get('queue') || '',
        accountNumber: props.accountNumber || '',
        name: props.name || '',
        zip: props.zip || '',
      };
    },
  });

  /** Rows of documents, with a message when there are none. */
  PrintLab.DocumentListView = Marionette.CollectionView.extend({
    className: 'document-list',
    template: _.template(
      '<table class="data-table">' +
        '<thead><tr><th>Document</th><th>Job</th><th>Queue</th><th>Account</th>' +
        '<th>Name</th><th>Zip</th><th></th></tr></thead>' +
        '<tbody></tbody>' +
      '</table>'
    ),
    childViewContainer: 'tbody',
    childView: PrintLab.DocumentRowView,
    emptyView: Marionette.View.extend({
      tagName: 'tr',
      className: 'empty-row',
      template: _.template('<td colspan="7"><%- message %></td>'),
      templateContext() {
        return { message: this.getOption('message') || 'No documents.' };
      },
    }),
    emptyViewOptions() {
      return { message: this.getOption('emptyMessage') };
    },
  });

  /**
   * A job's documents. The tabs are All plus each queue the job's documents are in; a tab sets
   * the collection's view filter, so the rows are filtered in place.
   */
  PrintLab.DocumentsView = PrintLab.DocumentListView.extend({
    className: 'documents',
    template: _.template(
      '<nav class="queue-tabs" aria-label="Queues">' +
        '<% _.each(queues, function (q) { %>' +
        '<button type="button" class="queue-tab<% if (q.value === active) { %> is-active<% } %>"' +
        ' data-queue="<%- q.value %>" aria-pressed="<%- q.value === active %>">' +
        '<%- q.label %></button>' +
        '<% }); %>' +
      '</nav>' +
      '<table class="data-table">' +
        '<thead><tr><th>Document</th><th>Job</th><th>Queue</th><th>Account</th>' +
        '<th>Name</th><th>Zip</th><th></th></tr></thead>' +
        '<tbody></tbody>' +
      '</table>'
    ),
    events: {
      'click .queue-tab': 'selectQueue',
    },

    initialize() {
      this.queue = '';
    },

    templateContext() {
      const names = _.uniq(this.collection.pluck('queue').filter(Boolean)).sort();
      const queues = [{ value: '', label: 'All (' + this.collection.length + ')' }];
      names.forEach((name) => {
        const count = this.collection.where({ queue: name }).length;
        queues.push({ value: name, label: name + ' (' + count + ')' });
      });
      return { queues, active: this.queue };
    },

    /** Filters the rows to one queue ('' for all). The tabs are re-marked, not re-rendered. */
    selectQueue(event) {
      const queue = event.currentTarget.getAttribute('data-queue') || '';
      this.queue = queue;
      // Marionette passes each child view to the filter, so the queue is read from its model.
      this.setFilter(queue ? (view) => view.model.get('queue') === queue : null);
      this.el.querySelectorAll('.queue-tab').forEach((tab) => {
        const active = tab.getAttribute('data-queue') === queue;
        tab.classList.toggle('is-active', active);
        tab.setAttribute('aria-pressed', String(active));
      });
    },
  });
})(window);
