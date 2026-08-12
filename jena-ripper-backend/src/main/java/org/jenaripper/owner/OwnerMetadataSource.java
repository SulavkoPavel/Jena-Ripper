package org.jenaripper.owner;

import java.util.List;

public interface OwnerMetadataSource {
    List<OwnerAssociation> findVisibleByClass(String classId);
}
