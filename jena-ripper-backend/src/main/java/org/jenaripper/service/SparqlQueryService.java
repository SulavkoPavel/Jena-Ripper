package org.jenaripper.service;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryCancelledException;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionBuilder;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.QueryExecException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.shared.JenaException;
import org.apache.jena.update.UpdateFactory;
import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.jenaripper.remote.CimApiClient;
import org.jenaripper.exception.CimApiException;
import org.jenaripper.exception.SparqlQueryException;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.jenaripper.dto.GraphEdgeDto;
import org.jenaripper.dto.GraphNodeDto;
import org.jenaripper.dto.SparqlAlgebraDto;
import org.jenaripper.dto.SparqlAnalyzeResponse;
import org.jenaripper.dto.SparqlBindingDto;
import org.jenaripper.dto.SparqlExecutionMetrics;
import org.jenaripper.dto.SparqlQueryAnalysis;
import org.jenaripper.dto.SparqlQueryResponse;
import org.jenaripper.dto.SparqlStatementDto;
import org.jenaripper.jena.JenaReadExecutor;
import org.jenaripper.owner.OwnerRulesModelReasoner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SparqlQueryService {
    private static final List<Long> DEFAULT_SPEED_THRESHOLDS_MS =
            List.of(100L, 500L, 2_000L, 10_000L);
    private static final int VERY_FAST_THRESHOLD_INDEX = 0;
    private static final int FAST_THRESHOLD_INDEX = 1;
    private static final int MEDIUM_THRESHOLD_INDEX = 2;
    private static final int SLOW_THRESHOLD_INDEX = 3;
    private final JenaReadExecutor executor;
    private final PrefixService prefixes;
    private final GraphMapper graphMapper;
    private final SparqlQueryAnalyzer analyzer;
    private final JenaRipperProperties.Sparql settings;
    private final RdfSourceProperties sourceProperties;
    private final CimApiClient cimApi;
    private final OwnerRulesModelReasoner ownerRulesReasoner;
    private final Map<String, ActiveQuery> activeQueries = new ConcurrentHashMap<>();

    public SparqlQueryService(
            JenaReadExecutor executor,
            PrefixService prefixes,
            GraphMapper graphMapper,
            SparqlQueryAnalyzer analyzer, JenaRipperProperties properties,
            RdfSourceProperties sourceProperties, CimApiClient cimApi,
            OwnerRulesModelReasoner ownerRulesReasoner) {
        this.executor = executor;
        this.prefixes = prefixes;
        this.graphMapper = graphMapper;
        this.analyzer = analyzer;
        this.settings = properties.sparql();
        this.sourceProperties = sourceProperties;
        this.cimApi = cimApi;
        this.ownerRulesReasoner = ownerRulesReasoner;
    }

    public SparqlQueryResponse execute(String source) {
        return execute(source, null, false);
    }

    public SparqlQueryResponse execute(String source, String requestId) {
        return execute(source, requestId, false);
    }

    public SparqlQueryResponse execute(String source, String requestId, boolean useOwnerRules) {
        Query query = parse(source);
        ensureReadQuery(query);
        ActiveQuery active = new ActiveQuery(normalizeRequestId(requestId));
        if (active.requestId != null && activeQueries.putIfAbsent(active.requestId, active) != null) {
            throw new SparqlQueryException("SPARQL_REQUEST_CONFLICT", "Запрос с таким идентификатором уже выполняется.", null, null);
        }
        long started = System.nanoTime();
        try {
            active.thread = Thread.currentThread();
            if (sourceProperties.remote()) return executeRemote(query, source, started, active, useOwnerRules);
            return executor.read(() -> executeRead(query, started, active, useOwnerRules));
        } catch (QueryCancelledException exception) {
            if (active.cancelled.get()) {
                throw new SparqlQueryException("SPARQL_CANCELLED", "Запрос отменён пользователем.", null, null);
            }
            throw new SparqlQueryException(
                    "SPARQL_TIMEOUT",
                    "Запрос выполнялся слишком долго и был остановлен. Лимит: " + settings.timeout().toSeconds() + " секунд.",
                    null, null);
        } catch (QueryExecException exception) {
            throw new SparqlQueryException("SPARQL_EXECUTION_ERROR", cleanMessage(exception.getMessage()), null, null);
        } catch (JenaException exception) {
            throw new SparqlQueryException("SPARQL_EXECUTION_ERROR", cleanMessage(exception.getMessage()), null, null);
        } catch (CimApiException exception) {
            if (active.cancelled.get() || Thread.currentThread().isInterrupted()) {
                Thread.interrupted();
                throw new SparqlQueryException("SPARQL_CANCELLED", "Запрос отменён пользователем.", null, null);
            }
            throw new SparqlQueryException(exception.type(), exception.getMessage(), null, null);
        } finally {
            if (active.requestId != null) activeQueries.remove(active.requestId, active);
        }
    }

    public boolean cancel(String requestId) {
        ActiveQuery active = activeQueries.get(normalizeRequestId(requestId));
        if (active == null) return false;
        active.cancelled.set(true);
        QueryExecution execution = active.execution;
        if (execution != null) execution.abort();
        Thread thread = active.thread;
        if (thread != null) thread.interrupt();
        return true;
    }

    private SparqlQueryResponse executeRemote(Query query, String source, long started, ActiveQuery active,
                                              boolean useOwnerRules) {
        SparqlQueryAnalysis analysis = analyzer.analyze(query);
        SparqlAlgebraDto algebra = analyzer.algebra(query);
        if (query.isDescribeType()) {
            throw new SparqlQueryException("UNSUPPORTED_QUERY",
                    "CIM App API не предоставляет endpoint для DESCRIBE.", null, null);
        }
        if (query.isAskType()) {
            if (useOwnerRules) throw new SparqlQueryException("UNSUPPORTED_QUERY",
                    "CIM App API не поддерживает useOwnerRules для ASK.", null, null);
            boolean value = cimApi.ask(source);
            Timing timing = new Timing(); timing.execution = System.nanoTime() - started;
            SparqlExecutionMetrics metrics = metrics(started, timing, 1);
            return new SparqlQueryResponse("ASK", null, null, value, null, null, null, null,
                    metrics.executionTimeMs(), false, 0, metrics, analysis, algebra);
        }
        if (query.isSelectType()) {
            int limit = settings.maxSelectRows();
            List<Map<String, String>> raw = cimApi.select(source, limit + 1, useOwnerRules);
            boolean truncated = raw.size() > limit;
            List<Map<String, String>> visible = truncated ? raw.subList(0, limit) : raw;
            List<String> variables = new ArrayList<>();
            visible.forEach(row -> row.keySet().stream().filter(key -> !key.endsWith(".isBlank"))
                    .forEach(key -> { if (!variables.contains(key)) variables.add(key); }));
            List<Map<String, SparqlBindingDto>> rows = new ArrayList<>();
            for (Map<String, String> rawRow : visible) {
                Map<String, SparqlBindingDto> row = new LinkedHashMap<>();
                for (String variable : variables) if (rawRow.get(variable) != null)
                    row.put(variable, remoteBinding(rawRow.get(variable), rawRow.get(variable + ".isBlank")));
                rows.add(row);
            }
            Timing timing = new Timing(); timing.execution = System.nanoTime() - started;
            SparqlExecutionMetrics metrics = metrics(started, timing, rows.size());
            return new SparqlQueryResponse("SELECT", variables, rows, null, null, null, null, null,
                    metrics.executionTimeMs(), truncated, limit, metrics, analysis, algebra);
        }
        String turtle = cimApi.construct(source, settings.maxGraphTriples() + 1, useOwnerRules);
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(turtle).lang(Lang.TURTLE).parse(model);
        List<Triple> triples = new ArrayList<>();
        model.getGraph().find().forEachRemaining(triples::add);
        boolean truncated = triples.size() > settings.maxGraphTriples();
        if (truncated) triples = new ArrayList<>(triples.subList(0, settings.maxGraphTriples()));
        List<SparqlStatementDto> statements = triples.stream().map(triple -> new SparqlStatementDto(binding(triple.getSubject()),
                binding(triple.getPredicate()), binding(triple.getObject()))).toList();
        GraphParts parts = graphParts(triples);
        Timing timing = new Timing(); timing.execution = System.nanoTime() - started;
        SparqlExecutionMetrics metrics = metrics(started, timing, triples.size());
        return new SparqlQueryResponse("CONSTRUCT", null, null, null, (long) triples.size(), statements,
                parts.nodes(), parts.edges(), metrics.executionTimeMs(), truncated, settings.maxGraphTriples(),
                metrics, analysis, algebra);
    }

    private SparqlBindingDto remoteBinding(String value, String blank) {
        if ("true".equalsIgnoreCase(blank) || value.startsWith("_:"))
            return new SparqlBindingDto("bnode", value, value, null, null);
        String expanded = prefixes.expand(value);
        if (!expanded.equals(value) || value.matches("[a-zA-Z][a-zA-Z0-9+.-]*:.*"))
            return new SparqlBindingDto("uri", expanded, prefixes.compact(expanded), null, null);
        return new SparqlBindingDto("literal", value, value, null, null);
    }

    public SparqlAnalyzeResponse analyze(String source) {
        Query query = parse(source);
        ensureReadQuery(query);
        return new SparqlAnalyzeResponse(queryType(query), analyzer.analyze(query), analyzer.algebra(query));
    }

    public Map<String, String> prefixes() {
        return prefixes.prefixes();
    }

    private Query parse(String source) {
        if (source == null || source.isBlank()) {
            throw new SparqlQueryException("SPARQL_PARSE_ERROR", "Введите SPARQL-запрос.", 1, 1);
        }
        try {
            return QueryFactory.create(source);
        } catch (QueryParseException exception) {
            if (isUpdate(source)) {
                throw new SparqlQueryException(
                        "SPARQL_UPDATE_DISABLED", "Изменение Dataset через SPARQL пока отключено.",
                        exception.getLine() > 0 ? exception.getLine() : null,
                        exception.getColumn() > 0 ? exception.getColumn() : null);
            }
            throw new SparqlQueryException(
                    "SPARQL_PARSE_ERROR", cleanMessage(exception.getMessage()),
                    parsePosition(exception.getLine(), exception.getMessage(), "line"),
                    parsePosition(exception.getColumn(), exception.getMessage(), "column"));
        }
    }

    private boolean isUpdate(String source) {
        try {
            UpdateFactory.create(source);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private SparqlQueryResponse executeRead(Query query, long started, ActiveQuery active, boolean useOwnerRules) {
        SparqlQueryAnalysis analysis = analyzer.analyze(query);
        SparqlAlgebraDto algebra = analyzer.algebra(query);
        QueryExecutionBuilder builder = useOwnerRules
                ? QueryExecution.model(ownerRulesReasoner.apply(executor.dataset().getDefaultModel())).query(query)
                : QueryExecution.dataset(executor.dataset()).query(query);
        try (QueryExecution execution = builder.timeout(settings.timeout().toMillis(), TimeUnit.MILLISECONDS).build()) {
            active.execution = execution;
            if (active.cancelled.get()) execution.abort();
            if (query.isSelectType()) return select(execution, started, analysis, algebra);
            if (query.isAskType()) return ask(execution, started, analysis, algebra);
            return graph(execution, queryType(query), query.isDescribeType(), started, analysis, algebra);
        }
    }

    private SparqlQueryResponse select(QueryExecution execution, long started, SparqlQueryAnalysis analysis, SparqlAlgebraDto algebra) {
        Timing timing = new Timing();
        long stage = System.nanoTime();
        ResultSet result = execution.execSelect();
        timing.execution += System.nanoTime() - stage;
        List<String> variables = List.copyOf(result.getResultVars());
        List<Map<String, SparqlBindingDto>> rows = new ArrayList<>();
        int limit = settings.maxSelectRows();
        while (rows.size() < limit) {
            stage = System.nanoTime();
            boolean hasNext = result.hasNext();
            timing.execution += System.nanoTime() - stage;
            if (!hasNext) break;
            stage = System.nanoTime();
            QuerySolution solution = result.nextSolution();
            timing.execution += System.nanoTime() - stage;
            stage = System.nanoTime();
            Map<String, SparqlBindingDto> row = new LinkedHashMap<>();
            for (String variable : variables) {
                RDFNode value = solution.get(variable);
                if (value != null) row.put(variable, binding(value.asNode()));
            }
            rows.add(row);
            timing.serialization += System.nanoTime() - stage;
        }
        stage = System.nanoTime();
        boolean truncated = result.hasNext();
        timing.execution += System.nanoTime() - stage;
        SparqlExecutionMetrics metrics = metrics(started, timing, rows.size());
        return new SparqlQueryResponse(
                "SELECT", variables, rows, null, null, null, null, null,
                metrics.executionTimeMs(), truncated, limit, metrics, analysis, algebra);
    }

    private SparqlQueryResponse ask(QueryExecution execution, long started, SparqlQueryAnalysis analysis, SparqlAlgebraDto algebra) {
        long stage = System.nanoTime();
        boolean value = execution.execAsk();
        Timing timing = new Timing();
        timing.execution = System.nanoTime() - stage;
        SparqlExecutionMetrics metrics = metrics(started, timing, 1);
        return new SparqlQueryResponse(
                "ASK", null, null, value, null, null, null, null,
                metrics.executionTimeMs(), false, 0, metrics, analysis, algebra);
    }

    private SparqlQueryResponse graph(QueryExecution execution, String type, boolean describe, long started, SparqlQueryAnalysis analysis, SparqlAlgebraDto algebra) {
        Timing timing = new Timing();
        long stage = System.nanoTime();
        Iterator<Triple> iterator = describe ? execution.execDescribeTriples() : execution.execConstructTriples();
        timing.execution += System.nanoTime() - stage;
        int limit = settings.maxGraphTriples();
        List<Triple> triples = new ArrayList<>();
        while (triples.size() < limit) {
            stage = System.nanoTime();
            boolean hasNext = iterator.hasNext();
            if (hasNext) triples.add(iterator.next());
            timing.execution += System.nanoTime() - stage;
            if (!hasNext) break;
        }
        stage = System.nanoTime();
        boolean truncated = iterator.hasNext();
        timing.execution += System.nanoTime() - stage;

        stage = System.nanoTime();
        List<SparqlStatementDto> statements = triples.stream()
                .map(triple -> new SparqlStatementDto(
                        binding(triple.getSubject()), binding(triple.getPredicate()), binding(triple.getObject())))
                .toList();
        GraphParts parts = graphParts(triples);
        timing.serialization += System.nanoTime() - stage;
        SparqlExecutionMetrics metrics = metrics(started, timing, triples.size());
        return new SparqlQueryResponse(
                type, null, null, null, (long) triples.size(), statements, parts.nodes(), parts.edges(),
                metrics.executionTimeMs(), truncated, limit, metrics, analysis, algebra);
    }

    private GraphParts graphParts(List<Triple> triples) {
        Set<Node> resources = new LinkedHashSet<>();
        Map<Node, List<Quad>> outgoing = new LinkedHashMap<>();
        Map<String, GraphEdgeDto> edges = new LinkedHashMap<>();
        for (Triple triple : triples) {
            Node subject = triple.getSubject();
            resources.add(subject);
            outgoing.computeIfAbsent(subject, ignored -> new ArrayList<>())
                    .add(new Quad(Quad.defaultGraphNodeGenerated, triple));
            if (triple.getObject().isURI() || triple.getObject().isBlank()) resources.add(triple.getObject());
            if ((triple.getObject().isURI() || triple.getObject().isBlank())
                    && !triple.getPredicate().getURI().equals(org.apache.jena.vocabulary.RDF.type.getURI())) {
                GraphEdgeDto edge = graphMapper.edge(subject, triple.getPredicate(), triple.getObject(), "outgoing");
                edges.putIfAbsent(edge.id(), edge);
            }
        }
        List<GraphNodeDto> nodes = resources.stream()
                .map(node -> graphMapper.toNode(node, outgoing.getOrDefault(node, List.of())))
                .toList();
        return new GraphParts(nodes, new ArrayList<>(edges.values()));
    }

    private SparqlBindingDto binding(Node node) {
        if (node.isURI()) return new SparqlBindingDto("uri", node.getURI(), prefixes.compact(node.getURI()), null, null);
        if (node.isBlank()) {
            String value = "_:" + node.getBlankNodeLabel();
            return new SparqlBindingDto("bnode", value, value, null, null);
        }
        if (node.isLiteral()) {
            return new SparqlBindingDto("literal", node.getLiteralLexicalForm(), node.getLiteralLexicalForm(),
                    node.getLiteralDatatypeURI(), node.getLiteralLanguage());
        }
        return new SparqlBindingDto("unknown", node.toString(), node.toString(), null, null);
    }

    private void ensureReadQuery(Query query) {
        if (!(query.isSelectType() || query.isAskType() || query.isConstructType() || query.isDescribeType())) {
            throw new SparqlQueryException("UNSUPPORTED_QUERY", "Поддерживаются только SELECT, ASK, CONSTRUCT и DESCRIBE.", null, null);
        }
    }

    private static String queryType(Query query) {
        if (query.isSelectType()) return "SELECT";
        if (query.isAskType()) return "ASK";
        if (query.isDescribeType()) return "DESCRIBE";
        return "CONSTRUCT";
    }

    private SparqlExecutionMetrics metrics(long started, Timing timing, long resultCount) {
        long executionMs = TimeUnit.NANOSECONDS.toMillis(timing.execution);
        long serializationMs = TimeUnit.NANOSECONDS.toMillis(timing.serialization);
        long totalMs = elapsed(started);
        return new SparqlExecutionMetrics(executionMs, serializationMs, totalMs, resultCount, "SUCCESS", speed(totalMs));
    }

    private String speed(long totalMs) {
        List<Long> thresholds = settings.speedThresholdsMs();
        if (thresholds == null || thresholds.size() < DEFAULT_SPEED_THRESHOLDS_MS.size()) {
            thresholds = DEFAULT_SPEED_THRESHOLDS_MS;
        }
        if (totalMs < thresholds.get(VERY_FAST_THRESHOLD_INDEX)) return "VERY_FAST";
        if (totalMs < thresholds.get(FAST_THRESHOLD_INDEX)) return "FAST";
        if (totalMs < thresholds.get(MEDIUM_THRESHOLD_INDEX)) return "MEDIUM";
        if (totalMs < thresholds.get(SLOW_THRESHOLD_INDEX)) return "SLOW";
        return "VERY_SLOW";
    }

    private static String cleanMessage(String message) {
        if (message == null || message.isBlank()) return "Не удалось разобрать SPARQL-запрос.";
        return message.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static Integer parsePosition(int reported, String message, String label) {
        if (reported > 0) return reported;
        if (message == null) return null;
        Matcher matcher = java.util.regex.Pattern.compile("(?i)" + label + "\\s+(\\d+)").matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        try {
            return java.util.UUID.fromString(requestId).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Некорректный requestId SPARQL-запроса.");
        }
    }

    private static final class Timing { long execution; long serialization; }
    private static final class ActiveQuery {
        final String requestId;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile QueryExecution execution;
        volatile Thread thread;
        ActiveQuery(String requestId) { this.requestId = requestId; }
    }
    private record GraphParts(List<GraphNodeDto> nodes, List<GraphEdgeDto> edges) {}
}
