package com.orderpilot.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed production deployment surface validated at startup in production-like profiles. */
@ConfigurationProperties(prefix = "orderpilot.production")
public class ProductionDeploymentProperties {

  /** Public HTTPS base URL for the Core API as seen by the trusted ingress/BFF (no trailing slash). */
  private String publicApiBaseUrl = "";

  /** Public HTTPS base URL for the operator web surface (no trailing slash). */
  private String publicWebBaseUrl = "";

  /** Emit redacted production configuration diagnostics after successful validation. */
  private boolean emitDiagnostics = true;

  public String getPublicApiBaseUrl() {
    return publicApiBaseUrl;
  }

  public void setPublicApiBaseUrl(String publicApiBaseUrl) {
    this.publicApiBaseUrl = publicApiBaseUrl == null ? "" : publicApiBaseUrl;
  }

  public String getPublicWebBaseUrl() {
    return publicWebBaseUrl;
  }

  public void setPublicWebBaseUrl(String publicWebBaseUrl) {
    this.publicWebBaseUrl = publicWebBaseUrl == null ? "" : publicWebBaseUrl;
  }

  public boolean isEmitDiagnostics() {
    return emitDiagnostics;
  }

  public void setEmitDiagnostics(boolean emitDiagnostics) {
    this.emitDiagnostics = emitDiagnostics;
  }
}
