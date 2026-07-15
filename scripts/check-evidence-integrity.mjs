#!/usr/bin/env node
/**
 * Evidence integrity validator.
 *
 * Structural mode validates tracked source policy. Exact-head proof is validated only from an
 * external/generated attestation after checkout of an immutable commit.
 */
import { existsSync, readFileSync } from "node:fs";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync, spawnSync } from "node:child_process";

const DEFAULT_REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const DEFAULT_ATTESTATION_PATH = "build/evidence/local-head-attestation.json";
const SHA40 = /^[0-9a-f]{40}$/;
const SHA64 = /^[0-9a-f]{64}$/;

const EVIDENCE_DOCS = [
  "OPERANT_PRODUCTION_EXECUTION_STATE.md",
  "docs/backlog/fix-notebook.md",
  "docs/backlog/pr267-remediation-ledger.md",
  "docs/production/PRODUCTION_READINESS_MATRIX.md",
  "docs/production/RELEASE_EVIDENCE_MANIFEST.md",
  "docs/production/TRUST_BOUNDARY_MATRIX.md"
];

const PLACEHOLDER_PATTERNS = [
  /resolve after evidence push/i,
  /\bTBD\b/,
  /\bFIXME\b/,
  /\bTO BE (FILLED|DETERMINED|DECIDED)\b/i,
  /<sha>/i,
  /\bplaceholder[-_ ]?sha\b/i,
  /\bx{7,}\b/i
];

const SKIPPED_AS_PASS =
  /\bskip(ped)?\b[^\n]*\b(count(s|ed)?|treated|marked|reported|considered)\s+as\s+(a\s+)?(pass|passed|green|success|proof)\b/i;
const FAKE_SHA = /\b(0{40}|f{40}|0{64}|1234567890(abcdef){2,})\b/i;
const ALL_CLOSED_CLAIM =
  /\ball\s+(16\s+)?(findings|issues|items)\b[^\n]{0,60}\b(closed|addressed|resolved|complete[d]?)\b/i;
const TARGETED_AS_FULL =
  /\btargeted\b[^\n]{0,60}\b(counts?|treated|marked|reported|considered|same)\s+as\s+(the\s+)?full\b/i;
const DIRTY_AS_RELEASE =
  /\b(working[- ]tree|dirty|uncommitted)\b[^\n]{0,80}\b(is|as|counts?\s+as)\s+release\s+evidence\b/i;

function parseArgs(argv) {
  const args = {
    mode: "structural",
    repoRoot: DEFAULT_REPO_ROOT,
    attestationPath: DEFAULT_ATTESTATION_PATH,
    ciMetadataPath: null
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--mode") args.mode = argv[++i];
    else if (arg.startsWith("--mode=")) args.mode = arg.slice("--mode=".length);
    else if (arg === "--repo-root") args.repoRoot = resolve(argv[++i]);
    else if (arg.startsWith("--repo-root=")) args.repoRoot = resolve(arg.slice("--repo-root=".length));
    else if (arg === "--attestation-path") args.attestationPath = argv[++i];
    else if (arg.startsWith("--attestation-path=")) args.attestationPath = arg.slice("--attestation-path=".length);
    else if (arg === "--ci-metadata-path") args.ciMetadataPath = argv[++i];
    else if (arg.startsWith("--ci-metadata-path=")) args.ciMetadataPath = arg.slice("--ci-metadata-path=".length);
    else if (arg === "--help" || arg === "-h") {
      printUsage();
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (!["structural", "local-head", "ci"].includes(args.mode)) {
    throw new Error(`Unsupported mode: ${args.mode}`);
  }
  return args;
}

function printUsage() {
  console.log(`Usage:
  node scripts/check-evidence-integrity.mjs --mode structural
  node scripts/check-evidence-integrity.mjs --mode local-head --attestation-path build/evidence/local-head-attestation.json
  node scripts/check-evidence-integrity.mjs --mode ci --ci-metadata-path build/evidence/ci-head-attestation.json`);
}

function gitOutput(repoRoot, args, options = {}) {
  return execFileSync("git", args, {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", options.quietStderr ? "ignore" : "pipe"]
  }).trim();
}

function gitStatus(repoRoot) {
  return gitOutput(repoRoot, ["status", "--short"]);
}

function currentHead(repoRoot) {
  return gitOutput(repoRoot, ["rev-parse", "HEAD"]);
}

function currentBranchOrRef(repoRoot) {
  return gitOutput(repoRoot, ["rev-parse", "--abbrev-ref", "HEAD"]);
}

function tracked(repoRoot, path) {
  const rel = normalizeRepoRelative(repoRoot, path);
  const result = spawnSync("git", ["ls-files", "--error-unmatch", rel], {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"]
  });
  return result.status === 0;
}

function ignored(repoRoot, path) {
  const rel = normalizeRepoRelative(repoRoot, path);
  const result = spawnSync("git", ["check-ignore", "-q", rel], {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"]
  });
  return result.status === 0;
}

function normalizeRepoRelative(repoRoot, path) {
  const absolute = isAbsolute(path) ? path : join(repoRoot, path);
  const rel = relative(repoRoot, absolute).replaceAll("\\", "/");
  if (rel.startsWith("..")) throw new Error(`Path is outside repository: ${path}`);
  return rel;
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function parseManifestTable(text) {
  const fields = new Map();
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^\|\s*`([^`]+)`(?:\s*\([^)]*\))?\s*\|\s*`?([^|`]+?)`?\s*\|/);
    if (match) fields.set(match[1], match[2].trim());
  }
  return fields;
}

function checkLedgerClosureGates(rel, text, findings, diagnostics) {
  for (const id of ["F13", "F15"]) {
    const key = `${rel}:${id}`;
    if (diagnostics.closureGateInvocations.has(key)) {
      findings.push(`${rel}: duplicate closure gate invocation for ${id}`);
      continue;
    }
    diagnostics.closureGateInvocations.add(key);
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

function checkPr269EvidenceAnchors(rel, text, findings) {
  if (rel !== "docs/backlog/pr267-remediation-ledger.md") return;
  const required = [
    ["remediation implementation SHA", /Remediation implementation SHA:\s*`[0-9a-f]{40}`/i],
    ["tested PR head SHA", /Tested PR head SHA:\s*`[0-9a-f]{40}`/i],
    ["tested PR merge-test SHA", /Tested PR merge-test SHA:\s*`[0-9a-f]{40}`/i],
    ["Frontend exact-head result", /Frontend workflow run `\d{8,}`:\s*\*\*SUCCESS\*\*/i],
    ["CI exact-head result", /CI workflow run `\d{8,}`:\s*\*\*SUCCESS\*\*/i],
    ["Backend exact-head result", /Backend workflow run `\d{8,}`:\s*\*\*SUCCESS\*\*/i],
    ["AI Worker exact-head result", /AI Worker workflow run `\d{8,}`:\s*\*\*SUCCESS\*\*/i]
  ];
  for (const [label, pattern] of required) {
    if (!pattern.test(text)) findings.push(`${rel}: missing ${label}`);
  }
}

function checkReleaseManifest(rel, text, findings) {
  if (rel !== "docs/production/RELEASE_EVIDENCE_MANIFEST.md") return;
  const fields = parseManifestTable(text);
  const required = [
    "evidence_schema_version",
    "release_candidate",
    "release_status",
    "baseline_reviewed_parent_sha",
    "remediation_implementation_sha",
    "tested_pr_head_sha",
    "tested_pr_merge_sha",
    "runtime_attestation_path",
    "STRUCTURAL_POLICY_VALID",
    "CURRENT_HEAD_LOCALLY_VERIFIED",
    "CURRENT_HEAD_CI_VERIFIED",
    "CODEQL_CURRENT_HEAD_VERIFIED",
    "FINAL_RELEASE_EVIDENCE_COMPLETE"
  ];
  for (const field of required) {
    if (!fields.has(field)) findings.push(`${rel}: missing tracked policy field ${field}`);
  }
  if (fields.has("final_reviewed_head") || /\bfinal_reviewed_head\b/i.test(text)) {
    findings.push(`${rel}: final_reviewed_head is prohibited because tracked source cannot name its own commit SHA`);
  }
  for (const field of ["baseline_reviewed_parent_sha", "remediation_implementation_sha", "tested_pr_head_sha", "tested_pr_merge_sha"]) {
    const value = fields.get(field);
    if (value && !SHA40.test(value)) findings.push(`${rel}: ${field} must be a 40-character lowercase SHA`);
  }
  if (fields.get("release_status") !== "SOURCE_POLICY_ONLY") {
    findings.push(`${rel}: release_status must remain SOURCE_POLICY_ONLY in tracked source`);
  }
  if (fields.get("FINAL_RELEASE_EVIDENCE_COMPLETE") !== "false") {
    findings.push(`${rel}: FINAL_RELEASE_EVIDENCE_COMPLETE must be false in tracked source`);
  }

  return {
    ok: findings.length === 0,
    findings,
    status: {
      STRUCTURAL_POLICY_VALID: findings.length === 0,
      CURRENT_HEAD_LOCALLY_VERIFIED: false,
      CURRENT_HEAD_CI_VERIFIED: false,
      CODEQL_CURRENT_HEAD_VERIFIED: false,
      FINAL_RELEASE_EVIDENCE_COMPLETE: false
    },
    diagnostics: { closureGateInvocations: diagnostics.closureGateInvocations.size }
  };
}

function structuralValidation(repoRoot) {
  const findings = [];
  const diagnostics = { closureGateInvocations: new Set() };
  const ledgerPath = join(repoRoot, "docs/backlog/pr267-remediation-ledger.md");
  const ledgerText = existsSync(ledgerPath) ? readFileSync(ledgerPath, "utf8") : "";
  const ledgerHasOpenFindings = /\b(PARTIAL|BLOCKED|NOT_PASS)\b/.test(ledgerText);

  for (const rel of EVIDENCE_DOCS) {
    const full = join(repoRoot, rel);
    if (!existsSync(full)) continue;
    const lines = readFileSync(full, "utf8").split(/\r?\n/);
    lines.forEach((line, i) => {
      const n = i + 1;
      for (const p of PLACEHOLDER_PATTERNS) {
        if (p.test(line)) findings.push(`${rel}:${n}: unresolved placeholder (${p})`);
      }
      const honestlyNegated = /\bnot\s+(treated|counted|marked|considered|a\s+pass)\b/i.test(line) || /NOT[_ ]PASS/i.test(line);
      if (SKIPPED_AS_PASS.test(line) && !honestlyNegated) findings.push(`${rel}:${n}: a skipped suite is described as passing`);
      if (FAKE_SHA.test(line)) findings.push(`${rel}:${n}: invented/fake SHA filler`);
      const negated = /\b(not|never|unless|until|cannot|don't|do not|must not|forbid(s|den)?)\b/i.test(line);
      if (ALL_CLOSED_CLAIM.test(line) && ledgerHasOpenFindings && !negated) {
        findings.push(`${rel}:${n}: claims all findings closed while the ledger has PARTIAL/BLOCKED/NOT_PASS entries`);
      }
      if (TARGETED_AS_FULL.test(line) && !negated) findings.push(`${rel}:${n}: a targeted test subset is described as the full suite`);
      if (DIRTY_AS_RELEASE.test(line) && !negated && !/NON-AUTHORITATIVE/i.test(line)) {
        findings.push(`${rel}:${n}: dirty working-tree output is described as release evidence`);
      }
      if (/\bfinal_reviewed_head\b/i.test(line)) findings.push(`${rel}:${n}: forbidden self-referential exact-head field final_reviewed_head`);
    });
    const text = lines.join("\n");
    checkLedgerClosureGates(rel, text, findings, diagnostics);
    checkPr269EvidenceAnchors(rel, text, findings);
    checkReleaseManifest(rel, text, findings);
  }

  return {
    ok: findings.length === 0,
    findings,
    status: {
      STRUCTURAL_POLICY_VALID: findings.length === 0,
      CURRENT_HEAD_LOCALLY_VERIFIED: false,
      CURRENT_HEAD_CI_VERIFIED: false,
      CODEQL_CURRENT_HEAD_VERIFIED: false,
      FINAL_RELEASE_EVIDENCE_COMPLETE: false
    },
    diagnostics: { closureGateInvocations: diagnostics.closureGateInvocations.size }
  };
}

function validateSha(value, field, findings, width = 40) {
  const pattern = width === 64 ? SHA64 : SHA40;
  if (!value) findings.push(`${field} is missing`);
  else if (!pattern.test(value)) findings.push(`${field} must be a ${width}-character lowercase hex SHA`);
}

function validateAttestationShape(attestation, findings) {
  const required = [
    "schemaVersion",
    "actualHeadSha",
    "workflowRunId",
    "workflowName",
    "workflowConclusion",
    "codeqlStatus",
    "generatedAt",
    "worktreeClean",
    "repository",
    "branchOrRef",
    "verificationMode",
    "evidenceComplete",
    "remainingUnproven"
  ];
  for (const field of required) {
    if (!Object.prototype.hasOwnProperty.call(attestation, field)) findings.push(`attestation.${field} is missing`);
  }
  validateSha(attestation.actualHeadSha, "actualHeadSha", findings);
  if (attestation.prHeadSha != null) validateSha(attestation.prHeadSha, "prHeadSha", findings);
  if (attestation.testedMergeSha != null) validateSha(attestation.testedMergeSha, "testedMergeSha", findings);
  if (!Array.isArray(attestation.remainingUnproven)) findings.push("remainingUnproven must be an array");
}

function localHeadValidation(repoRoot, attestationPath) {
  const findings = [];
  const head = currentHead(repoRoot);
  const status = gitStatus(repoRoot);
  const fullPath = isAbsolute(attestationPath) ? attestationPath : join(repoRoot, attestationPath);
  if (status !== "") findings.push("worktree is dirty; local exact-head verification requires a clean checkout");
  if (!existsSync(fullPath)) findings.push(`local attestation is missing: ${normalizeRepoRelative(repoRoot, fullPath)}`);
  else {
    const attestation = readJson(fullPath);
    validateAttestationShape(attestation, findings);
    if (attestation.actualHeadSha !== head) findings.push(`local attestation actualHeadSha ${attestation.actualHeadSha} does not match HEAD ${head}`);
    if (attestation.worktreeClean !== true) findings.push("local attestation worktreeClean must be true");
    if (attestation.verificationMode !== "local-head") findings.push("local attestation verificationMode must be local-head");
    if (attestation.workflowRunId || attestation.workflowConclusion || attestation.codeqlStatus === "success") {
      findings.push("local-head mode cannot accept manually supplied CI/CodeQL success claims");
    }
    if (tracked(repoRoot, fullPath)) findings.push("local exact-head attestation must not be tracked by Git");
    if (!ignored(repoRoot, fullPath)) findings.push("local exact-head attestation path must be ignored by Git");
  }
  return {
    ok: findings.length === 0,
    findings,
    status: {
      STRUCTURAL_POLICY_VALID: true,
      CURRENT_HEAD_LOCALLY_VERIFIED: findings.length === 0,
      CURRENT_HEAD_CI_VERIFIED: false,
      CODEQL_CURRENT_HEAD_VERIFIED: false,
      FINAL_RELEASE_EVIDENCE_COMPLETE: false
    },
    diagnostics: { actualHeadSha: head, branchOrRef: currentBranchOrRef(repoRoot) }
  };
}

function ciValidation(repoRoot, ciMetadataPath) {
  const findings = [];
  const head = currentHead(repoRoot);
  const status = gitStatus(repoRoot);
  if (!ciMetadataPath) findings.push("--ci-metadata-path is required in ci mode");
  const fullPath = ciMetadataPath ? (isAbsolute(ciMetadataPath) ? ciMetadataPath : join(repoRoot, ciMetadataPath)) : null;
  if (status !== "") findings.push("worktree is dirty; CI exact-head verification requires a clean checkout");
  if (fullPath && !existsSync(fullPath)) findings.push(`CI attestation is missing: ${normalizeRepoRelative(repoRoot, fullPath)}`);
  if (fullPath && existsSync(fullPath)) {
    const attestation = readJson(fullPath);
    validateAttestationShape(attestation, findings);
    if (attestation.verificationMode !== "ci") findings.push("CI attestation verificationMode must be ci");
    if (attestation.actualHeadSha !== head) findings.push(`CI attestation actualHeadSha ${attestation.actualHeadSha} does not match HEAD ${head}`);
    if (attestation.workflowHeadSha && attestation.workflowHeadSha !== head) findings.push(`workflow metadata belongs to another SHA: ${attestation.workflowHeadSha}`);
    if (attestation.workflowConclusion !== "success") findings.push("workflowConclusion must be success");
    if (attestation.codeqlStatus !== "success") findings.push("codeqlStatus must be success");
    if (!attestation.workflowRunId) findings.push("workflowRunId is required");

    const githubRepository = process.env.GITHUB_REPOSITORY;
    const githubRef = process.env.GITHUB_REF;
    const githubSha = process.env.GITHUB_SHA;
    if (!githubRepository || !githubRef || !githubSha) {
      findings.push("CI mode requires trusted GITHUB_REPOSITORY, GITHUB_REF, and GITHUB_SHA environment facts");
    } else {
      if (attestation.repository !== githubRepository) findings.push(`repository ${attestation.repository} does not match CI repository ${githubRepository}`);
      if (attestation.branchOrRef !== githubRef) findings.push(`branchOrRef ${attestation.branchOrRef} does not match CI ref ${githubRef}`);
      if (githubSha !== head) findings.push(`GITHUB_SHA ${githubSha} does not match checked-out HEAD ${head}`);
    }

    if (attestation.evidenceComplete !== false) {
      findings.push("CI mode cannot accept a self-authored evidenceComplete=true final-release claim");
    }
    if (!Array.isArray(attestation.remainingUnproven) || attestation.remainingUnproven.length === 0) {
      findings.push("CI mode must retain remainingUnproven until an independent release verifier exists");
    }
    if (attestation.eventName === "pull_request" && attestation.prHeadSha && attestation.testedMergeSha) {
      if (attestation.testedMergeSha !== head) findings.push("pull_request testedMergeSha must equal the checked-out HEAD");
      if (attestation.prHeadSha === attestation.testedMergeSha && attestation.syntheticMergeAllowed === true) {
        findings.push("synthetic merge metadata must keep prHeadSha distinct from testedMergeSha");
      }
      if (attestation.syntheticMergeAllowed !== true) findings.push("pull_request synthetic merge evidence requires syntheticMergeAllowed=true");
    }
    if (attestation.eventName === "push" && attestation.githubSha && attestation.githubSha !== head) {
      findings.push(`push github.sha ${attestation.githubSha} does not match checked-out HEAD ${head}`);
    }
  }
  const ciVerified = findings.length === 0;
  return {
    ok: ciVerified,
    findings,
    status: {
      STRUCTURAL_POLICY_VALID: true,
      CURRENT_HEAD_LOCALLY_VERIFIED: false,
      CURRENT_HEAD_CI_VERIFIED: ciVerified,
      CODEQL_CURRENT_HEAD_VERIFIED: ciVerified,
      FINAL_RELEASE_EVIDENCE_COMPLETE: false
    },
    diagnostics: { actualHeadSha: head, branchOrRef: currentBranchOrRef(repoRoot) }
  };
}

function printResult(result) {
  for (const [key, value] of Object.entries(result.status)) console.log(`${key}=${value}`);
  if (result.diagnostics) {
    for (const [key, value] of Object.entries(result.diagnostics)) console.log(`${key}=${value}`);
  }
  if (!result.ok) {
    console.error("Evidence integrity check FAILED:");
    for (const finding of result.findings) console.error(`  ${finding}`);
  } else {
    console.log("Evidence integrity check passed.");
  }
}

try {
  const args = parseArgs(process.argv.slice(2));
  let result;
  if (args.mode === "structural") {
    result = structuralValidation(args.repoRoot);
  } else {
    const structural = structuralValidation(args.repoRoot);
    result = structural.ok
      ? args.mode === "local-head"
        ? localHeadValidation(args.repoRoot, args.attestationPath)
        : ciValidation(args.repoRoot, args.ciMetadataPath)
      : structural;
  }
  printResult(result);
  process.exit(result.ok ? 0 : 1);
} catch (error) {
  console.error(`Evidence integrity check FAILED: ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
}
