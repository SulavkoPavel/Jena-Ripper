package org.jenaripper.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record ConnectionProfilesImportRequest(JsonNode document, Map<String, String> decisions) {}
