package org.jenaripper.dto;

public record CreateConnectionProfileRequest(String name, Boolean copyCurrent, String sourceProfileId) {}
