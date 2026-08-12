package org.jenaripper.dto;

public record ConnectionTestResponse(boolean success, String type, String message, Boolean redisAvailable) {
    public ConnectionTestResponse(boolean success, String type, String message) {
        this(success, type, message, null);
    }
}
