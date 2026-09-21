package org.jenaripper.exception;

public class RedisRulesUnavailableException extends RuntimeException {
    private final boolean timeout;

    public RedisRulesUnavailableException(String message, boolean timeout, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
    }

    public boolean timeout() {
        return timeout;
    }
}
