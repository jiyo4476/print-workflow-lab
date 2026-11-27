/*
 * The dashboard Application and its page shell. The shell owns the layout (header, navigation,
 * main region); the Application owns startup and decides what the main region shows for each
 * route. Starting a feature ends the previous one first, so its controller stops its timers and
 * requests before the next one runs.
 */
(function (root) {
  'use strict';

  const { Backbone, Marionette, _ } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  PrintLab.ShellView = Marionette.View.extend({
    className: 'shell',
    template: _.template(
      '<header class="shell-header">' +
        '<h1>Print Workflow Lab</h1>' +
        '<nav class="shell-nav" aria-label="Main">' +
          '<a href="#jobs">Jobs</a> <a href="#search">Search</a>' +
        '</nav>' +
        '<div class="shell-health"></div>' +
      '</header>' +
      '<main class="shell-main"></main>'
    ),
    regions: {
      health: '.shell-health',
      main: '.shell-main',
    },
  });

  PrintLab.DashboardApp = Marionette.Application.extend({
    region: '#app',
    channelName: 'dashboard',

    onStart() {
      this.shell = new PrintLab.ShellView();
      this.showView(this.shell);

      this.health = new PrintLab.HealthModel();
      this.shell.showChildView('health', new PrintLab.HealthView({ model: this.health }));
      this.health.check();

      this.router = new PrintLab.DashboardRouter({ app: this });
      Backbone.history.start();
    },

    showMain(view) {
      this.shell.showChildView('main', view);
      // Focus moves to the new page, so keyboard users start at its content. Refreshes don't
      // come through here, so polling never moves focus.
      const main = this.shell.el.querySelector('.shell-main');
      main.setAttribute('tabindex', '-1');
      main.focus();
      return view;
    },

    loading(message) {
      return this.showMain(new PrintLab.PlaceholderView({ message }));
    },

    /** A failure with a Retry button; {@code onRetry} runs when it is pressed. */
    showError(message, onRetry) {
      const view = new PrintLab.ErrorView({ message });
      this.listenTo(view, 'retry', onRetry);
      return this.showMain(view);
    },

    /** Ends the feature on screen: its controller stops, and the listeners it had go with it. */
    endFeature() {
      this.stopListening();
      if (this.controller) this.controller.destroy();
      this.controller = null;
    },

    showJobs() {
      this.endFeature();
      const controller = (this.controller = new PrintLab.JobsController());

      const showList = () => {
        const list = new PrintLab.JobsListView({
          collection: controller.jobs,
          selectedId: this.selectedJobId,
        });
        this.listenTo(list, 'job:select', (row) => {
          this.selectedJobId = row.model.id;
          this.router.navigate('jobs/' + encodeURIComponent(row.model.id), { trigger: true });
        });
        this.showMain(list);
      };

      this.listenTo(controller, 'loaded', () => {
        if (!(this.shell.getChildView('main') instanceof PrintLab.JobsListView)) showList();
      });
      this.listenTo(controller, 'error', (xhr) => {
        this.showError(PrintLab.describeFailure('jobs', xhr), () => {
          this.loading('Loading jobs…');
          controller.refresh();
        });
      });

      this.loading('Loading jobs…');
      controller.start();
    },

    showJob(id) {
      this.endFeature();
      this.selectedJobId = id;
      const controller = (this.controller = new PrintLab.JobDetailController({ id }));

      this.listenTo(controller, 'loaded', ({ job, documents, steps }) => {
        this.showMain(new PrintLab.JobDetailView({ model: job, documents, steps }));
      });
      this.listenTo(controller, 'error', (xhr) => {
        this.showError(PrintLab.describeFailure('job ' + id, xhr), () => {
          this.loading('Loading job…');
          controller.load();
        });
      });

      this.loading('Loading job…');
      controller.load();
    },

    /** {@code query} is the part of the URL after '?': property, value and offset. */
    showSearch(query) {
      this.endFeature();
      const params = new URLSearchParams(query || '');
      const property = PrintLab.SEARCH_PROPERTIES.includes(params.get('property'))
        ? params.get('property')
        : PrintLab.SEARCH_PROPERTIES[0];
      const value = params.get('value') || '';
      const offset = Math.max(0, parseInt(params.get('offset') || '0', 10) || 0);
      const criteria = value ? { property, value, offset } : null;

      const controller = (this.controller = new PrintLab.SearchController());
      const page = new PrintLab.SearchPage({ criteria, limit: PrintLab.SEARCH_PAGE_SIZE });
      const view = new PrintLab.SearchView({ model: page, documents: controller.documents });

      this.listenTo(view, 'search:submit', (submitted) => {
        if (submitted.value) {
          this.navigateSearch(submitted.property, submitted.value, 0);
        }
      });
      this.listenTo(view, 'page:change', (newOffset) => {
        this.navigateSearch(criteria.property, criteria.value, newOffset);
      });
      this.listenTo(view, 'retry', () => {
        page.set({ error: null });
        controller.search(page.get('criteria'));
      });
      this.listenTo(controller, 'loaded', ({ criteria: done, total, offset: at }) => {
        page.set({ criteria: done, total, offset: at, error: null });
      });
      this.listenTo(controller, 'error', (xhr, failed) => {
        page.set({ criteria: failed, error: PrintLab.describeFailure('search results', xhr) });
      });

      this.showMain(view);
      if (criteria) controller.search(criteria);
    },

    navigateSearch(property, value, offset) {
      const params = new URLSearchParams();
      params.set('property', property);
      params.set('value', value);
      if (offset) params.set('offset', String(offset));
      this.router.navigate('search?' + params.toString(), { trigger: true });
    },

    showStructure(id) {
      this.endFeature();
      const controller = (this.controller = new PrintLab.StructureController({ documentId: id }));

      this.listenTo(controller, 'loaded', ({ roots, truncated }) => {
        this.showMain(new PrintLab.StructurePageView({ documentId: id, roots, truncated }));
      });
      this.listenTo(controller, 'error', (xhr) => {
        this.showError(PrintLab.describeFailure('the structure', xhr), () => {
          this.loading('Loading structure…');
          controller.load();
        });
      });

      this.loading('Loading structure…');
      controller.load();
    },
  });
})(window);
