/*
 * The jobs list and one job's detail: the rows, the state badges, the step timeline and the
 * properties. Rows trigger 'select' on their link; the list forwards it as 'job:select'.
 */
(function (root) {
  'use strict';

  const { Marionette, _ } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  PrintLab.EmptyJobsView = Marionette.View.extend({
    tagName: 'tr',
    className: 'empty-row',
    template: _.template('<td colspan="5">No jobs yet. Drop a file in a hot folder to start one.</td>'),
  });

  PrintLab.JobRowView = Marionette.View.extend({
    tagName: 'tr',
    className: 'job-row',
    template: _.template(
      '<td><a class="job-link" href="<%- href %>"><%- id %></a></td>' +
      '<td><span class="badge badge-<%- badge %>"><%- state %></span></td>' +
      '<td><%- phase %></td>' +
      '<td><%- currentStep %></td>' +
      '<td><%- documentCount %></td>'
    ),
    templateContext() {
      const job = this.model;
      return {
        id: job.id,
        href: '#jobs/' + encodeURIComponent(job.id),
        state: job.get('state') || '',
        badge: PrintLab.badgeClass(job.get('state')),
        phase: String(job.get('phase') || '').toLowerCase(),
        currentStep: job.get('currentStep') || '',
        documentCount: job.get('documentCount') || 0,
      };
    },
    triggers: {
      'click .job-link': { event: 'select', preventDefault: true, stopPropagation: true },
    },
    onRender() {
      this.setSelected(this.getOption('selectedId') === this.model.id);
    },
    setSelected(selected) {
      this.el.classList.toggle('is-selected', selected);
      const link = this.el.querySelector('.job-link');
      if (selected) {
        link.setAttribute('aria-current', 'true');
      } else {
        link.removeAttribute('aria-current');
      }
    },
  });

  /** The jobs table. Its rows keep the selection across refreshes (see setSelected). */
  PrintLab.JobsListView = Marionette.CollectionView.extend({
    className: 'jobs-list',
    template: _.template(
      '<table class="data-table">' +
        '<thead><tr><th>Job</th><th>State</th><th>Phase</th><th>Current step</th>' +
        '<th>Documents</th></tr></thead>' +
        '<tbody></tbody>' +
      '</table>'
    ),
    childViewContainer: 'tbody',
    childView: PrintLab.JobRowView,
    emptyView: PrintLab.EmptyJobsView,
    childViewTriggers: {
      select: 'job:select',
    },
    childViewOptions() {
      return { selectedId: this.selectedId };
    },

    initialize(options) {
      this.selectedId = (options && options.selectedId) || null;
    },

    /** Marks one job's row as selected, and keeps it selected when the list refreshes. */
    setSelected(id) {
      this.selectedId = id || null;
      this.children.each((view) => view.setSelected(view.model.id === this.selectedId));
    },
  });

  /** The job's properties as a definition list. */
  PrintLab.JobPropertiesView = Marionette.View.extend({
    className: 'job-properties',
    template: _.template(
      '<% if (entries.length === 0) { %><p class="muted">No properties.</p><% } else { %>' +
      '<dl class="properties"><% _.each(entries, function (e) { %>' +
      '<dt><%- e[0] %></dt><dd><%- e[1] %></dd>' +
      '<% }); %></dl><% } %>'
    ),
    templateContext() {
      return { entries: _.pairs(this.model.get('properties') || {}) };
    },
  });

  /** One step of the timeline. */
  PrintLab.StepRowView = Marionette.View.extend({
    tagName: 'li',
    className: 'step-row',
    template: _.template(
      '<span class="step-name"><%- name %></span> ' +
      '<span class="badge badge-<%- badge %>"><%- outcome %></span> ' +
      '<span class="step-phase"><%- phase %></span>'
    ),
    templateContext() {
      return {
        name: this.model.get('name') || '',
        outcome: this.model.get('outcome') || '',
        badge: PrintLab.badgeClass(this.model.get('outcome')),
        phase: String(this.model.get('phase') || '').toLowerCase(),
      };
    },
  });

  PrintLab.StepsView = Marionette.CollectionView.extend({
    tagName: 'ol',
    className: 'step-timeline',
    childView: PrintLab.StepRowView,
    emptyView: Marionette.View.extend({
      tagName: 'li',
      className: 'muted',
      template: _.template('No steps have run yet.'),
    }),
  });

  /** A job's header and its three regions: properties, the step timeline and documents. */
  PrintLab.JobDetailView = Marionette.View.extend({
    className: 'job-detail',
    template: _.template(
      '<h2>Job <%- id %> <span class="badge badge-<%- badge %>"><%- state %></span></h2>' +
      '<p>Phase: <%- phase %></p>' +
      '<% if (errorMessage) { %><p class="job-error" role="alert"><%- errorMessage %></p><% } %>' +
      '<section><h3>Properties</h3><div class="region-properties"></div></section>' +
      '<section><h3>Steps</h3><div class="region-steps"></div></section>' +
      '<section><h3>Documents</h3><div class="region-documents"></div></section>'
    ),
    regions: {
      properties: '.region-properties',
      steps: '.region-steps',
      documents: '.region-documents',
    },
    templateContext() {
      const job = this.model;
      return {
        id: job.id,
        state: job.get('state') || '',
        badge: PrintLab.badgeClass(job.get('state')),
        phase: String(job.get('phase') || '').toLowerCase(),
        errorMessage: job.get('errorMessage') || null,
      };
    },
    onRender() {
      this.showChildView('properties', new PrintLab.JobPropertiesView({ model: this.model }));
      this.showChildView('steps', new PrintLab.StepsView({ collection: this.getOption('steps') }));
      this.showChildView('documents', new PrintLab.DocumentsView({
        collection: this.getOption('documents'),
      }));
    },
  });
})(window);
