/*
 * A document's structure as a tree. StructureView lists nodes; each StructureNodeView shows its
 * children in a nested StructureView while it is expanded, so the tree is built up from the
 * same view at each level. Toggling a node re-renders only that node.
 */
(function (root) {
  'use strict';

  const { Marionette, _ } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  PrintLab.StructureNodeView = Marionette.View.extend({
    tagName: 'li',
    className: 'node',
    template: _.template(
      '<div class="node-line">' +
        '<% if (hasChildren) { %>' +
        '<button type="button" class="toggle" aria-expanded="<%- expanded %>">' +
        '<%- expanded ? "▾" : "▸" %></button>' +
        '<% } %>' +
        '<span class="node-label"><%- label %></span> ' +
        '<span class="node-detail"><%- detail %></span>' +
      '</div>' +
      '<div class="region-children"></div>'
    ),
    regions: {
      children: '.region-children',
    },
    triggers: {
      'click .toggle': 'node:toggle',
    },
    templateContext() {
      const expanded = this.isExpanded();
      return {
        label: this.model.get('label') || '',
        detail: this.model.get('detail') || '',
        hasChildren: this.model.children.length > 0,
        expanded,
      };
    },
    onRender() {
      if (this.isExpanded() && this.model.children.length > 0) {
        this.showChildView('children', new PrintLab.StructureView({
          collection: this.model.children,
        }));
      }
    },
    onNodeToggle() {
      this.expanded = !this.isExpanded();
      this.render();
    },
    isExpanded() {
      return this.expanded === undefined ? this.getOption('expanded') !== false : this.expanded;
    },
  });

  PrintLab.StructureView = Marionette.CollectionView.extend({
    tagName: 'ul',
    className: 'structure-tree',
    childView: PrintLab.StructureNodeView,
  });

  /** The page around a structure tree: a heading, a note if the file was cut short, the tree. */
  PrintLab.StructurePageView = Marionette.View.extend({
    className: 'structure-page',
    template: _.template(
      '<h2>Structure of document <%- id %></h2>' +
      '<% if (truncated) { %><p class="muted">Only the first items are shown: the file is larger' +
      ' than the view can list.</p><% } %>' +
      '<div class="region-tree"></div>'
    ),
    regions: {
      tree: '.region-tree',
    },
    templateContext() {
      return { id: this.getOption('documentId'), truncated: this.getOption('truncated') };
    },
    onRender() {
      this.showChildView('tree', new PrintLab.StructureView({
        collection: this.getOption('roots'),
      }));
    },
  });
})(window);
