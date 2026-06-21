package com.orderpilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.BotConversationRepository;
import com.orderpilot.domain.bot.BotHandoffRepository;
import com.orderpilot.domain.bot.BotMessageRepository;
import com.orderpilot.domain.bot.BotRfqRequestRepository;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CoreV1InvestorDemoSmokeTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private DemoDataService demoDataService;
  @Autowired private BotConversationRepository conversationRepository;
  @Autowired private BotMessageRepository messageRepository;
  @Autowired private BotRfqRequestRepository rfqRequestRepository;
  @Autowired private BotHandoffRepository handoffRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private CustomerAccountRepository customerRepository;
  @Autowired private ProductRepository productRepository;

  @Test
  void serviceRootAndFaviconDoNotReturnInternalError() throws Exception {
    mockMvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.service").value("orderpilot-core-api"))
        .andExpect(jsonPath("$.status").value("UP"));

    mockMvc.perform(get("/favicon.ico"))
        .andExpect(status().isNoContent());
  }

  @Test
  void telegramRfqWithMissingDemoSeedReturnsControlledClientError() throws Exception {
    mockMvc.perform(post("/api/v1/bot/telegram/webhook")
            .header("X-Tenant-Id", "11111111-1111-4111-8111-111111111111")
            .contentType("application/json")
            .content(demoDataService.fixtureText("telegram-rfq-demo.json")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Tenant not found for X-Tenant-Id. Run scripts\\seed-local-demo.ps1 for the investor demo or use a seeded tenant id."));
  }

  @Test
  void investorDemoSmokeFlowRunsThroughBotReconciliationAnalyticsAndAudit() throws Exception {
    DemoDataService.DemoSeedResult seed = demoDataService.seedCoreV1Demo();
    UUID tenantId = seed.tenantId();
    long customersBeforeBot = customerRepository.count();
    long productsBeforeBot = productRepository.count();
    long inventorySnapshotsBeforeBot = inventorySnapshotRepository.count();
    long pricesBeforeBot = priceRuleRepository.count();
    long approvedQuotesBefore = draftQuoteRepository.countByTenantIdAndStatus(tenantId, "APPROVED_INTERNAL");
    long finalOrdersBefore = draftOrderRepository.countByTenantIdAndStatus(tenantId, "APPROVED_INTERNAL");

    mockMvc.perform(post("/api/v1/bot/telegram/webhook")
            .header("X-Tenant-Id", tenantId.toString())
            .contentType("application/json")
            .content(demoDataService.fixtureText("telegram-rfq-demo.json")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.intent").value("RFQ_REQUEST"))
        .andExpect(jsonPath("$.requiresHumanReview").value(true))
        .andExpect(jsonPath("$.createdRfqDraftId").exists());

    assertThat(conversationRepository.count()).isEqualTo(1);
    assertThat(messageRepository.countByTenantIdAndChannel(tenantId, "TELEGRAM")).isEqualTo(1);
    assertThat(rfqRequestRepository.countByTenantId(tenantId)).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_RFQ_DRAFT_CREATED");

    mockMvc.perform(post("/api/v1/bot/telegram/webhook")
            .header("X-Tenant-Id", tenantId.toString())
            .contentType("application/json")
            .content(demoDataService.fixtureText("telegram-unknown-demo.json")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.intent").value("UNKNOWN"))
        .andExpect(jsonPath("$.requiresHumanReview").value(true));

    assertThat(handoffRepository.count()).isEqualTo(1);
    assertThat(draftQuoteRepository.countByTenantIdAndStatus(tenantId, "APPROVED_INTERNAL")).isEqualTo(approvedQuotesBefore);
    assertThat(draftOrderRepository.countByTenantIdAndStatus(tenantId, "APPROVED_INTERNAL")).isEqualTo(finalOrdersBefore);
    assertThat(customerRepository.count()).isEqualTo(customersBeforeBot);
    assertThat(productRepository.count()).isEqualTo(productsBeforeBot);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventorySnapshotsBeforeBot);
    assertThat(priceRuleRepository.count()).isEqualTo(pricesBeforeBot);

    mockMvc.perform(post("/api/v1/reconciliation/inventory/run")
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-OrderPilot-Permissions", "ANALYTICS_MANAGE")
            .contentType("application/json")
            .content("{\"productId\":\"" + seed.primaryProductId() + "\",\"locationId\":\"" + seed.locationId() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expectedStock").value(116))
        .andExpect(jsonPath("$.actualStock").value(100))
        .andExpect(jsonPath("$.mismatchQuantity").value(-16))
        .andExpect(jsonPath("$.severity").value("HIGH"))
        .andExpect(jsonPath("$.reconciliationCaseId").exists());

    assertThat(auditEventRepository.findAll()).extracting("action").contains("RECONCILIATION_CASE_CREATED");

    mockMvc.perform(get("/api/v1/analytics/commerce/summary")
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-OrderPilot-Permissions", "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.totalBotRfqRequests").value(1))
        .andExpect(jsonPath("$.openReconciliationCases").value(1))
        .andExpect(jsonPath("$.highSeverityReconciliationCases").value(1))
        .andExpect(jsonPath("$.channelBreakdown.TELEGRAM").value(2));

    UUID otherTenant = UUID.randomUUID();
    mockMvc.perform(get("/api/v1/analytics/commerce/summary")
            .header("X-Tenant-Id", otherTenant.toString())
            .header("X-OrderPilot-Permissions", "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalBotRfqRequests").value(0))
        .andExpect(jsonPath("$.openReconciliationCases").value(0))
        .andExpect(jsonPath("$.channelBreakdown.TELEGRAM").value(0));
  }
}
