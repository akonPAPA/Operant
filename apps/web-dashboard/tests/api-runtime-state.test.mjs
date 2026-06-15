import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// Runtime state handling: 403/404/empty/5xx/network must render explicit, safe UI
// states instead of throwing a raw Next.js runtime error or leaking backend ids.
const root = process.cwd();
const coreApiClient = readFileSync(join(root, "lib", "core-api-client.ts"), "utf8");
const quoteReviewApi = readFileSync(join(root, "lib", "quote-review-api.ts"), "utf8");
const aiWorkApi = readFileSync(join(root, "lib", "ai-work-api.ts"), "utf8");
const quoteReviewCockpit = readFileSync(join(root, "components", "quote-review-cockpit.tsx"), "utf8");
const conversionReviewCockpit = readFileSync(join(root, "components", "conversion-review-cockpit.tsx"), "utf8");
const aiWorkWorkspace = readFileSync(join(root, "components", "ai-work-assistant-workspace.tsx"), "utf8");
const intakeUploadForm = readFileSync(join(root, "components", "intake-upload-form.tsx"), "utf8");

test("shared client maps 403/404/422/5xx/network into a typed ApiResult", () => {
  assert.match(coreApiClient, /export type ApiResult<T>/);
  assert.match(coreApiClient, /kind: "forbidden"/);
  assert.match(coreApiClient, /kind: "not_found"/);
  assert.match(coreApiClient, /kind: "validation_error"/);
  assert.match(coreApiClient, /"server_error" \| "network_error"/);
  assert.match(coreApiClient, /export async function coreApiGet<T>/);
  // status is inspected and mapped explicitly
  assert.match(coreApiClient, /case 403:/);
  assert.match(coreApiClient, /case 404:/);
  assert.match(coreApiClient, /case 422:/);
  // network failure is caught and typed, not thrown
  assert.match(coreApiClient, /kind: "network_error"/);
});

test("shared client uses operator-safe messages and never surfaces the raw body", () => {
  assert.match(coreApiClient, /You do not have access to this workspace or tenant context\./);
  assert.match(coreApiClient, /Review not found or no longer available\./);
  assert.match(coreApiClient, /Could not load this workspace\./);
  // non-200 bodies are drained but never returned to the caller as the message
  assert.doesNotMatch(coreApiClient, /message: text/);
  assert.doesNotMatch(coreApiClient, /message: await response\.text\(\)/);
});

test("quote review reads return typed ApiResult via the shared client", () => {
  assert.match(quoteReviewApi, /getQuoteReviewQueue\(\): Promise<ApiResult<QuoteReviewQueueRow\[\]>>/);
  assert.match(quoteReviewApi, /getQuoteReviewDetail\(quoteId: string\): Promise<ApiResult<QuoteReviewDetail>>/);
  assert.match(quoteReviewApi, /getQuoteConversionAttempts\([^)]*\): Promise<ApiResult<QuoteConversionAttemptReviewItem\[\]>>/);
  assert.match(quoteReviewApi, /getQuoteConversionAttemptDetail\(attemptId: string\): Promise<ApiResult<QuoteConversionAttemptReviewDetail>>/);
  assert.match(quoteReviewApi, /return coreApiGet</);
});

test("quote review command path maps errors to safe messages, not raw body text", () => {
  assert.match(quoteReviewApi, /throw new Error\(coreApiStatusMessage\(response\.status\)\)/);
  assert.doesNotMatch(quoteReviewApi, /const message = await response\.text\(\)/);
});

test("quote review queue renders loading/empty/forbidden/not-found states", () => {
  assert.match(quoteReviewCockpit, /type LoadKind = "loading" \| "ready" \| "forbidden" \| "not_found" \| "error"/);
  assert.match(quoteReviewCockpit, /No reviews found\./);
  assert.match(quoteReviewCockpit, /loadState === "forbidden" \|\| loadState === "not_found" \|\| loadState === "error"/);
  // detail body only renders when a resource was actually loaded — actions stay hidden otherwise
  assert.match(quoteReviewCockpit, /loadState === "ready" && detail \?/);
  assert.match(quoteReviewCockpit, /result\.kind === "forbidden" \|\| result\.kind === "not_found"/);
});

test("conversion review renders empty/forbidden/not-found without exposing raw ids", () => {
  assert.match(conversionReviewCockpit, /No conversion reviews found\./);
  assert.match(conversionReviewCockpit, /loadState === "forbidden" \|\| loadState === "not_found" \|\| loadState === "error"/);
  assert.match(conversionReviewCockpit, /loadState === "ready" && detail \?/);
  // the surface still only renders backend-provided safe fields, never a raw id echo
  assert.doesNotMatch(conversionReviewCockpit, /detail\.sourceId/);
  assert.doesNotMatch(conversionReviewCockpit, /\{attemptId\}<\//);
});

test("AI work client inspects status before parsing and maps to safe messages", () => {
  assert.match(aiWorkApi, /function aiWorkStatusMessage\(status: number\)/);
  assert.match(aiWorkApi, /You do not have access to this workspace or tenant context\./);
  // error branch returns a safe message, not the parsed backend body field
  assert.match(aiWorkApi, /return \{ data: fallback, error: aiWorkStatusMessage\(response\.status\) \}/);
  assert.doesNotMatch(aiWorkApi, /"message" in data/);
});

test("AI work workspace keeps a safe empty state for no backend suggestions", () => {
  assert.match(aiWorkWorkspace, /No advisory suggestions are available for the active review context\./);
  assert.match(aiWorkWorkspace, /suggestions\.length === 0/);
});

test("intake upload maps rejections to safe messages, not the raw body", () => {
  assert.match(intakeUploadForm, /message: coreApiStatusMessage\(response\.status\)/);
  assert.doesNotMatch(intakeUploadForm, /message: text \|\|/);
});
