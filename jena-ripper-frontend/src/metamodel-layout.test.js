import test from 'node:test';
import assert from 'node:assert/strict';
import { analyzeStructuralGraph, layoutMetamodel } from './metamodel-layout.js';

const nodes = ['A', 'B', 'C', 'D', 'E', 'F'].map(id => ({ id, label: id }));
const edges = [['A', 'C'], ['B', 'C'], ['C', 'D'], ['D', 'C'], ['D', 'D'], ['E', 'F']]
  .map(([source, target], i) => ({ id: String(i), source, target, label: 'Group ' + i }));

test('keeps disconnected branches, distinct parents, cycles and self-associations', () => {
  const analysis = analyzeStructuralGraph(nodes, edges);
  assert.equal(analysis.components.length, 2);
  assert.equal(analysis.components.reduce((sum, component) => sum + component.rootCount, 0), 3);
  assert.deepEqual([...analysis.cyclicNodeIds].sort(), ['C', 'D']);
  assert.equal(analysis.multipleParentCount, 2);
});

test('packs complete components without node duplication and routes every association', async () => {
  const layout = await layoutMetamodel(nodes, edges);
  assert.equal(layout.positions.size, nodes.length);
  assert.equal(layout.associationPositions.size, edges.length);
  assert.equal(layout.edgeRoutes.size, edges.length);
  for (const edge of edges) {
    assert.equal(layout.positions.get(edge.source).componentId, layout.positions.get(edge.target).componentId);
    assert.ok(layout.edgeRoutes.get(edge.id).length >= 2);
    for (const point of layout.edgeRoutes.get(edge.id).flat()) {
      assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y));
    }
  }
  for (const a of layout.components) for (const b of layout.components) {
    if (a.id === b.id) continue;
    assert.ok(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y);
  }
});

test('supports empty structural graph', async () => {
  const layout = await layoutMetamodel([], []);
  assert.equal(layout.componentCount, 0);
  assert.equal(layout.positions.size, 0);
});
