package org.jenaripper.owner;

import java.util.List;

public record OwnerAssociation(
        String classId,
        String name,
        String inverseRoleName,
        List<String> ranges,
        String dataType) {
}
