/* Starts the dashboard once the page has loaded. Kept separate so tests can load app.js
 * without starting anything. */
(function (root) {
  'use strict';

  root.jQuery(() => {
    root.PrintLab.app = new root.PrintLab.DashboardApp();
    root.PrintLab.app.start();
  });
})(window);
