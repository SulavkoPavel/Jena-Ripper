package org.jenaripper.owner;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.springframework.stereotype.Component;

@Component
public class OwnerRulesModelReasoner {
    private final OwnerRuleGenerator ruleGenerator;

    public OwnerRulesModelReasoner(OwnerRuleGenerator ruleGenerator) {
        this.ruleGenerator = ruleGenerator;
    }

    public Model apply(Model model) {
        OwnerRuleBundle rules = ruleGenerator.rules();
        Model directAssets = ModelFactory.createInfModel(
                new GenericRuleReasoner(rules.directAssetRules()), model);
        return ModelFactory.createInfModel(
                new GenericRuleReasoner(rules.ownerRules()), directAssets);
    }
}
