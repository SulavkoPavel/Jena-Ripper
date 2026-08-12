package org.jenaripper.remote;

public class CimApiException extends RuntimeException {
    private final String type;

    public CimApiException(String type, String message) {
        super(message);
        this.type = type;
    }

    public String type() { return type; }
}
