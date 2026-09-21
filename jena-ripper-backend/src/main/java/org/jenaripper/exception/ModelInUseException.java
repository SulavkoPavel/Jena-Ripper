package org.jenaripper.exception;

import java.util.List;

public class ModelInUseException extends UploadedModelException {
    private final List<ProfileReference> profiles;

    public ModelInUseException(List<ProfileReference> profiles) {
        super("Модель используется профилем подключения");
        this.profiles = List.copyOf(profiles);
    }

    public List<ProfileReference> profiles() {
        return profiles;
    }

    public record ProfileReference(String id, String name) {}
}
