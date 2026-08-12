package org.jenaripper.config;

import org.springframework.stereotype.Component;

@Component
public class DatasetRuntimeState {
    private volatile boolean fallback;
    private volatile String error;

    public void fallback(String message) {
        this.fallback = true;
        this.error = message;
    }

    public boolean fallback() { return fallback; }
    public String error() { return error; }
}
