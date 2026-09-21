package org.jenaripper.jena;

import lombok.RequiredArgsConstructor;
import org.apache.jena.query.Dataset;
import org.apache.jena.system.Txn;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class JenaReadExecutor {
    private final Dataset dataset;

    public <T> T read(Supplier<T> action) {
        return Txn.calculateRead(dataset, action);
    }

    public Dataset dataset() {
        return dataset;
    }
}
