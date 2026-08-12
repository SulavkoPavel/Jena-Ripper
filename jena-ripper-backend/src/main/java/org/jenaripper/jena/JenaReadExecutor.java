package org.jenaripper.jena;

import org.apache.jena.query.Dataset;
import org.apache.jena.system.Txn;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class JenaReadExecutor {
    private final Dataset dataset;

    public JenaReadExecutor(Dataset dataset) {
        this.dataset = dataset;
    }

    public <T> T read(Supplier<T> action) {
        return Txn.calculateRead(dataset, action);
    }

    public Dataset dataset() {
        return dataset;
    }
}

