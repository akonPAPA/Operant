package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// OP-CAP-42D: Response / sensitive-error leakage negative proof.
//
// Threat class (separate from request mass-assignment, OP-CAP-42C): a malicious or unauthorized
// client must not be able to learn internal implementation details from an *error* response. The
// dangerous leak channels on the error path are:
//   - malformed-JSON / deserialization errors echoing Jackson parser internals or the raw body;
//   - permission / default-deny denials echoing the route-policy class graph or a stack trace;
//   - the catch-all 500 echoing the underlying exception message (e.g. a SQL statement, a JDBC URL,
//     a Hibernate/JPA internal, or an implementation class name) or a stack trace.
//
// This routes through the *real* MVC stack: the real ApiPermissionInterceptor (default-deny), the
// real GlobalExceptionHandler, and real Jackson serialization. ChangeRequestController is reused
// only as a representative external-write-adjacent surface; the assertions are about the centralized
// error contract, not this controller. Each test asserts the *absence of concrete sensitive tokens*
// in the raw response body, not merely an HTTP status.
@WebMvcTest(ChangeRequestController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class, RequestActorResolver.class})
class ErrorResponseLeakTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private ChangeRequestService service;
  @MockBean private QuoteExternalWritePreparationService externalWritePreparationService;

  // Concrete implementation / infrastructure tokens that must never appear in any API error body.
  private static final String[] IMPLEMENTATION_LEAK_TOKENS = {
      "java.", "org.springframework", "com.fasterxml.jackson", "jakarta.",
      "Hibernate", "SQLException", "PSQLException", "JdbcSQLException", "DataAccessException",
      "stackTrace", "at com.orderpilot", ".java:", "Caused by",
      "ApiRouteSecurityPolicy", "ApiPermissionInterceptor", "ApiPermissionGuard", "TenantPolicyException"
  };

  private void assertNoImplementationLeak(String body) {
    for (String token : IMPLEMENTATION_LEAK_TOKENS) {
      assertThat(body)
          .as("error body must not leak implementation token '%s'", token)
          .doesNotContain(token);
    }
  }

  @Test
  void malformedJsonBodyReturnsSafeStructuredErrorWithoutJacksonParserInternalsOrStackTrace() throws Exception {
    // Caller is authorized (passes the permission interceptor) but sends a body that cannot be
    // deserialized. Spring raises HttpMessageNotReadableException whose own message carries the
    // Jackson parser location/internals; the handler must replace it with a stable safe message.
    String body = mockMvc.perform(post("/api/v1/change-requests")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_CREATE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetSystem\": \"ONEC\", this-is-not-valid-json :: <<>>"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Request body is not valid JSON"))
        .andReturn().getResponse().getContentAsString();

    assertNoImplementationLeak(body);
    // No Jackson parser diagnostics (token text, line/column, parser feature names) echoed back.
    assertThat(body)
        .doesNotContain("JsonParse")
        .doesNotContain("JsonMappingException")
        .doesNotContain("MismatchedInput")
        .doesNotContain("Unexpected character")
        .doesNotContain("this-is-not-valid-json")
        .doesNotContain("line:")
        .doesNotContain("column:");
  }

  @Test
  void permissionDeniedRequestDoesNotLeakRoutePolicyInternalsOrStackTrace() throws Exception {
    // Caller is authenticated at the gateway-header layer but holds an *insufficient* permission, so
    // the real ApiPermissionInterceptor/ApiPermissionGuard default-deny path runs and raises
    // TenantPolicyException -> 403. The denial must be a stable code/message and must not expose the
    // route-policy class graph (ApiRouteSecurityPolicy / ApiPermissionInterceptor) or a stack trace.
    // (The required permission *name* is a client-supplied contract token, not an internal
    // implementation detail, so its presence is intentional and not asserted against here.)
    String body = mockMvc.perform(post("/api/v1/change-requests")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetSystem\":\"ONEC\",\"targetEntity\":\"ORDER\",\"requestedAction\":\"CREATE_ORDER\",\"sourceType\":\"QUOTE\",\"sourceId\":\"" + UUID.randomUUID() + "\",\"requestPayloadJson\":\"{}\",\"idempotencyKey\":\"key\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andReturn().getResponse().getContentAsString();

    assertNoImplementationLeak(body);
  }

  @Test
  void unexpectedServerErrorDoesNotEchoUnderlyingExceptionMessageOrStackTrace() throws Exception {
    // Authorized request with a valid body, but the service blows up with an exception whose message
    // is deliberately stuffed with sensitive-looking internals (a JDBC URL, a SQL statement, and an
    // implementation class name). The catch-all 500 handler must NOT echo any of it back to the
    // client; the original detail is logged server-side only.
    String sensitiveJdbcUrl = "jdbc:postgresql://internal-db.orderpilot.local:55432/orderpilot?user=svc&password=topsecret";
    String sensitiveSql = "SELECT credential_secret FROM tenant_connector_credentials WHERE tenant_id = ?";
    String sensitiveClass = "com.orderpilot.infrastructure.persistence.ConnectorCredentialRepositoryImpl";
    when(service.createChangeRequest(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
        .thenThrow(new IllegalStateException(
            "DB failure " + sensitiveClass + " executing [" + sensitiveSql + "] against " + sensitiveJdbcUrl));

    String body = mockMvc.perform(post("/api/v1/change-requests")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_CREATE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetSystem\":\"ONEC\",\"targetEntity\":\"ORDER\",\"requestedAction\":\"CREATE_ORDER\",\"sourceType\":\"QUOTE\",\"sourceId\":\"" + UUID.randomUUID() + "\",\"requestPayloadJson\":\"{}\",\"idempotencyKey\":\"key\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("Unexpected server error"))
        .andReturn().getResponse().getContentAsString();

    assertNoImplementationLeak(body);
    // The specific sensitive content carried by the underlying exception must be fully suppressed.
    assertThat(body)
        .doesNotContain(sensitiveJdbcUrl)
        .doesNotContain("jdbc:postgresql")
        .doesNotContain("password=")
        .doesNotContain("topsecret")
        .doesNotContain(sensitiveSql)
        .doesNotContain("SELECT credential_secret")
        .doesNotContain("tenant_connector_credentials")
        .doesNotContain(sensitiveClass)
        .doesNotContain("ConnectorCredentialRepositoryImpl");
  }
}
