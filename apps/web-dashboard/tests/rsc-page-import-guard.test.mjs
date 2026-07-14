import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const testDir = dirname(fileURLToPath(import.meta.url));
const appRoot = join(testDir, "..", "app");

const TENANT_CLIENT_APIS = [
  "@/lib/intake-api",
  "@/lib/stage2-data-api",
  "@/lib/draft-review-api",
  "@/lib/validation-review-api",
  "@/lib/validation-review-detail-api",
  "@/lib/validation-review-draft-command-api",
  "@/lib/validation-review-draft-queue-api",
  "@/lib/pilot-metrics-api",
  "@/lib/commerce-intelligence-api",
  "@/lib/runtime-control-telemetry-api",
  "@/lib/channel-bot-api",
  "@/lib/rfq-handoff-api",
  "@/lib/ai-work-api",
  "@/lib/bot-runtime-config-api",
  "@/lib/channel-identity-api",
  "@/lib/bot-runtime-api"
];

function walk(dir, files = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full, files);
    } else if (entry === "page.tsx") {
      files.push(full);
    }
  }
  return files;
}

test("dashboard Server Component pages import server tenant API modules", () => {
  const pages = walk(appRoot);
  assert.ok(pages.length > 0);
  for (const pagePath of pages) {
    const source = readFileSync(pagePath, "utf8");
    for (const banned of TENANT_CLIENT_APIS) {
      assert.doesNotMatch(
        source,
        new RegExp(banned.replace("/", "\\/")),
        `${pagePath} must not import browser tenant client ${banned}`
      );
    }
    if (source.includes("@/lib/server/") && source.includes("-api")) {
      assert.match(source, /@\/lib\/server\/[a-z0-9-]+\.server/);
    }
  }
});
