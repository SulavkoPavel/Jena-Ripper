package org.jenaripper.dto;

public record ConnectionSettingsSaveResponse(boolean success, boolean restartRequired, String message) {
}
