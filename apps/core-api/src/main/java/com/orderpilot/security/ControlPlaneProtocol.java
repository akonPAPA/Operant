package com.orderpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

final class ControlPlaneProtocol {
  static final String PROTOCOL_MARKER = "OPERANT_CONTROL_V1";
  static final String SIGNATURE_VERSION = "1";
  static final String AUDIENCE = "orderpilot-control-plane";
  static final String EMPTY_BODY_SHA256_HEX =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  static final String CREDENTIAL_HEADER = "X-OrderPilot-Control-Credential";
  static final String AUDIENCE_HEADER = "X-OrderPilot-Control-Audience";
  static final String TIMESTAMP_HEADER = "X-OrderPilot-Control-Timestamp";
  static final String NONCE_HEADER = "X-OrderPilot-Control-Nonce";
  static final String VERSION_HEADER = "X-OrderPilot-Control-Signature-Version";
  static final String CONTENT_SHA256_HEADER = "X-OrderPilot-Control-Content-SHA256";
  static final String SIGNATURE_HEADER = "X-OrderPilot-Control-Signature";

  private ControlPlaneProtocol() {}

  static String canonical(
      String method,
      String normalizedPath,
      String rawQuery,
      String contentType,
      String bodySha256Hex,
      String audience,
      String credentialAlias,
      long timestampEpoch,
      String nonce) {
    return PROTOCOL_MARKER
        + "\n" + method.toUpperCase(Locale.ROOT)
        + "\n" + normalizedPath
        + "\n" + nullToEmpty(rawQuery)
        + "\n" + normalizeContentType(contentType)
        + "\n" + bodySha256Hex.toLowerCase(Locale.ROOT)
        + "\n" + audience.trim()
        + "\n" + credentialAlias.trim()
        + "\n" + timestampEpoch
        + "\n" + nonce.trim();
  }

  static String requestContentType(HttpServletRequest request) {
    return normalizeContentType(request.getContentType());
  }

  static String normalizeContentType(String contentType) {
    return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
