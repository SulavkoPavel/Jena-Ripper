package org.jenaripper.service;

import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementOptional;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementUnion;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;
import org.jenaripper.dto.QueryRecommendation;
import org.jenaripper.dto.SparqlAlgebraDto;
import org.jenaripper.dto.SparqlQueryAnalysis;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SparqlQueryAnalyzer {
    public SparqlQueryAnalysis analyze(Query query) {
        Stats stats = new Stats();
        if (query.getQueryPattern() != null) {
            ElementWalker.walk(query.getQueryPattern(), new ElementVisitorBase() {
                @Override public void visit(ElementPathBlock block) { stats.accept(block); }
                @Override public void visit(ElementFilter filter) {
                    stats.filters++;
                    if (filter.getExpr().toString().toLowerCase(Locale.ROOT).contains("regex")) stats.regex = true;
                }
                @Override public void visit(ElementOptional optional) { stats.optionals++; }
                @Override public void visit(ElementUnion union) { stats.unions += Math.max(1, union.getElements().size() - 1); }
            });
        }

        boolean select = query.isSelectType();
        boolean selectStar = select && query.isQueryResultStar();
        boolean ordered = query.hasOrderBy();
        boolean grouped = query.hasGroupBy();
        List<String> aggregates = query.getAggregators().stream()
                .map(value -> value.getAggregator().getName().toUpperCase(Locale.ROOT))
                .distinct().toList();
        List<QueryRecommendation> recommendations = new ArrayList<>();
        if (select && !query.hasLimit()) recommendations.add(new QueryRecommendation("MISSING_LIMIT", ordered ? "CRITICAL" : "WARNING"));
        if (selectStar) recommendations.add(new QueryRecommendation("SELECT_STAR", "INFO"));
        if (query.isDistinct()) recommendations.add(new QueryRecommendation("DISTINCT", "INFO"));
        if (ordered) recommendations.add(new QueryRecommendation("ORDER_BY", query.hasLimit() ? "INFO" : "WARNING"));
        if (grouped || !aggregates.isEmpty()) recommendations.add(new QueryRecommendation("AGGREGATION", "INFO"));
        if (stats.regex) recommendations.add(new QueryRecommendation("REGEX_FILTER", "WARNING"));
        if (stats.optionals >= 3) recommendations.add(new QueryRecommendation("MANY_OPTIONALS", "WARNING", java.util.Map.of("count", stats.optionals)));
        if (stats.unions >= 3) recommendations.add(new QueryRecommendation("MANY_UNIONS", "WARNING", java.util.Map.of("count", stats.unions)));
        if (stats.recursivePaths > 0) recommendations.add(new QueryRecommendation("RECURSIVE_PROPERTY_PATH", "WARNING", java.util.Map.of("count", stats.recursivePaths)));
        if (stats.cartesian) recommendations.add(new QueryRecommendation("CARTESIAN_PRODUCT", "CRITICAL"));

        return new SparqlQueryAnalysis(
                stats.triples,
                stats.variables.size(),
                select ? query.getProjectVars().size() : 0,
                stats.filters,
                stats.optionals,
                stats.unions,
                stats.paths,
                selectStar,
                query.isDistinct(),
                ordered,
                grouped,
                aggregates,
                List.copyOf(recommendations));
    }

    public SparqlAlgebraDto algebra(Query query) {
        Op original = Algebra.compile(query);
        Op optimized = Algebra.optimize(original);
        return new SparqlAlgebraDto(original.toString(), optimized.toString());
    }

    private static final class Stats {
        int triples;
        int filters;
        int optionals;
        int unions;
        int paths;
        int recursivePaths;
        boolean regex;
        boolean cartesian;
        final Set<String> variables = new LinkedHashSet<>();

        void accept(ElementPathBlock block) {
            List<Set<String>> patterns = new ArrayList<>();
            block.patternElts().forEachRemaining(path -> {
                triples++;
                Set<String> patternVars = variables(path);
                variables.addAll(patternVars);
                patterns.add(patternVars);
                if (path.getPath() != null) {
                    paths++;
                    String value = path.getPath().toString();
                    if (value.contains("*") || value.contains("+")) recursivePaths++;
                }
            });
            if (patterns.size() > 1 && disconnected(patterns)) cartesian = true;
        }

        private static Set<String> variables(TriplePath path) {
            Set<String> result = new HashSet<>();
            add(path.getSubject(), result);
            add(path.getPredicate(), result);
            add(path.getObject(), result);
            return result;
        }

        private static void add(Node node, Set<String> target) {
            if (node != null && Var.isVar(node)) target.add(Var.alloc(node).getVarName());
        }

        private static boolean disconnected(List<Set<String>> patterns) {
            Set<Integer> reached = new HashSet<>();
            reached.add(0);
            boolean changed;
            do {
                changed = false;
                Set<String> known = new HashSet<>();
                reached.forEach(index -> known.addAll(patterns.get(index)));
                for (int i = 0; i < patterns.size(); i++) {
                    if (!reached.contains(i) && !java.util.Collections.disjoint(known, patterns.get(i))) {
                        reached.add(i);
                        changed = true;
                    }
                }
            } while (changed);
            return reached.size() != patterns.size();
        }
    }
}
