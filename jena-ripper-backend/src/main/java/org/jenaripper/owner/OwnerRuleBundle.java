package org.jenaripper.owner;

import org.apache.jena.reasoner.rulesys.Rule;

import java.util.List;

public record OwnerRuleBundle(List<Rule> directAssetRules, List<Rule> ownerRules) {
}
