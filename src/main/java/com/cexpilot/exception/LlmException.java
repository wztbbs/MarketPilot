package com.cexpilot.exception;

public class LlmException extends RuntimeException {
    private com.fasterxml.jackson.databind.JsonNode diagnostics;

    public LlmException withDiagnostics(com.fasterxml.jackson.databind.JsonNode value) {
        diagnostics = value;
        return this;
    }

    public com.fasterxml.jackson.databind.JsonNode diagnostics() { return diagnostics; }

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
