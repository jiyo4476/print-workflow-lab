/*
 * The structure of a document, from GET api/documents/{id}/structure (PRD-14 FR-6), as a tree of
 * StructureNodes. An AFP file's structured fields come flat; buildStructure nests them by their
 * Begin and End fields (the middle byte of the type id is A8 for a Begin, A9 for an End). A PDF
 * nests its operators under their page.
 */
(function (root) {
  'use strict';

  const { Backbone } = root;
  const PrintLab = (root.PrintLab = root.PrintLab || {});

  PrintLab.StructureNode = Backbone.Model.extend({
    initialize() {
      this.children = new PrintLab.StructureNodes();
    },
  });

  PrintLab.StructureNodes = Backbone.Collection.extend({
    model: PrintLab.StructureNode,
  });

  const BEGIN = 'A8';
  const END = 'A9';

  /**
   * The tree for a structure body: {@code {roots, truncated}}. {@code roots} is a
   * StructureNodes; {@code truncated} says the server stopped at its item cap.
   */
  PrintLab.buildStructure = function buildStructure(body) {
    const roots = new PrintLab.StructureNodes();
    if (body.format === 'pdf') {
      (body.pages || []).forEach((page) => {
        const node = new PrintLab.StructureNode({
          label: 'Page ' + page.number,
          detail: (page.operators || []).length + ' operators',
        });
        (page.operators || []).forEach((op) => {
          node.children.add(new PrintLab.StructureNode({
            label: op.operator,
            detail: 'offset ' + op.offset + ', ' + op.operands + ' operands',
          }));
        });
        roots.add(node);
      });
    } else {
      // Stack of the collections open at each depth; the bottom one is the roots.
      const open = [roots];
      (body.fields || []).forEach((field) => {
        const kind = String(field.typeId || '').slice(2, 4);
        if (kind === END) {
          if (open.length > 1) open.pop();
          return;
        }
        const node = new PrintLab.StructureNode({
          label: field.type,
          detail: field.typeId + ' at ' + field.offset + ', ' + field.dataLength + ' bytes',
        });
        open[open.length - 1].add(node);
        if (kind === BEGIN) open.push(node.children);
      });
    }
    return { roots, truncated: Boolean(body.truncated) };
  };
})(window);
