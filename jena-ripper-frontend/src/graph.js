import * as d3 from 'd3';

export class RdfGraph {
  constructor(container, onSelect, onExpand) {
    this.container = container;
    this.onSelect = onSelect;
    this.onExpand = onExpand;
    this.allNodes = new Map();
    this.allEdges = new Map();
    this.nodes = new Map();
    this.edges = new Map();
    this.expansions = new Map();
    this.pinnedNodes = new Set();
    this.selectedId = null;
    this.width = container.clientWidth;
    this.height = container.clientHeight;

    this.svg = d3.select(container).append('svg').attr('viewBox', [0, 0, this.width, this.height]);
    this.root = this.svg.append('g');
    this.edgeLayer = this.root.append('g').attr('class', 'edges');
    this.edgeLabelLayer = this.root.append('g').attr('class', 'edge-labels');
    this.nodeLayer = this.root.append('g').attr('class', 'nodes');
    this.labelLayer = this.root.append('g').attr('class', 'node-labels');
    this.zoom = d3.zoom().scaleExtent([0.08, 6]).on('zoom', event => this.root.attr('transform', event.transform));
    this.svg.call(this.zoom).on('dblclick.zoom', null);
    this.svg.append('defs').append('marker')
      .attr('id', 'arrow').attr('viewBox', '0 -5 10 10').attr('refX', 46).attr('refY', 0)
      .attr('markerWidth', 7).attr('markerHeight', 7).attr('orient', 'auto')
      .append('path').attr('d', 'M0,-5L10,0L0,5');
    this.simulation = d3.forceSimulation()
      .force('link', d3.forceLink().id(node => node.id).distance(270).strength(0.55))
      .force('charge', d3.forceManyBody().strength(-900))
      .force('center', d3.forceCenter(this.width / 2, this.height / 2).strength(0.06))
      .force('collision', d3.forceCollide(92).strength(0.9));
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
        node.x = (anchor?.x ?? this.width / 2) + Math.cos(angle) * 130;
        node.y = (anchor?.y ?? this.height / 2) + Math.sin(angle) * 130;
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
    if (this.circles) this.circles.classed('selected', node => node.id === uri);
  }

  focus(uri) {
    const node = this.nodes.get(uri);
    if (!node) return false;
    this.select(uri);
    const scale = 1.15;
    this.svg.transition().duration(450).call(
      this.zoom.transform,
      d3.zoomIdentity.translate(this.width / 2 - node.x * scale, this.height / 2 - node.y * scale).scale(scale));
    return true;
  }

  render(alpha) {
    const nodes = [...this.nodes.values()];
    const edges = [...this.edges.values()];
    this.links = this.edgeLayer.selectAll('line').data(edges, edge => edge.id).join('line')
      .attr('marker-end', 'url(#arrow)');
    this.links.selectAll('title').data(edge => [edge]).join('title').text(edge => edge.predicate);
    this.edgeLabels = this.edgeLabelLayer.selectAll('text').data(edges, edge => edge.id).join('text')
      .text(edge => truncate(edge.label, 32));
    this.circles = this.nodeLayer.selectAll('circle').data(nodes, node => node.id).join('circle')
      .attr('r', 40)
      .attr('class', node => `node-${(node.nodeType || 'RESOURCE').toLowerCase()}`)
      .classed('selected', node => node.id === this.selectedId)
      .attr('tabindex', 0).attr('role', 'button')
      .on('click', (_, node) => this.onSelect(node))
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
        label.append('tspan').attr('x', 0).attr('dy', 0).attr('class', 'node-name').text(truncate(node.label, 28));
        label.append('tspan').attr('x', 0).attr('dy', '1.35em').attr('class', 'node-type')
          .text(shortType(node.types?.[0] || node.nodeType));
      });
    this.simulation.nodes(nodes).on('tick', () => this.tick());
    this.simulation.force('link').links(edges);
    this.simulation.alpha(alpha).restart();
  }

  tick() {
    this.links.attr('x1', edge => edge.source.x).attr('y1', edge => edge.source.y)
      .attr('x2', edge => edge.target.x).attr('y2', edge => edge.target.y);
    this.edgeLabels.attr('x', edge => (edge.source.x + edge.target.x) / 2)
      .attr('y', edge => (edge.source.y + edge.target.y) / 2 - 5);
    this.circles.attr('cx', node => node.x).attr('cy', node => node.y);
    this.nodeLabels.attr('transform', node => `translate(${node.x},${node.y - 3})`);
  }

}

function endpointId(endpoint) {
  return typeof endpoint === 'object' ? endpoint.id : endpoint;
}

function shortType(value) {
  if (!value) return 'Resource';
  const separator = value.indexOf(':');
  return truncate(separator >= 0 ? value.slice(separator + 1) : value, 24);
}

function truncate(value, max) {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}
