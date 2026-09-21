package org.jenaripper.exception;

public class OwnerRulesUnavailableException extends RuntimeException {
    public OwnerRulesUnavailableException(String message) {
        super(message);
    }

    public OwnerRulesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
