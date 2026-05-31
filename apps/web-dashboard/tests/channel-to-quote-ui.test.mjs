import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "quote-transaction-api.ts"), "utf8");
const panel = readFileSync(join(root, "components", "channel-quote-conversion-panel.tsx"), "utf8");
const sourcePanel = readFileSync(join(root, "components", "quote-source-context-panel.tsx"), "utf8");
const messagePage = readFileSync(join(root, "app", "(dashboard)", "messages", "[id]", "page.tsx"), "utf8");
const documentPage = readFileSync(join(root, "app", "(dashboard)", "documents", "[id]", "page.tsx"), "utf8");
const quotePage = readFileSync(join(root, "app", "(dashboard)", "quotes", "[id]", "page.tsx"), "utf8");

test("channel-to-quote API client exposes conversion and source context endpoints", () => {
  assert.match(apiClient, /createQuoteFromChannelMessage/);
  assert.match(apiClient, /createQuoteFromInboundDocument/);
  assert.match(apiClient, /getQuoteSourceContext/);
  assert.match(apiClient, /\/api\/v1\/quote-transactions\/from-channel-message\/\$\{messageId\}/);
  assert.match(apiClient, /\/api\/v1\/quote-transactions\/from-inbound-document\/\$\{documentId\}/);
  assert.match(apiClient, /\/api\/v1\/quotes\/\$\{quoteId\}\/source-context/);
  assert.match(apiClient, /X-Tenant-Id/);
});

test("source detail pages render create quote draft wiring", () => {
  assert.match(messagePage, /ChannelQuoteConversionPanel/);
  assert.match(messagePage, /sourceType="CHANNEL_MESSAGE"/);
  assert.match(documentPage, /ChannelQuoteConversionPanel/);
  assert.match(documentPage, /sourceType="INBOUND_DOCUMENT"/);
  assert.match(panel, /Prepare Quote/);
  assert.match(panel, /Create Quote Draft/);
  assert.match(panel, /Dry run preview/);
  assert.match(panel, /Conversion rejected by backend validation/);
  assert.match(panel, /Review required before draft quote creation/);
  assert.match(panel, /validationIssues/);
});

test("quote detail page renders source context panel", () => {
  assert.match(quotePage, /QuoteSourceContextPanel/);
  assert.match(sourcePanel, /Source Context/);
  assert.match(sourcePanel, /Load Source Context/);
  assert.match(sourcePanel, /sourceExternalRef/);
  assert.match(sourcePanel, /conversionAttemptId/);
});
