/*
 * The dashboard Application and its page shell. The shell owns the layout (header + main
 * regions); the Application owns startup and decides what each region shows.
 */
(function (root) {
  'use strict';

  const { Marionette, _ } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  PrintLab.ShellView = Marionette.View.extend({
    className: 'shell',
    template: _.template(
      '<header class="shell-header">' +
        '<h1>Print Workflow Lab</h1>' +
        '<div class="shell-health"></div>' +
      '</header>' +
      '<main class="shell-main"></main>'
    ),
    regions: {
      health: '.shell-health',
      main: '.shell-main',
    },
  });

  PrintLab.PlaceholderView = Marionette.View.extend({
    className: 'placeholder',
    template: _.template('<p><%- message %></p>'),
    templateContext() {
      return { message: this.getOption('message') };
    },
  });

  PrintLab.DashboardApp = Marionette.Application.extend({
    region: '#app',

    onStart() {
      const shell = new PrintLab.ShellView();
      this.showView(shell);

      this.health = new PrintLab.HealthModel();
      shell.showChildView('health', new PrintLab.HealthView({ model: this.health }));
      shell.showChildView('main', new PrintLab.PlaceholderView({
        message: 'Jobs, documents and search arrive in PR 16.',
      }));

      this.health.check();
    },
  });
})(window);
