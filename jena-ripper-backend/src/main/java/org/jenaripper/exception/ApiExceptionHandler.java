package org.jenaripper.exception;

import org.jenaripper.dto.SparqlErrorDto;
import org.jenaripper.dto.SparqlErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ModelInUseException.class)
    public ResponseEntity<Map<String, Object>> modelInUse(ModelInUseException exception) {
        List<Map<String, String>> profiles = exception.profiles().stream()
                .map(profile -> Map.of("id", profile.id(), "name", profile.name()))
                .toList();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "MODEL_IN_USE",
                "message", exception.getMessage(),
                "profileIds", exception.profiles().stream().map(ModelInUseException.ProfileReference::id).toList(),
                "profiles", profiles));
    }

    @ExceptionHandler(UploadedModelException.class)
    public ResponseEntity<Map<String, String>> uploadedModelFailed(UploadedModelException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", exception.getMessage(), "type", "UPLOADED_MODEL_ERROR"));
    }

    @ExceptionHandler(MetamodelUnavailableException.class)
    public ResponseEntity<Map<String, String>> metamodelUnavailable(MetamodelUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", exception.getMessage(), "type", "METAMODEL_UNAVAILABLE"));
    }

    @ExceptionHandler(RedisCommandException.class)
    public ResponseEntity<Map<String, Object>> redisCommandFailed(RedisCommandException exception) {
        HttpStatus status = switch (exception.type()) {
            case "REDIS_TIMEOUT", "CIM_REDIS_TIMEOUT" -> HttpStatus.REQUEST_TIMEOUT;
            case "REDIS_UNAVAILABLE", "CIM_REDIS_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "CIM_REDIS_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "CIM_REDIS_AUTH" -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
                "type", exception.type(), "message", exception.getMessage())));
    }

    @ExceptionHandler(ConnectionTestException.class)
    public ResponseEntity<Map<String, String>> connectionTestFailed(ConnectionTestException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", exception.getMessage(), "type", "CONNECTION_TEST_FAILED"));
    }

    @ExceptionHandler(CimApiException.class)
    public ResponseEntity<Map<String, String>> cimApiFailed(CimApiException exception) {
        HttpStatus status = switch (exception.type()) {
            case "CIM_API_AUTH_FAILED" -> HttpStatus.UNAUTHORIZED;
            case "CIM_API_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "CIM_API_TIMEOUT" -> HttpStatus.REQUEST_TIMEOUT;
            case "CIM_API_DATASET_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of("error", exception.getMessage(), "type", exception.type()));
    }

    @ExceptionHandler(RedisRulesUnavailableException.class)
    public ResponseEntity<Map<String, String>> redisRulesUnavailable(RedisRulesUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", exception.getMessage(),
                        "type", exception.timeout() ? "REDIS_RULES_TIMEOUT" : "REDIS_RULES_UNAVAILABLE"));
    }

    @ExceptionHandler(OwnerRulesUnavailableException.class)
    public ResponseEntity<Map<String, String>> ownerRulesUnavailable(OwnerRulesUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", exception.getMessage(), "type", "OWNER_RULES_UNAVAILABLE"));
    }

    @ExceptionHandler(SparqlQueryException.class)
    public ResponseEntity<SparqlErrorResponse> sparqlError(SparqlQueryException exception) {
        HttpStatus status = "SPARQL_TIMEOUT".equals(exception.type())
                ? HttpStatus.REQUEST_TIMEOUT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new SparqlErrorResponse(new SparqlErrorDto(
                exception.type(), exception.getMessage(), exception.line(), exception.column())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException exception) {
        return Map.of("error", exception.getMessage());
    }
}
