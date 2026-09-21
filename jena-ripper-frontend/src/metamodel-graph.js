import * as d3 from 'd3';
import { layoutMetamodel, METAMODEL_NODE_HEIGHT, METAMODEL_NODE_WIDTH } from './metamodel-layout.js';

const MIN_ZOOM = 0.04;
const MAX_ZOOM = 4;
const MAX_FIT_SCALE = 1.45;
const FOCUS_SCALE = 1.35;
const VIEW_TRANSITION_MS = 450;
const GRAPH_FIT_PADDING = 90;
const COMPONENT_FIT_PADDING = 50;
const MAX_TOP_INSET = 130;
const NODE_LABEL_LENGTH = 25;

export class MetamodelGraph {
  constructor(container, onNodeSelect, onEdgeSelect = () => {}) {
    this.container = container;
    this.onNodeSelect = onNodeSelect;
    this.onEdgeSelect = onEdgeSelect;
    this.nodes = new Map();
    this.edges = new Map();
    this.associationNodes = new Map();
    this.components = new Map();
    this.selectedId = null;
    this.selectedEdgeId = null;
    this.layoutWidth = 0;
    this.layoutHeight = 0;
    this.svg = d3.select(container).append('svg').attr('class', 'metamodel-layered')
      .attr('aria-label', 'Иерархический граф метамодели');
    this.root = this.svg.append('g');
    this.componentLayer = this.root.append('g').attr('class', 'metamodel-components');
    this.edgeLayer = this.root.append('g').attr('class', 'metamodel-edges');
    this.edgeHitLayer = this.root.append('g').attr('class', 'metamodel-edge-hits');
    this.associationLayer = this.root.append('g').attr('class', 'metamodel-association-nodes');
    this.nodeLayer = this.root.append('g').attr('class', 'metamodel-nodes');
    this.zoom = d3.zoom().scaleExtent([MIN_ZOOM, MAX_ZOOM])
      .on('zoom', event => this.root.attr('transform', event.transform));
    this.svg.call(this.zoom).on('dblclick.zoom', null).on('click', () => this.clearSelection());
    this.markerId = `metamodel-arrow-${++graphSequence}`;
    this.svg.append('defs').append('marker').attr('id', this.markerId).attr('viewBox', '0 -5 10 10')
      .attr('refX', 9).attr('refY', 0).attr('markerWidth', 7).attr('markerHeight', 7).attr('orient', 'auto')
      .append('path').attr('d', 'M0,-5L10,0L0,5');
  }

  async load({ nodes, edges }) {
    const layout = await layoutMetamodel(nodes, edges);
    this.nodes = new Map(nodes.map(node => [node.id, { ...node, ...layout.positions.get(node.id) }]));
    this.edges = new Map(edges.map(edge => [edge.id, { ...edge, route: layout.edgeRoutes.get(edge.id) }]));
    this.associationNodes = new Map(edges.map(edge => [edge.id, { ...edge, ...layout.associationPositions.get(edge.id) }]));
    this.components = new Map(layout.components.map(component => [component.id, component]));
    this.layoutWidth = layout.width;
    this.layoutHeight = layout.height;
    this.selectedId = null;
    this.selectedEdgeId = null;
    this.render();
    return {
      nodeCount: this.nodes.size,
      edgeCount: this.edges.size,
      componentCount: layout.componentCount,
      multipleParentCount: layout.multipleParentCount,
      rootCount: layout.rootCount,
      cyclicComponentCount: layout.stronglyConnectedComponents.length
    };
  }

  render() {
    const nodes = [...this.nodes.values()];
    const edges = [...this.edges.values()];
    const components = [...this.components.values()];
    this.componentGroups = this.componentLayer.selectAll('g').data(components, item => item.id).join(enter => {
      const group = enter.append('g').attr('class', 'metamodel-component');
      group.append('rect').attr('rx', 18);
      group.append('text').attr('class', 'metamodel-component-title');
      group.append('text').attr('class', 'metamodel-component-meta');
      return group;
    });
    this.componentGroups.attr('data-component-id', item => item.id).classed('main', item => item.main)
      .attr('transform', item => `translate(${item.x},${item.y})`);
    this.componentGroups.select('rect').attr('width', item => item.width).attr('height', item => item.height);
    this.componentGroups.select('.metamodel-component-title').attr('x', 24).attr('y', 26).text(item => item.label)
      .on('click', (event, item) => { event.stopPropagation(); this.fitComponent(item.id); });
    this.componentGroups.select('.metamodel-component-meta').attr('x', item => item.width - 24).attr('y', 26)
      .text(item => `${item.rootCount} roots · ${item.edgeCount} associations`);

    this.links = this.edgeLayer.selectAll('path').data(edges, item => item.id).join('path')
      .attr('d', edge => edgePath(edge, this.nodes, this.associationNodes))
      .attr('marker-end', `url(#${this.markerId})`).classed('self-loop', edge => edge.source === edge.target);
    this.edgeHits = this.edgeHitLayer.selectAll('path').data(edges, item => item.id).join('path')
      .attr('d', edge => edgePath(edge, this.nodes, this.associationNodes))
      .on('click', (event, edge) => { event.stopPropagation(); this.selectEdge(edge.id); this.onEdgeSelect(edge); });
    this.edgeHits.selectAll('title').data(edge => [edge]).join('title')
      .text(edge => edge.associations.map(item => [item.labelRu || item.label, item.name].filter(Boolean).join(' · ')).join('\n'));

    // Keep ELK's routing anchors, but render only classes and continuous edges.
    this.associationGroups = this.associationLayer.selectAll('g').data([]).join('g');

    this.nodeGroups = this.nodeLayer.selectAll('g').data(nodes, item => item.id).join(enter => {
      const group = enter.append('g').attr('class', 'metamodel-node').attr('tabindex', 0).attr('role', 'button');
      group.append('rect').attr('x', -METAMODEL_NODE_WIDTH / 2).attr('y', -METAMODEL_NODE_HEIGHT / 2)
        .attr('width', METAMODEL_NODE_WIDTH).attr('height', METAMODEL_NODE_HEIGHT).attr('rx', 11);
      group.append('text').attr('class', 'metamodel-node-name').attr('dy', '.35em');
      group.append('title');
      return group;
    });
    this.nodeGroups.attr('data-node-id', item => item.id).attr('data-component-id', item => item.componentId)
      .attr('transform', item => `translate(${item.x},${item.y})`).classed('cyclic', item => item.cyclic)
      .on('click', (event, item) => { event.stopPropagation(); this.select(item.id); this.onNodeSelect(item); })
      .on('keydown', (event, item) => {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        event.preventDefault();
        this.select(item.id);
        this.onNodeSelect(item);
      });
    this.nodeGroups.select('text').text(item => truncate(item.label, NODE_LABEL_LENGTH));
    this.nodeGroups.select('title').text(item => `${item.label}\n${item.uri || item.id}`);
    this.updateSelection();
  }

  select(id) {
    if (!this.nodes.has(id)) return false;
    this.selectedId = id;
    this.selectedEdgeId = null;
    this.updateSelection();
    return true;
  }

  selectEdge(id) {
    if (!this.edges.has(id)) return false;
    this.selectedId = null;
    this.selectedEdgeId = id;
    this.updateSelection();
    return true;
  }

  clearSelection() {
    this.selectedId = null;
    this.selectedEdgeId = null;
    this.updateSelection();
  }

  updateSelection() {
    const neighbors = new Set();
    if (this.selectedId) {
      neighbors.add(this.selectedId);
      this.edges.forEach(edge => {
        if (edge.source === this.selectedId) neighbors.add(edge.target);
        if (edge.target === this.selectedId) neighbors.add(edge.source);
      });
    }
    const hasNodeSelection = Boolean(this.selectedId);
    this.svg.classed('has-node-selection', hasNodeSelection);
    if (this.nodeGroups) this.nodeGroups.classed('selected', item => item.id === this.selectedId)
      .classed('connected', item => neighbors.has(item.id) && item.id !== this.selectedId)
      .classed('muted', item => hasNodeSelection && !neighbors.has(item.id));
    if (this.links) this.links.classed('selected', item => item.id === this.selectedEdgeId)
      .classed('connected', item => hasNodeSelection && isConnected(item, this.selectedId))
      .classed('muted', item => hasNodeSelection && !isConnected(item, this.selectedId));
    if (this.associationGroups) this.associationGroups.classed('selected', item => item.id === this.selectedEdgeId)
      .classed('connected', item => hasNodeSelection && isConnected(item, this.selectedId))
      .classed('muted', item => hasNodeSelection && !isConnected(item, this.selectedId));
  }

  focus(id) {
    const node = this.nodes.get(id);
    if (!node) return false;
    this.select(id);
    const { width, height } = viewportSize(this.container);
    const scale = FOCUS_SCALE;
    this.svg.transition().duration(VIEW_TRANSITION_MS).call(this.zoom.transform,
      d3.zoomIdentity.translate(width / 2 - node.x * scale, height / 2 - node.y * scale).scale(scale));
    return true;
  }

  fit(duration = VIEW_TRANSITION_MS) {
    if (!this.components.size) return false;
    return this.fitBounds({ x: 0, y: 0, width: this.layoutWidth, height: this.layoutHeight },
      duration, GRAPH_FIT_PADDING);
  }

  fitComponent(id, duration = VIEW_TRANSITION_MS) {
    const component = this.components.get(id);
    return component ? this.fitBounds(component, duration, COMPONENT_FIT_PADDING) : false;
  }

  fitBounds(bounds, duration, padding) {
    const { width, height } = viewportSize(this.container);
    const graphWidth = Math.max(bounds.width + padding * 2, 1);
    const graphHeight = Math.max(bounds.height + padding * 2, 1);
    const topInset = Math.min(MAX_TOP_INSET, height / 3);
    const availableHeight = height - topInset;
    const scale = Math.min(MAX_FIT_SCALE,
      Math.max(MIN_ZOOM, Math.min(width / graphWidth, availableHeight / graphHeight)));
    const centerX = bounds.x + bounds.width / 2;
    const centerY = bounds.y + bounds.height / 2;
    this.svg.transition().duration(duration).call(this.zoom.transform,
      d3.zoomIdentity.translate(width / 2 - centerX * scale, topInset + availableHeight / 2 - centerY * scale).scale(scale));
    return true;
  }

  reset() {
    this.clearSelection();
    return this.fit(350);
  }

  destroy() {
    this.svg.remove();
    this.nodes.clear();
    this.edges.clear();
    this.associationNodes.clear();
    this.components.clear();
  }
}

let graphSequence = 0;

function edgePath(edge, nodes, associationNodes) {
  // Use ELK's obstacle-aware routing, including cycle-return and self-association paths.
  if (edge.route?.length) return edge.route.flat().map((point, index) =>
    `${index ? 'L' : 'M'}${point.x},${point.y}`).join(' ');
  const source = nodes.get(edge.source);
  const target = nodes.get(edge.target);
  const association = associationNodes.get(edge.id);
  if (!source || !target || !association) return '';
  if (source.id === target.id) return selfLoopPath(source, association);
  return `${segmentPath(source, association, METAMODEL_NODE_HEIGHT, association.height)} ${segmentPath(association, target, association.height, METAMODEL_NODE_HEIGHT)}`;
}

function segmentPath(source, target, sourceHeight, targetHeight) {
  const down = target.y >= source.y;
  const startY = source.y + (down ? sourceHeight / 2 : -sourceHeight / 2);
  const endY = target.y + (down ? -targetHeight / 2 : targetHeight / 2);
  const middleY = (startY + endY) / 2;
  return `M${source.x},${startY} C${source.x},${middleY} ${target.x},${middleY} ${target.x},${endY}`;
}

function selfLoopPath(node, association) {
  const nodeRight = node.x + METAMODEL_NODE_WIDTH / 2;
  const associationRight = association.x + association.width / 2;
  const bendX = Math.max(nodeRight, associationRight) + 56;
  const associationTop = association.y - association.height / 2;
  const associationBottom = association.y + association.height / 2;
  return `M${nodeRight},${node.y - 10} C${bendX},${node.y - 54} ${bendX},${associationTop} ${association.x},${associationTop} M${association.x},${associationBottom} C${bendX},${associationBottom} ${bendX},${node.y + 54} ${nodeRight},${node.y + 10}`;
}

function isConnected(edge, nodeId) {
  return edge.source === nodeId || edge.target === nodeId;
}

function viewportSize(container) {
  return { width: Math.max(container.clientWidth, 1), height: Math.max(container.clientHeight, 1) };
}

function truncate(value, max) {
  if (!value) return '';
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}
