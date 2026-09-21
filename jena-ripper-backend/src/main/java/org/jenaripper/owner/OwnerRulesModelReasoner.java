package org.jenaripper.owner;

import lombok.RequiredArgsConstructor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OwnerRulesModelReasoner {
    private final OwnerRuleGenerator ruleGenerator;

    public Model apply(Model model) {
        OwnerRuleBundle rules = ruleGenerator.rules();
        Model directAssets = ModelFactory.createInfModel(
                new GenericRuleReasoner(rules.directAssetRules()), model);
        return ModelFactory.createInfModel(
                new GenericRuleReasoner(rules.ownerRules()), directAssets);
    }
}
