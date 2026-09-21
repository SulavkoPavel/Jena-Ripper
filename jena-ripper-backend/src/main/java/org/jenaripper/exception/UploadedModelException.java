package org.jenaripper.exception;

public class UploadedModelException extends RuntimeException {
    public UploadedModelException(String message) {
        super(message);
    }

    public UploadedModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
