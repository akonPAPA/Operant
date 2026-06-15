package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundAttachmentRepository;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.intake.InboundEventLedgerRepository;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class Phase3OmnichannelIntakeControllerTest {
  private static final String TENANT = "X-Tenant-Id";
  private static final String PERMISSIONS = "X-OrderPilot-Permissions";

  @Autowired private MockMvc mockMvc;
  @Autowired private InboundDocumentRepository documentRepository;
  @Autowired private ChannelMessageRepository messageRepository;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private InboundAttachmentRepository attachmentRepository;
  @Autowired private InboundEventLedgerRepository ledgerRepository;

  @Test
  void fileUploadCreatesInboundDocumentProcessingJobAndAuditEvent() throws Exception {
    UUID tenantId = UUID.randomUUID();
    MockMultipartFile file = new MockMultipartFile("file", "quote-request.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));

    mockMvc.perform(multipart("/api/v1/intake/documents/upload")
            .file(file)
            .header(TENANT, tenantId.toString())
            .header(PERMISSIONS, "INTAKE_WRITE")
            .param("sourceChannel", "FILE_UPLOAD")
            .param("documentType", "CUSTOMER_RFQ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceChannel").value("FILE_UPLOAD"))
        .andExpect(jsonPath("$.status").value("QUEUED"));

    var documents = documentRepository.findByTenantIdOrderByReceivedAtDesc(tenantId);
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getObjectStorageKey()).isNotBlank();
    assertThat(ledgerRepository.findByTenantIdOrderByReceivedAtDesc(tenantId))
        .extracting("source", "eventType", "status")
        .contains(tuple("FILE_UPLOAD", "DOCUMENT_RECEIVED", "QUEUED"));
    assertThat(jobRepository.findByTenantIdOrderByQueuedAtDesc(tenantId))
        .extracting("jobType", "targetType", "status")
        .contains(tuple("DOCUMENT_RECEIVED", "INBOUND_DOCUMENT", "PENDING"));
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains("inbound_document.received");
  }

  @Test
  void duplicateFileUploadIsDetectedByFingerprint() throws Exception {
    UUID tenantId = UUID.randomUUID();
    byte[] bytes = "sku,qty\nPAD-100,2\n".getBytes(StandardCharsets.UTF_8);

    uploadCsv(tenantId, bytes).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
    uploadCsv(tenantId, bytes).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DUPLICATE"));

    assertThat(documentRepository.findByTenantIdOrderByReceivedAtDesc(tenantId)).hasSize(2);
    assertThat(jobRepository.findByTenantIdOrderByQueuedAtDesc(tenantId)).hasSize(1);
  }

  @Test
  void invalidAndOversizedFilesAreRejectedBeforeDocumentCreation() throws Exception {
    UUID tenantId = UUID.randomUUID();
    MockMultipartFile invalid = new MockMultipartFile("file", "malware.exe", "application/x-msdownload", "bad".getBytes(StandardCharsets.UTF_8));
    mockMvc.perform(multipart("/api/v1/intake/documents/upload").file(invalid).header(TENANT, tenantId.toString()).header(PERMISSIONS, "INTAKE_WRITE"))
        .andExpect(status().isBadRequest());

    MockMultipartFile spoofed = new MockMultipartFile("file", "malware.exe", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
    mockMvc.perform(multipart("/api/v1/intake/documents/upload").file(spoofed).header(TENANT, tenantId.toString()).header(PERMISSIONS, "INTAKE_WRITE"))
        .andExpect(status().isBadRequest());

    MockMultipartFile oversized = new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[(10 * 1024 * 1024) + 1]);
    mockMvc.perform(multipart("/api/v1/intake/documents/upload").file(oversized).header(TENANT, tenantId.toString()).header(PERMISSIONS, "INTAKE_WRITE"))
        .andExpect(status().isBadRequest());

    assertThat(documentRepository.findByTenantIdOrderByReceivedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void apiUploadCreatesInboundEventMessageAndProcessingJob() throws Exception {
    UUID tenantId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/intake/api-upload")
            .header(TENANT, tenantId.toString())
            .header(PERMISSIONS, "INTAKE_WRITE")
            .contentType("application/json")
            .content("""
                {
                  "source":"external_api",
                  "customerHint":"ACME Auto Parts",
                  "messageText":"Need brake pads for Toyota Camry 2018, 20 pcs",
                  "externalReference":"demo-api-001"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel").value("EXTERNAL_API"))
        .andExpect(jsonPath("$.textContent").value("Need brake pads for Toyota Camry 2018, 20 pcs"));

    assertThat(messageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId)).hasSize(1);
    assertThat(ledgerRepository.findByTenantIdOrderByReceivedAtDesc(tenantId))
        .extracting("source", "externalEventId", "eventType", "status")
        .contains(tuple("EXTERNAL_API", "demo-api-001", "MESSAGE_RECEIVED", "QUEUED"));
    assertThat(jobRepository.findByTenantIdOrderByQueuedAtDesc(tenantId)).hasSize(1);
  }

  @Test
  void genericMessageCreatesChannelMessageProcessingJobAndAuditEvent() throws Exception {
    UUID tenantId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/intake/messages")
            .header(TENANT, tenantId.toString())
            .header(PERMISSIONS, "INTAKE_WRITE")
            .contentType("application/json")
            .content("""
                {
                  "channel":"API",
                  "externalMessageId":"api-msg-1",
                  "conversationId":"api-thread-1",
                  "senderHandle":"buyer@example.test",
                  "direction":"INBOUND",
                  "messageType":"TEXT",
                  "textContent":"Need brake pads for Toyota Camry",
                  "rawPayload":"{\\"source\\":\\"manual-api\\"}"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel").value("API"))
        .andExpect(jsonPath("$.status").value("QUEUED"));

    assertThat(messageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId)).hasSize(1);
    assertThat(jobRepository.findByTenantIdOrderByQueuedAtDesc(tenantId))
        .extracting("jobType", "targetType", "status")
        .contains(tuple("MESSAGE_RECEIVED", "CHANNEL_MESSAGE", "PENDING"));
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains("channel_message.received");
  }

  @Test
  void telegramWebhookStubCreatesChannelMessageAndLedgeredEvent() throws Exception {
    UUID tenantId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/webhooks/telegram")
            .header(TENANT, tenantId.toString())
            .contentType("application/json")
            .content("""
                {"update_id":1001,"message":{"message_id":501,"chat":{"id":"chat-1"},"from":{"id":"sender-1","username":"buyer"},"text":"Need filters"}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel").value("TELEGRAM"))
        .andExpect(jsonPath("$.textContent").value("Need filters"));

    assertThat(messageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId)).hasSize(1);
    assertThat(jobRepository.findByTenantIdOrderByQueuedAtDesc(tenantId)).hasSize(1);
    assertThat(ledgerRepository.findByTenantIdOrderByReceivedAtDesc(tenantId))
        .extracting("source", "eventType", "status")
        .contains(tuple("TELEGRAM", "TELEGRAM_WEBHOOK", "ACCEPTED"), tuple("TELEGRAM", "MESSAGE_RECEIVED", "QUEUED"));
  }

  @Test
  void whatsappWebhookStubCreatesChannelMessage() throws Exception {
    UUID tenantId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/webhooks/whatsapp")
            .header(TENANT, tenantId.toString())
            .contentType("application/json")
            .content("""
                {"entry":[{"changes":[{"value":{"contacts":[{"wa_id":"77001112233","profile":{"name":"Buyer One"}}],"messages":[{"id":"wamid.1","from":"77001112233","type":"text","text":{"body":"Need oil filters"}}]}}]}]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel").value("WHATSAPP"))
        .andExpect(jsonPath("$.textContent").value("Need oil filters"));

    assertThat(messageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId)).hasSize(1);
  }

  @Test
  void emailWebhookStubCreatesChannelMessageAndAttachmentMetadata() throws Exception {
    UUID tenantId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/webhooks/email")
            .header(TENANT, tenantId.toString())
            .contentType("application/json")
            .content("""
                {
                  "externalMessageId":"email-1",
                  "sender":"buyer@example.test",
                  "subject":"RFQ",
                  "bodyText":"Please quote attached list",
                  "rawPayload":"{\\"messageId\\":\\"email-1\\"}",
                  "attachments":[{"originalFilename":"rfq.csv","contentType":"text/csv","sizeBytes":128,"objectStorageKey":"metadata-only/email-1/rfq.csv","fingerprintSha256":"abc123"}]
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel").value("EMAIL"));

    var message = messageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId).getFirst();
    assertThat(attachmentRepository.findByTenantIdAndChannelMessageId(tenantId, message.getId())).hasSize(1);
    assertThat(ledgerRepository.findByTenantIdOrderByReceivedAtDesc(tenantId))
        .extracting("source", "eventType")
        .contains(tuple("EMAIL", "EMAIL_WEBHOOK"), tuple("EMAIL", "MESSAGE_RECEIVED"));
  }

  @Test
  void malformedJsonAndMissingTenantReturnSafeErrors() throws Exception {
    mockMvc.perform(post("/api/v1/intake/api-upload")
            .header(TENANT, UUID.randomUUID().toString())
            .header(PERMISSIONS, "INTAKE_WRITE")
            .contentType("application/json")
            .content("{bad-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Request body is not valid JSON"));

    mockMvc.perform(post("/api/v1/intake/api-upload")
            .header(PERMISSIONS, "INTAKE_WRITE")
            .contentType("application/json")
            .content("{\"source\":\"external_api\",\"messageText\":\"Need pads\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Missing tenant header X-Tenant-Id"));
  }

  @Test
  void tenantIsolationKeepsIntakeRecordsScopedToCurrentTenant() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/intake/messages")
            .header(TENANT, tenantA.toString())
            .header(PERMISSIONS, "INTAKE_WRITE")
            .contentType("application/json")
            .content("{\"channel\":\"API\",\"externalMessageId\":\"shared\",\"senderHandle\":\"a\",\"textContent\":\"Tenant A\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/intake/messages")
            .header(TENANT, tenantB.toString())
            .header(PERMISSIONS, "INTAKE_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    assertThat(messageRepository.findByTenantIdOrderByReceivedAtDesc(tenantA)).hasSize(1);
    assertThat(messageRepository.findByTenantIdOrderByReceivedAtDesc(tenantB)).isEmpty();

    mockMvc.perform(get("/api/v1/intake/events")
            .header(TENANT, tenantB.toString())
            .header(PERMISSIONS, "INTAKE_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  // OP-CAP-17E: the inbound-event ledger response must never expose the raw object storage key
  // (an internal, tenant-scoped path). It returns only a safe boolean indicator plus the content
  // fingerprint; the raw key is persisted server-side but stays out of the API contract.
  @Test
  void inboundEventResponseExposesSafeFlagNotRawStorageKey() throws Exception {
    UUID tenantId = UUID.randomUUID();
    uploadCsv(tenantId, "sku,qty\nPAD-100,2\n".getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk());

    String rawStorageKey = ledgerRepository.findByTenantIdOrderByReceivedAtDesc(tenantId).getFirst().getRawPayloadStorageKey();
    assertThat(rawStorageKey).isNotBlank();

    String body = mockMvc.perform(get("/api/v1/intake/events")
            .header(TENANT, tenantId.toString())
            .header(PERMISSIONS, "INTAKE_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].rawPayloadStored").value(true))
        .andExpect(jsonPath("$[0].fingerprintSha256").isNotEmpty())
        .andExpect(jsonPath("$[0].rawPayloadStorageKey").doesNotExist())
        .andReturn().getResponse().getContentAsString();

    assertThat(body).doesNotContain(rawStorageKey);
  }

  private org.springframework.test.web.servlet.ResultActions uploadCsv(UUID tenantId, byte[] bytes) throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "rfq.csv", "text/csv", bytes);
    return mockMvc.perform(multipart("/api/v1/intake/documents/upload").file(file).header(TENANT, tenantId.toString()).header(PERMISSIONS, "INTAKE_WRITE").param("sourceChannel", "FILE_UPLOAD"));
  }

  private static org.assertj.core.groups.Tuple tuple(Object... values) {
    return org.assertj.core.groups.Tuple.tuple(values);
  }
}
