import * as d3 from 'd3';

const MIN_ZOOM = 0.08;
const MAX_ZOOM = 6;
const NODE_RADIUS = 40;
const LINK_DISTANCE = 270;
const LINK_STRENGTH = 0.55;
const CHARGE_STRENGTH = -900;
const CENTER_STRENGTH = 0.06;
const COLLISION_RADIUS = 92;
const COLLISION_STRENGTH = 0.9;
const INITIAL_NODE_RADIUS = 130;
const FOCUS_SCALE = 1.15;
const MAX_FIT_SCALE = 2;
const FIT_PADDING = 55;
const FIT_VIEWPORT_FILL = 0.9;
const VIEW_TRANSITION_MS = 450;
const EDGE_LABEL_LENGTH = 32;
const NODE_LABEL_LENGTH = 28;
const TYPE_LABEL_LENGTH = 24;

export class RdfGraph {
  constructor(container, onSelect, onExpand, onEdgeSelect = () => {}) {
    this.container = container;
    this.onSelect = onSelect;
    this.onExpand = onExpand;
    this.onEdgeSelect = onEdgeSelect;
    this.allNodes = new Map();
    this.allEdges = new Map();
    this.nodes = new Map();
    this.edges = new Map();
    this.expansions = new Map();
    this.pinnedNodes = new Set();
    this.selectedId = null;
    this.selectedEdgeId = null;
    this.width = container.clientWidth;
    this.height = container.clientHeight;

    this.svg = d3.select(container).append('svg').attr('viewBox', [0, 0, this.width, this.height]);
    this.root = this.svg.append('g');
    this.edgeLayer = this.root.append('g').attr('class', 'edges');
    this.edgeLabelLayer = this.root.append('g').attr('class', 'edge-labels');
    this.nodeLayer = this.root.append('g').attr('class', 'nodes');
    this.labelLayer = this.root.append('g').attr('class', 'node-labels');
    this.zoom = d3.zoom().scaleExtent([MIN_ZOOM, MAX_ZOOM])
      .on('zoom', event => this.root.attr('transform', event.transform));
    this.svg.call(this.zoom).on('dblclick.zoom', null);
    this.markerId = `arrow-${++graphSequence}`;
    this.svg.append('defs').append('marker')
      .attr('id', this.markerId).attr('viewBox', '0 -5 10 10').attr('refX', 46).attr('refY', 0)
      .attr('markerWidth', 7).attr('markerHeight', 7).attr('orient', 'auto')
      .append('path').attr('d', 'M0,-5L10,0L0,5');
    this.simulation = d3.forceSimulation()
      .force('link', d3.forceLink().id(node => node.id).distance(LINK_DISTANCE).strength(LINK_STRENGTH))
      .force('charge', d3.forceManyBody().strength(CHARGE_STRENGTH))
      .force('center', d3.forceCenter(this.width / 2, this.height / 2).strength(CENTER_STRENGTH))
      .force('collision', d3.forceCollide(COLLISION_RADIUS).strength(COLLISION_STRENGTH));
  }

  hasNode(uri) {
    return this.nodes.has(uri);
  }

  merge(response, anchorId) {
    const knownCount = this.nodes.size;
    const anchorAlreadyVisible = this.nodes.has(anchorId);
    const anchor = this.allNodes.get(anchorId);
    response.nodes.forEach((node, index) => {
      const existing = this.allNodes.get(node.id);
      if (existing) {
        Object.assign(existing, node);
      } else {
        const angle = (index / Math.max(response.nodes.length, 1)) * Math.PI * 2;
        node.x = (anchor?.x ?? this.width / 2) + Math.cos(angle) * INITIAL_NODE_RADIUS;
        node.y = (anchor?.y ?? this.height / 2) + Math.sin(angle) * INITIAL_NODE_RADIUS;
        this.allNodes.set(node.id, node);
      }
    });
    if (!anchorAlreadyVisible) this.pinnedNodes.add(anchorId);
    response.edges.forEach(edge => {
      const existing = this.allEdges.get(edge.id);
      if (existing) Object.assign(existing, edge);
      else this.allEdges.set(edge.id, edge);
    });
    this.expansions.set(anchorId, {
      addedNodes: new Set(response.nodes.map(node => node.id).filter(id => id !== anchorId)),
      addedEdges: new Set(response.edges.map(edge => edge.id))
    });
    this.recomputeVisibility();
    this.render(knownCount === 0 ? 1 : 0.45);
  }

  load(response) {
    this.clear();
    response.nodes.forEach((node, index) => {
      const angle = (index / Math.max(response.nodes.length, 1)) * Math.PI * 2;
      node.x = this.width / 2 + Math.cos(angle) * Math.min(260, 80 + response.nodes.length * 8);
      node.y = this.height / 2 + Math.sin(angle) * Math.min(260, 80 + response.nodes.length * 8);
      this.allNodes.set(node.id, node);
      this.pinnedNodes.add(node.id);
    });
    response.edges.forEach(edge => this.allEdges.set(edge.id, edge));
    this.nodes = new Map(this.allNodes);
    this.edges = new Map(this.allEdges);
    this.render(1);
  }

  canCollapse(uri) {
    const expansion = this.expansions.get(uri);
    return Boolean(expansion && (
      [...expansion.addedNodes].some(id => this.nodes.has(id))
      || [...expansion.addedEdges].some(id => this.edges.has(id))));
  }

  collapse(uri) {
    if (!this.expansions.delete(uri)) return false;
    this.recomputeVisibility();
    this.render(0.35);
    return true;
  }

  clear() {
    this.expansions.clear();
    this.pinnedNodes.clear();
    this.allNodes.clear();
    this.allEdges.clear();
    this.nodes.clear();
    this.edges.clear();
    this.selectedId = null;
    this.selectedEdgeId = null;
    this.render(0);
  }

  destroy() {
    this.clear();
    this.simulation.stop();
    this.svg.remove();
  }

  isEmpty() {
    return this.nodes.size === 0;
  }

  recomputeVisibility() {
    const visibleNodes = new Set([...this.pinnedNodes].filter(id => this.allNodes.has(id)));
    let changed = true;
    while (changed) {
      changed = false;
      for (const [parentId, expansion] of this.expansions) {
        if (!visibleNodes.has(parentId)) continue;
        for (const nodeId of expansion.addedNodes) {
          if (this.allNodes.has(nodeId) && !visibleNodes.has(nodeId)) {
            visibleNodes.add(nodeId);
            changed = true;
          }
        }
      }
    }

    for (const parentId of [...this.expansions.keys()]) {
      if (!visibleNodes.has(parentId)) this.expansions.delete(parentId);
    }

    const visibleEdges = new Set();
    for (const [parentId, expansion] of this.expansions) {
      if (!visibleNodes.has(parentId)) continue;
      expansion.addedEdges.forEach(id => visibleEdges.add(id));
    }

    this.nodes = new Map([...this.allNodes].filter(([id]) => visibleNodes.has(id)));
    this.edges = new Map([...this.allEdges].filter(([id, edge]) =>
      visibleEdges.has(id) && visibleNodes.has(endpointId(edge.source)) && visibleNodes.has(endpointId(edge.target))));

    this.allNodes = new Map(this.nodes);
    this.allEdges = new Map(this.edges);
    if (this.selectedId && !this.nodes.has(this.selectedId)) this.selectedId = null;
  }

  select(uri) {
    this.selectedId = uri;
    this.selectedEdgeId = null;
    this.updateSelection();
  }

  selectEdge(id) {
    this.selectedId = null;
    this.selectedEdgeId = id;
    this.updateSelection();
  }

  updateSelection() {
    const connected = new Set();
    if (this.selectedId) {
      connected.add(this.selectedId);
      this.edges.forEach(edge => {
        const source = endpointId(edge.source);
        const target = endpointId(edge.target);
        if (source === this.selectedId) connected.add(target);
        if (target === this.selectedId) connected.add(source);
      });
    }
    if (this.circles) this.circles
      .classed('selected', node => node.id === this.selectedId)
      .classed('connected', node => connected.has(node.id) && node.id !== this.selectedId);
    if (this.links) this.links
      .classed('selected', edge => edge.id === this.selectedEdgeId)
      .classed('connected', edge => this.selectedId &&
        (endpointId(edge.source) === this.selectedId || endpointId(edge.target) === this.selectedId));
    if (this.edgeLabels) this.edgeLabels
      .classed('selected', edge => edge.id === this.selectedEdgeId)
      .classed('connected', edge => this.selectedId &&
        (endpointId(edge.source) === this.selectedId || endpointId(edge.target) === this.selectedId));
  }

  focus(uri) {
    const node = this.nodes.get(uri);
    if (!node) return false;
    this.select(uri);
    const scale = FOCUS_SCALE;
    this.svg.transition().duration(VIEW_TRANSITION_MS).call(
      this.zoom.transform,
      d3.zoomIdentity.translate(this.width / 2 - node.x * scale, this.height / 2 - node.y * scale).scale(scale));
    return true;
  }

  fit(duration = VIEW_TRANSITION_MS) {
    const nodes = [...this.nodes.values()].filter(node => Number.isFinite(node.x) && Number.isFinite(node.y));
    if (!nodes.length) return false;
    const minX = Math.min(...nodes.map(node => node.x - FIT_PADDING));
    const maxX = Math.max(...nodes.map(node => node.x + FIT_PADDING));
    const minY = Math.min(...nodes.map(node => node.y - FIT_PADDING));
    const maxY = Math.max(...nodes.map(node => node.y + FIT_PADDING));
    const scale = Math.min(MAX_FIT_SCALE, Math.max(MIN_ZOOM,
      FIT_VIEWPORT_FILL / Math.max((maxX - minX) / this.width, (maxY - minY) / this.height)));
    const centerX = (minX + maxX) / 2;
    const centerY = (minY + maxY) / 2;
    this.svg.transition().duration(duration).call(this.zoom.transform,
      d3.zoomIdentity.translate(this.width / 2 - centerX * scale, this.height / 2 - centerY * scale).scale(scale));
    return true;
  }

  render(alpha) {
    const nodes = [...this.nodes.values()];
    const edges = [...this.edges.values()];
    this.links = this.edgeLayer.selectAll('path').data(edges, edge => edge.id).join('path')
      .attr('marker-end', `url(#${this.markerId})`)
      .on('click', (event, edge) => {
        event.stopPropagation();
        this.selectEdge(edge.id);
        this.onEdgeSelect(edge);
      });
    this.links.selectAll('title').data(edge => [edge]).join('title').text(edge => edge.predicate);
    this.edgeLabels = this.edgeLabelLayer.selectAll('text').data(edges, edge => edge.id).join('text')
      .text(edge => truncate(edge.label, EDGE_LABEL_LENGTH));
    this.circles = this.nodeLayer.selectAll('circle').data(nodes, node => node.id).join('circle')
      .attr('r', NODE_RADIUS)
      .attr('class', node => `node-${(node.nodeType || 'RESOURCE').toLowerCase()}`)
      .classed('selected', node => node.id === this.selectedId)
      .attr('tabindex', 0).attr('role', 'button')
      .on('click', (_, node) => this.onSelect(node))
      .on('mouseenter', (_, node) => this.highlightHover(node.id))
      .on('mouseleave', () => this.highlightHover(null))
      .on('dblclick', (event, node) => { event.stopPropagation(); this.onExpand(node); })
      .on('keydown', (event, node) => { if (event.key === 'Enter') this.onExpand(node); })
      .call(d3.drag()
        .on('start', (event, node) => { if (!event.active) this.simulation.alphaTarget(0.2).restart(); node.fx = node.x; node.fy = node.y; })
        .on('drag', (event, node) => { node.fx = event.x; node.fy = event.y; })
        .on('end', (event, node) => { if (!event.active) this.simulation.alphaTarget(0); node.fx = null; node.fy = null; }));
    this.circles.selectAll('title').data(node => [node]).join('title')
      .text(node => `${node.label}\n${node.types?.join(', ') || 'RDF Resource'}\n${node.compactUri}`);
    this.nodeLabels = this.labelLayer.selectAll('text').data(nodes, node => node.id).join('text')
      .each(function (node) {
        const label = d3.select(this);
        label.selectAll('*').remove();
        label.append('tspan').attr('x', 0).attr('dy', 0).attr('class', 'node-name')
          .text(truncate(node.label, NODE_LABEL_LENGTH));
        label.append('tspan').attr('x', 0).attr('dy', '1.35em').attr('class', 'node-type')
          .text(shortType(node.types?.[0] || node.nodeType));
      });
    this.simulation.nodes(nodes).on('tick', () => this.tick());
    this.simulation.force('link').links(edges);
    this.simulation.alpha(alpha).restart();
    this.updateSelection();
  }

  highlightHover(id) {
    if (!this.links) return;
    this.links.classed('hover-connected', edge => id &&
      (endpointId(edge.source) === id || endpointId(edge.target) === id));
  }

  tick() {
    this.links.attr('d', edgePath);
    this.edgeLabels.attr('x', edge => edge.source.id === edge.target.id
      ? edge.source.x + 92 : (edge.source.x + edge.target.x) / 2)
      .attr('y', edge => edge.source.id === edge.target.id
        ? edge.source.y - 8 : (edge.source.y + edge.target.y) / 2 - 5);
    this.circles.attr('cx', node => node.x).attr('cy', node => node.y);
    this.nodeLabels.attr('transform', node => `translate(${node.x},${node.y - 3})`);
  }

}

let graphSequence = 0;

function edgePath(edge) {
  if (edge.source.id === edge.target.id) {
    const x = edge.source.x;
    const y = edge.source.y;
    return `M${x + 28},${y - 28} C${x + 92},${y - 100} ${x + 108},${y + 72} ${x + 33},${y + 25}`;
  }
  return `M${edge.source.x},${edge.source.y} L${edge.target.x},${edge.target.y}`;
}

function endpointId(endpoint) {
  return typeof endpoint === 'object' ? endpoint.id : endpoint;
}

function shortType(value) {
  if (!value) return 'Resource';
  const separator = value.indexOf(':');
  return truncate(separator >= 0 ? value.slice(separator + 1) : value, TYPE_LABEL_LENGTH);
}

function truncate(value, max) {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}
