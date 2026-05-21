package com.orderpilot.application.services.connector;

public record ConnectionDiagnostic(DiagnosticSeverity severity, DiagnosticCode code, String message) {}
