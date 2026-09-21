import ELK from 'elkjs/lib/elk.bundled.js';

export const METAMODEL_NODE_WIDTH = 168;
export const METAMODEL_NODE_HEIGHT = 58;
export const METAMODEL_ASSOCIATION_HEIGHT = 32;

const COMPONENT_PADDING = 18;
const COMPONENT_HEADER_HEIGHT = 42;
const COMPONENT_GAP = 48;
const ROUTING_ANCHOR_SIZE = 8;
const MIN_COMPONENT_WIDTH = 460;
const COMPONENT_PACKING_ASPECT_RATIO = 1.55;
const elk = new ELK();

export async function layoutMetamodel(nodes, edges) {
  const analysis = analyzeStructuralGraph(nodes, edges);
  const componentLayouts = await Promise.all(analysis.components.map(component =>
    layoutComponent(component, nodes, edges)));
  const packed = packComponents(componentLayouts);
  const positions = new Map();
  const associationPositions = new Map();
  const edgeRoutes = new Map();

  packed.components.forEach(component => {
    component.edgeRoutes.forEach((sections, id) => edgeRoutes.set(id, sections.map(points =>
      points.map(point => ({ x: point.x + component.x, y: point.y + component.y })))));
    component.classPositions.forEach((position, id) => {
      positions.set(id, {
        x: position.x + component.x,
        y: position.y + component.y,
        componentId: component.id,
        cyclic: analysis.cyclicNodeIds.has(id)
      });
    });
    component.associationPositions.forEach((position, id) => {
      associationPositions.set(id, {
        ...position,
        x: position.x + component.x,
        y: position.y + component.y,
        componentId: component.id
      });
    });
  });

  return {
    width: packed.width,
    height: packed.height,
    positions,
    associationPositions,
    edgeRoutes,
    components: packed.components.map(({ classPositions, associationPositions, edgeRoutes, ...component }) => component),
    componentCount: packed.components.length,
    multipleParentCount: analysis.multipleParentCount,
    rootCount: analysis.components.reduce((total, component) => total + component.rootCount, 0),
    stronglyConnectedComponents: analysis.cyclicComponents
  };
}

export function analyzeStructuralGraph(nodes, edges) {
  const nodeIds = new Set(nodes.map(node => node.id));
  const validEdges = edges.filter(edge => nodeIds.has(edge.source) && nodeIds.has(edge.target));
  const undirected = new Map(nodes.map(node => [node.id, new Set()]));
  const outgoing = new Map(nodes.map(node => [node.id, []]));
  const incoming = new Map(nodes.map(node => [node.id, new Set()]));

  validEdges.forEach(edge => {
    undirected.get(edge.source).add(edge.target);
    undirected.get(edge.target).add(edge.source);
    outgoing.get(edge.source).push(edge.target);
    incoming.get(edge.target).add(edge.source);
  });

  const visited = new Set();
  const components = [];
  nodes.forEach(node => {
    if (visited.has(node.id)) return;
    const nodeIdsInComponent = [];
    const queue = [node.id];
    visited.add(node.id);
    while (queue.length) {
      const current = queue.shift();
      nodeIdsInComponent.push(current);
      undirected.get(current).forEach(neighbor => {
        if (visited.has(neighbor)) return;
        visited.add(neighbor);
        queue.push(neighbor);
      });
    }
    const idSet = new Set(nodeIdsInComponent);
    const componentEdges = validEdges.filter(edge => idSet.has(edge.source) && idSet.has(edge.target));
    components.push({
      nodeIds: nodeIdsInComponent,
      edgeIds: componentEdges.map(edge => edge.id),
      rootCount: nodeIdsInComponent.filter(id => incoming.get(id).size === 0).length
    });
  });
  components.sort((left, right) => right.nodeIds.length - left.nodeIds.length
    || right.edgeIds.length - left.edgeIds.length);
  components.forEach((component, index) => {
    component.id = `component-${index + 1}`;
    component.main = index === 0;
  });

  const stronglyConnected = stronglyConnectedComponents(nodes.map(node => node.id), outgoing);
  const selfLoops = new Set(validEdges.filter(edge => edge.source === edge.target).map(edge => edge.source));
  const cyclicComponents = stronglyConnected.filter(component =>
    component.length > 1 || selfLoops.has(component[0]));
  const cyclicNodeIds = new Set(cyclicComponents.flat());
  const multipleParentCount = [...incoming.values()].filter(parents => parents.size > 1).length;
  return { components, cyclicComponents, cyclicNodeIds, multipleParentCount };
}

async function layoutComponent(component, allNodes, allEdges) {
  const nodeIds = new Set(component.nodeIds);
  const edgeIds = new Set(component.edgeIds);
  const nodes = allNodes
    .filter(node => nodeIds.has(node.id))
    .sort((left, right) => left.label.localeCompare(right.label, 'ru'));
  // Keep association order supplied by metadata, not alphabetical group-label order.
  const edges = allEdges.filter(edge => edgeIds.has(edge.id));
  const associationNodes = edges.map(edge => ({
    id: associationNodeId(edge.id),
    // Invisible routing anchors should not reserve the old group-label space.
    width: ROUTING_ANCHOR_SIZE,
    height: ROUTING_ANCHOR_SIZE
  }));
  const elkEdges = edges.flatMap(edge => [
    {
      id: `${edge.id}-parent`,
      sources: [edge.source],
      targets: [associationNodeId(edge.id)]
    },
    {
      id: `${edge.id}-child`,
      sources: [associationNodeId(edge.id)],
      targets: [edge.target]
    }
  ]);
  const layout = await elk.layout({
    id: component.id,
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'DOWN',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.spacing.nodeNode': '16',
      'elk.layered.spacing.nodeNodeBetweenLayers': '24',
      'elk.spacing.edgeNode': '8',
      'elk.layered.spacing.edgeNodeBetweenLayers': '8',
      'elk.spacing.edgeEdge': '6',
      'elk.layered.spacing.edgeEdgeBetweenLayers': '6',
      'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
      'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
      'elk.layered.nodePlacement.favorStraightEdges': 'false',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.padding': '[top=8,left=8,bottom=8,right=8]'
    },
    children: [
      ...nodes.map(node => ({
        id: node.id,
        width: METAMODEL_NODE_WIDTH,
        height: METAMODEL_NODE_HEIGHT
      })),
      ...associationNodes
    ],
    edges: elkEdges
  });

  const classPositions = new Map();
  const associationPositions = new Map();
  const routedEdges = new Map((layout.edges || []).map(edge => [edge.id, edge]));
  const edgeRoutes = new Map(edges.map(edge => [edge.id,
    [`${edge.id}-parent`, `${edge.id}-child`].flatMap(id =>
      (routedEdges.get(id)?.sections || []).map(section =>
        [section.startPoint, ...(section.bendPoints || []), section.endPoint].map(point => ({
          x: point.x + COMPONENT_PADDING,
          y: point.y + COMPONENT_PADDING + COMPONENT_HEADER_HEIGHT
        }))))]));
  (layout.children || []).forEach(child => {
    const position = {
      x: (child.x || 0) + child.width / 2 + COMPONENT_PADDING,
      y: (child.y || 0) + child.height / 2 + COMPONENT_PADDING + COMPONENT_HEADER_HEIGHT,
      width: child.width,
      height: child.height
    };
    if (child.id.startsWith('association:')) {
      associationPositions.set(child.id.slice('association:'.length), position);
    } else {
      classPositions.set(child.id, position);
    }
  });

  return {
    ...component,
    nodeCount: component.nodeIds.length,
    edgeCount: component.edgeIds.length,
    width: Math.max(MIN_COMPONENT_WIDTH, (layout.width || 0) + COMPONENT_PADDING * 2),
    height: (layout.height || 0) + COMPONENT_PADDING * 2 + COMPONENT_HEADER_HEIGHT,
    classPositions,
    associationPositions,
    edgeRoutes
  };
}

function packComponents(components) {
  const totalArea = components.reduce((total, component) => total + component.width * component.height, 0);
  const widest = Math.max(...components.map(component => component.width), 1);
  const targetWidth = Math.max(widest, Math.sqrt(totalArea) * COMPONENT_PACKING_ASPECT_RATIO);
  let x = 0;
  let y = 0;
  let rowHeight = 0;
  let packedWidth = 0;

  components.forEach(component => {
    if (x > 0 && x + component.width > targetWidth) {
      x = 0;
      y += rowHeight + COMPONENT_GAP;
      rowHeight = 0;
    }
    component.x = x;
    component.y = y;
    component.label = component.main
      ? `Component 1 · ${component.nodeCount} classes`
      : `Disconnected component ${component.id.slice('component-'.length)} · ${component.nodeCount} classes`;
    x += component.width + COMPONENT_GAP;
    rowHeight = Math.max(rowHeight, component.height);
    packedWidth = Math.max(packedWidth, x - COMPONENT_GAP);
  });

  return {
    components,
    width: packedWidth,
    height: y + rowHeight
  };
}

function associationNodeId(edgeId) {
  return `association:${edgeId}`;
}

function associationWidth(label) {
  return Math.min(230, Math.max(112, (label?.length || 0) * 6.3 + 28));
}

function stronglyConnectedComponents(nodeIds, outgoing) {
  let currentIndex = 0;
  const indices = new Map();
  const lowLinks = new Map();
  const stack = [];
  const onStack = new Set();
  const components = [];

  function visit(nodeId) {
    indices.set(nodeId, currentIndex);
    lowLinks.set(nodeId, currentIndex);
    currentIndex += 1;
    stack.push(nodeId);
    onStack.add(nodeId);
    for (const target of outgoing.get(nodeId) || []) {
      if (!indices.has(target)) {
        visit(target);
        lowLinks.set(nodeId, Math.min(lowLinks.get(nodeId), lowLinks.get(target)));
      } else if (onStack.has(target)) {
        lowLinks.set(nodeId, Math.min(lowLinks.get(nodeId), indices.get(target)));
      }
    }
    if (lowLinks.get(nodeId) !== indices.get(nodeId)) return;
    const component = [];
    let member;
    do {
      member = stack.pop();
      onStack.delete(member);
      component.push(member);
    } while (member !== nodeId);
    components.push(component);
  }

  nodeIds.forEach(nodeId => {
    if (!indices.has(nodeId)) visit(nodeId);
  });
  return components;
}
