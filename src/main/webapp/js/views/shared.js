/*
 * Views and helpers used by more than one feature. Views only render and emit intent (a trigger
 * such as 'retry'); the app decides what that means. Every value goes through <%- %>.
 */
(function (root) {
  'use strict';

  const { Marionette, _ } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  /** The CSS badge for a job state or step outcome: red for a failure, green for done. */
  PrintLab.badgeClass = function badgeClass(value) {
    if (value === 'ERROR' || value === 'FAILED') return 'error';
    if (value === 'COMPLETE' || value === 'COMPLETED') return 'ok';
    return 'pending';
  };

  /** A sentence for a failed request, from its status (0 means the API didn't answer). */
  PrintLab.describeFailure = function describeFailure(what, xhr) {
    const status = xhr && xhr.status;
    if (!status) return 'Could not load ' + what + ': the API did not answer.';
    return 'Could not load ' + what + ' (HTTP ' + status + ').';
  };

  /** A message with a Retry button. Pressing Retry triggers 'retry'. */
  PrintLab.ErrorView = Marionette.View.extend({
    className: 'error-state',
    attributes: { role: 'alert' },
    template: _.template(
      '<p><%- message %></p>' +
      '<button type="button" class="retry">Retry</button>'
    ),
    templateContext() {
      return { message: this.getOption('message') || 'Something went wrong.' };
    },
    triggers: {
      'click .retry': 'retry',
    },
  });

  /** A status line for a view that is waiting on the API. */
  PrintLab.PlaceholderView = Marionette.View.extend({
    className: 'placeholder',
    template: _.template('<p><%- message %></p>'),
    templateContext() {
      return { message: this.getOption('message') };
    },
  });
})(window);
