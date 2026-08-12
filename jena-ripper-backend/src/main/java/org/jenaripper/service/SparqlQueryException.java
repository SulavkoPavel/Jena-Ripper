package org.jenaripper.service;

public class SparqlQueryException extends RuntimeException {
    private final String type;
    private final Integer line;
    private final Integer column;

    public SparqlQueryException(String type, String message, Integer line, Integer column) {
        super(message);
        this.type = type;
        this.line = line;
        this.column = column;
    }

    public String type() { return type; }
    public Integer line() { return line; }
    public Integer column() { return column; }
}
