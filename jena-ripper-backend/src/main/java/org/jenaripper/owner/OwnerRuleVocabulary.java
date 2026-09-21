package org.jenaripper.owner;

import java.util.Set;

public final class OwnerRuleVocabulary {
    public static final String NAMESPACE = "http://so-ups.ru/2015/schema-cim16#";
    public static final Set<String> ASSET_OWNER_PREDICATES = Set.of(
            NAMESPACE + "Object.OwnersToBottom",
            NAMESPACE + "Object.OwnersToTop");
    public static final Set<String> DATA_SOURCE_PREDICATES = Set.of(
            NAMESPACE + "Object.DataSourceToBottom",
            NAMESPACE + "Object.DataSourceToTop");
    public static final Set<String> REMOTE_OWNER_PREDICATES = Set.of(
            NAMESPACE + "Object.OwnersToBottom",
            NAMESPACE + "Object.OwnersToTop",
            NAMESPACE + "Object.DataSourceToBottom",
            NAMESPACE + "Object.DataSourceToTop");
    public static final Set<String> INFERRED_PREDICATES = Set.of(
            NAMESPACE + "Object.OwnersToBottom",
            NAMESPACE + "Object.OwnersToTop",
            NAMESPACE + "Object.DataSourceToBottom",
            NAMESPACE + "Object.DataSourceToTop",
            NAMESPACE + "HasDirectAssetOwner",
            NAMESPACE + "HasNotDirectAssetOwner",
            NAMESPACE + "HasDirectAssetDataSource");

    private OwnerRuleVocabulary() {
    }
}
