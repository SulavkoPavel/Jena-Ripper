package org.jenaripper.redis;

public class RedisCommandException extends RuntimeException {
    private final String type;

    public RedisCommandException(String type, String message) {
        super(message);
        this.type = type;
    }

    public RedisCommandException(String type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public String type() { return type; }
}
