package org.jenaripper.settings;

import java.nio.file.Path;

public final class ConnectionProfilePaths {
    public static final String UPLOADED_MODELS_DIRECTORY = "uploaded-models";
    public static final String MODEL_DATASET_DIRECTORY = "dataset";
    private static final String PROFILES_FILE_NAME = "profiles.json";

    private ConnectionProfilePaths() {}

    public static Path resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "jena-ripper", PROFILES_FILE_NAME).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".jena-ripper", PROFILES_FILE_NAME)
                .toAbsolutePath().normalize();
    }
}
