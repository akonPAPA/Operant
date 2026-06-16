package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.trust.AiMemoryGovernanceService;
import com.orderpilot.application.services.trust.AiRuntimeTraceService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationReasonCode;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryType;
import com.orderpilot.domain.trust.ai.AiRuntimeStatus;
import com.orderpilot.domain.trust.ai.AiRuntimeTrace;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance — endpoint contract, delegation, and
 * tenant scoping.
 */
@WebMvcTest(TrustAiMemoryController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class,
    TenantContextFilter.class})
class TrustAiMemoryControllerStage17FTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private AiMemoryGovernanceService memoryService;
  @MockBean private AiRuntimeTraceService runtimeTraceService;

  private static final String TENANT = UUID.randomUUID().toString();
  private static final Instant T = Instant.parse("2026-06-14T12:00:00Z");

  private AiMemoryRecord sampleRecord() {
    return new AiMemoryRecord(UUID.fromString(TENANT), AiMemoryNamespace.PRODUCT_ALIAS_HINT, "alias-1",
        AiMemoryType.HINT, AiMemoryAuthorityLevel.MEDIUM, AiMemorySourceType.DOCUMENT_TRUST_RUN,
        UUID.randomUUID(), "documentTrustRun:ref", "Alias hint", "summary", "X->Y",
        new BigDecimal("0.9000"), 5, 1, null, null, T);
  }

  @Test
  void createDelegatesAndReturnsDto() throws Exception {
    when(memoryService.createMemoryRecord(any())).thenReturn(sampleRecord());

    mockMvc.perform(post("/api/v1/trust/ai-memory")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"namespace\":\"PRODUCT_ALIAS_HINT\",\"memoryKey\":\"alias-1\","
                + "\"memoryType\":\"HINT\",\"authorityLevel\":\"MEDIUM\",\"sourceType\":\"DOCUMENT_TRUST_RUN\","
                + "\"title\":\"Alias hint\",\"summary\":\"summary\",\"confidence\":0.9}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.namespace").value("PRODUCT_ALIAS_HINT"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void searchSupportsNamespaceAndFlags() throws Exception {
    when(memoryService.searchMemory(any(), eq(AiMemoryNamespace.PRODUCT_ALIAS_HINT), eq("alias-1"),
        eq(false), eq(true), eq(10))).thenReturn(List.of(sampleRecord()));

    mockMvc.perform(get("/api/v1/trust/ai-memory"
            + "?namespace=PRODUCT_ALIAS_HINT&memoryKey=alias-1&includeLowConfidence=true&limit=10")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.namespace").value("PRODUCT_ALIAS_HINT"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.records[0].memoryKey").value("alias-1"));

    verify(memoryService).searchMemory(any(), eq(AiMemoryNamespace.PRODUCT_ALIAS_HINT), eq("alias-1"),
        eq(false), eq(true), eq(10));
  }

  @Test
  void getMemoryReturnsDto() throws Exception {
    UUID id = UUID.randomUUID();
    when(memoryService.getRecord(any(), eq(id))).thenReturn(sampleRecord());

    mockMvc.perform(get("/api/v1/trust/ai-memory/" + id).header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memoryKey").value("alias-1"));
  }

  @Test
  void invalidateDelegatesWithReasonCodeAndReason() throws Exception {
    UUID id = UUID.randomUUID();
    when(memoryService.invalidateMemoryRecord(any(), eq(id),
        eq(AiMemoryInvalidationReasonCode.USER_INVALIDATED), eq("no longer valid"), any(), any()))
        .thenReturn(sampleRecord());

    mockMvc.perform(post("/api/v1/trust/ai-memory/" + id + "/invalidate")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reasonCode\":\"USER_INVALIDATED\",\"reason\":\"no longer valid\"}"))
        .andExpect(status().isOk());

    verify(memoryService).invalidateMemoryRecord(any(), eq(id),
        eq(AiMemoryInvalidationReasonCode.USER_INVALIDATED), eq("no longer valid"), any(), any());
  }

  @Test
  void runtimeTraceWriteStoresSafeMetadata() throws Exception {
    AiRuntimeTrace trace = new AiRuntimeTrace(UUID.fromString(TENANT), "DOCUMENT_EXTRACTION", "local",
        "stub", "v3", "schema-2", 120, 40, new BigDecimal("0.0500"), AiRuntimeStatus.FALLBACK_USED, null,
        AiMemorySourceType.DOCUMENT_TRUST_RUN, UUID.randomUUID(), T);
    when(runtimeTraceService.recordRuntimeTrace(any())).thenReturn(trace);

    mockMvc.perform(post("/api/v1/trust/ai-runtime/traces")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workloadType\":\"DOCUMENT_EXTRACTION\",\"promptVersion\":\"v3\","
                + "\"status\":\"FALLBACK_USED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workloadType").value("DOCUMENT_EXTRACTION"))
        .andExpect(jsonPath("$.status").value("FALLBACK_USED"));
  }

  @Test
  void runtimeTraceListIsPagedAndTenantScoped() throws Exception {
    when(runtimeTraceService.listRuntimeTraces(any(), eq("DOCUMENT_EXTRACTION"),
        eq(AiRuntimeStatus.SUCCEEDED), any(), any(), eq(1), eq(10))).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/trust/ai-runtime/traces"
            + "?workloadType=DOCUMENT_EXTRACTION&status=SUCCEEDED&page=1&size=10")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk());

    verify(runtimeTraceService).listRuntimeTraces(any(), eq("DOCUMENT_EXTRACTION"),
        eq(AiRuntimeStatus.SUCCEEDED), any(), any(), eq(1), eq(10));
  }
}
