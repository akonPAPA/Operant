#!/usr/bin/env node
/**
 * F12 — evidence-integrity validator.
 *
 * Fails closed if any tracked production-evidence document contains:
 *  - an unresolved placeholder marker (e.g. "resolve after evidence push", TBD, FIXME, <sha>);
 *  - a PASS/GREEN claim on the same line as a "skipped" qualifier (skipped-described-as-passed);
 *  - an invented / obviously-fake SHA placeholder.
 *
 * It does NOT invent a head SHA. When the remediation is uncommitted, evidence docs must say so
 * (NON-AUTHORITATIVE / NOT YET COMMITTED) rather than carry a placeholder or a fabricated SHA.
 *
 * Usage: node scripts/check-evidence-integrity.mjs
 */
import { readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const EVIDENCE_DOCS = [
  "OPERANT_PRODUCTION_EXECUTION_STATE.md",
  "docs/backlog/fix-notebook.md",
  "docs/backlog/pr267-remediation-ledger.md",
  "docs/production/PRODUCTION_READINESS_MATRIX.md",
  "docs/production/RELEASE_EVIDENCE_MANIFEST.md",
  "docs/production/TRUST_BOUNDARY_MATRIX.md"
];

// Unresolved-placeholder markers. Case-insensitive.
const PLACEHOLDER_PATTERNS = [
  /resolve after evidence push/i,
  /\bTBD\b/,
  /\bFIXME\b/,
  /\bTO BE (FILLED|DETERMINED|DECIDED)\b/i,
  /<sha>/i,
  /\bplaceholder[-_ ]?sha\b/i,
  /\bx{7,}\b/i // xxxxxxx placeholder SHAs
];

// Explicit conflation of a skipped suite with a pass — the anti-pattern F12 forbids. A transparent
// numeric breakdown ("2371 pass / 45 skipped") or an explicit NOT_PASS is honest and NOT flagged.
const SKIPPED_AS_PASS =
  /\bskip(ped)?\b[^\n]*\b(count(s|ed)?|treated|marked|reported|considered)\s+as\s+(a\s+)?(pass|passed|green|success|proof)\b/i;

// A bare 40/64-hex "SHA" that is actually a repeated/obviously-fake filler.
const FAKE_SHA = /\b(0{40}|f{40}|0{64}|1234567890(abcdef){2,})\b/i;

// "All findings closed"-style claims are only honest when nothing is PARTIAL/BLOCKED/NOT_PASS.
const ALL_CLOSED_CLAIM =
  /\ball\s+(16\s+)?(findings|issues|items)\b[^\n]{0,60}\b(closed|addressed|resolved|complete[d]?)\b/i;

// A targeted subset presented as the full suite.
const TARGETED_AS_FULL =
  /\btargeted\b[^\n]{0,60}\b(counts?|treated|marked|reported|considered|same)\s+as\s+(the\s+)?full\b/i;

// Dirty working-tree output presented as release evidence.
const DIRTY_AS_RELEASE =
  /\b(working[- ]tree|dirty|uncommitted)\b[^\n]{0,80}\b(is|as|counts?\s+as)\s+release\s+evidence\b/i;

const STALE_EVIDENCE_ANCHOR =
  /\b(uncommitted|NOT YET (CREATED|COMMITTED)|no SHA (exists|is bound)|No SHA is bound)\b/i;

/**
 * Ledger closure-gate: F13 may be CLOSED only with build + E2E proof in its row; F15 only with
 * post-build diff/untracked cleanliness proof in its row. Applied to the remediation ledger table.
 */
function checkLedgerClosureGates(rel, text, findings) {
  for (const id of ["F13", "F15"]) {
    const row = text.split(/\r?\n/).find((l) => l.startsWith(`| ${id} `));
    if (!row) continue;
    const closed = /\|\s*CLOSED\s*\|/.test(row);
    if (!closed) continue;
    if (id === "F13" && !(/\bbuild\b/i.test(row) && /\b(e2e|playwright)\b/i.test(row))) {
      findings.push(`${rel}: F13 marked CLOSED without build + E2E proof in its ledger row`);
    }
    if (id === "F15" && !(/git diff/i.test(row) && /\buntracked\b/i.test(row))) {
      findings.push(`${rel}: F15 marked CLOSED without post-build git diff + untracked cleanliness proof`);
    }
  }
}

let findings = [];

const ledgerPath = join(repoRoot, "docs/backlog/pr267-remediation-ledger.md");
const ledgerText = existsSync(ledgerPath) ? readFileSync(ledgerPath, "utf8") : "";
const ledgerHasOpenFindings = /\b(PARTIAL|BLOCKED|NOT_PASS)\b/.test(ledgerText);

for (const rel of EVIDENCE_DOCS) {
  const full = join(repoRoot, rel);
  if (!existsSync(full)) {
    continue; // an absent optional doc is not a failure; a present one is validated
  }
  const lines = readFileSync(full, "utf8").split(/\r?\n/);
  lines.forEach((line, i) => {
    const n = i + 1;
    for (const p of PLACEHOLDER_PATTERNS) {
      if (p.test(line)) {
        findings.push(`${rel}:${n}: unresolved placeholder (${p})`);
      }
    }
    const honestlyNegated = /\bnot\s+(treated|counted|marked|considered|a\s+pass)\b/i.test(line) || /NOT[_ ]PASS/i.test(line);
    if (SKIPPED_AS_PASS.test(line) && !honestlyNegated) {
      findings.push(`${rel}:${n}: a skipped suite is described as passing`);
    }
    if (FAKE_SHA.test(line)) {
      findings.push(`${rel}:${n}: invented/fake SHA filler`);
    }
    const negated = /\b(not|never|unless|until|cannot|don't|do not|must not|forbid(s|den)?)\b/i.test(line);
    if (ALL_CLOSED_CLAIM.test(line) && ledgerHasOpenFindings && !negated) {
      findings.push(`${rel}:${n}: claims all findings closed while the ledger has PARTIAL/BLOCKED/NOT_PASS entries`);
    }
    if (TARGETED_AS_FULL.test(line) && !negated) {
      findings.push(`${rel}:${n}: a targeted test subset is described as the full suite`);
    }
    if (DIRTY_AS_RELEASE.test(line) && !negated && !/NON-AUTHORITATIVE/i.test(line)) {
      findings.push(`${rel}:${n}: dirty working-tree output is described as release evidence`);
    }
    if (STALE_EVIDENCE_ANCHOR.test(line)) {
      findings.push(`${rel}:${n}: stale mutable evidence anchor language`);
    }
  });
  checkLedgerClosureGates(rel, lines.join("\n"), findings);
}

if (findings.length > 0) {
  console.error("Evidence integrity check FAILED:");
  for (const f of findings) {
    console.error("  " + f);
  }
  process.exit(1);
}
console.log(`Evidence integrity check passed (${EVIDENCE_DOCS.filter((d) => existsSync(join(repoRoot, d))).length} docs).`);
