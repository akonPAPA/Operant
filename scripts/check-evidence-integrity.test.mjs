import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const scriptPath = resolve(dirname(fileURLToPath(import.meta.url)), "check-evidence-integrity.mjs");
const GOOD_SHA = "1111111111111111111111111111111111111111";
const OTHER_SHA = "2222222222222222222222222222222222222222";
const MERGE_SHA = "3333333333333333333333333333333333333333";

function git(repo, args) {
  return execFileSync("git", args, { cwd: repo, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

function run(repo, args = []) {
  const result = spawnSync(process.execPath, [scriptPath, "--repo-root", repo, ...args], {
    cwd: repo,
    encoding: "utf8"
  });
  return {
    status: result.status,
    stdout: result.stdout,
    stderr: result.stderr,
    combined: `${result.stdout}\n${result.stderr}`
  };
}

function manifest({ finalReviewedHead = null, releaseComplete = "false" } = {}) {
  const finalRow = finalReviewedHead ? `| \`final_reviewed_head\` | \`${finalReviewedHead}\` |\n` : "";
  return `# Release Evidence Manifest

## Tracked repository evidence policy

| Field | Value |
| --- | --- |
| \`evidence_schema_version\` | \`2\` |
| \`release_candidate\` | \`test-rc\` |
| \`release_status\` | \`PENDING_FINAL_COMMIT\` |
| \`baseline_reviewed_parent_sha\` | \`${GOOD_SHA}\` |
| \`runtime_attestation_path\` | \`build/evidence/local-head-attestation.json\` |

## Historical immutable anchors

| Field | SHA / status |
| --- | --- |
| \`remediation_implementation_sha\` | \`${GOOD_SHA}\` |
| \`tested_pr_head_sha\` | \`${GOOD_SHA}\` |
| \`tested_pr_merge_sha\` | \`${MERGE_SHA}\` |
${finalRow}
## Current-head evidence gate

| Field | Status |
| --- | --- |
| \`STRUCTURAL_POLICY_VALID\` | \`false\` |
| \`CURRENT_HEAD_LOCALLY_VERIFIED\` | \`false\` |
| \`CURRENT_HEAD_CI_VERIFIED\` | \`false\` |
| \`CODEQL_CURRENT_HEAD_VERIFIED\` | \`false\` |
| \`FINAL_RELEASE_EVIDENCE_COMPLETE\` | \`${releaseComplete}\` |
`;
}

function initRepo({ withLedger = false } = {}) {
  const repo = mkdtempSync(join(tmpdir(), "op-evidence-"));
  git(repo, ["init", "-q"]);
  git(repo, ["config", "user.email", "test@example.invalid"]);
  git(repo, ["config", "user.name", "Evidence Test"]);
  writeFileSync(join(repo, ".gitignore"), "build/\n", "utf8");
  mkdirSync(join(repo, "docs", "production"), { recursive: true });
  writeFileSync(join(repo, "docs", "production", "RELEASE_EVIDENCE_MANIFEST.md"), manifest(), "utf8");
  if (withLedger) {
    mkdirSync(join(repo, "docs", "backlog"), { recursive: true });
    writeFileSync(join(repo, "docs", "backlog", "pr267-remediation-ledger.md"), ledger(), "utf8");
  }
  git(repo, ["add", "."]);
  git(repo, ["commit", "-q", "-m", "init"]);
  return repo;
}

function ledger() {
  return `# Ledger

Remediation implementation SHA: \`${GOOD_SHA}\`
Tested PR head SHA: \`${GOOD_SHA}\`
Tested PR merge-test SHA: \`${MERGE_SHA}\`
Frontend workflow run \`12345678\`: **SUCCESS**
CI workflow run \`12345679\`: **SUCCESS**
Backend workflow run \`12345680\`: **SUCCESS**
AI Worker workflow run \`12345681\`: **SUCCESS**

| ID | Status | Evidence |
| --- | --- | --- |
| F13 | CLOSED | build and Playwright e2e proof |
| F15 | CLOSED | git diff and untracked cleanliness proof |
`;
}

function head(repo) {
  return git(repo, ["rev-parse", "HEAD"]);
}

function writeAttestation(repo, overrides = {}, path = "build/evidence/local-head-attestation.json") {
  const full = join(repo, path);
  mkdirSync(dirname(full), { recursive: true });
  const body = {
    schemaVersion: 1,
    actualHeadSha: head(repo),
    prHeadSha: null,
    testedMergeSha: null,
    workflowRunId: null,
    workflowName: null,
    workflowConclusion: null,
    codeqlStatus: "not_provided",
    generatedAt: "2026-07-14T00:00:00.000Z",
    worktreeClean: true,
    repository: "fixture/repo",
    branchOrRef: "main",
    verificationMode: "local-head",
    evidenceComplete: false,
    remainingUnproven: ["CI", "CodeQL"],
    ...overrides
  };
  writeFileSync(full, JSON.stringify(body, null, 2), "utf8");
  return path;
}

function cleanup(repo) {
  rmSync(repo, { recursive: true, force: true });
}

test("structural tracked manifest with non-final status passes and reports final evidence incomplete", () => {
  const repo = initRepo();
  try {
    const result = run(repo, ["--mode", "structural"]);
    assert.equal(result.status, 0, result.combined);
    assert.match(result.stdout, /STRUCTURAL_POLICY_VALID=true/);
    assert.match(result.stdout, /FINAL_RELEASE_EVIDENCE_COMPLETE=false/);
  } finally {
    cleanup(repo);
  }
});

test("tracked manifest containing final_reviewed_head fails with a clear error", () => {
  const repo = initRepo();
  try {
    writeFileSync(join(repo, "docs", "production", "RELEASE_EVIDENCE_MANIFEST.md"), manifest({ finalReviewedHead: head(repo) }), "utf8");
    const result = run(repo, ["--mode", "structural"]);
    assert.notEqual(result.status, 0);
    assert.match(result.combined, /final_reviewed_head is prohibited|forbidden self-referential/);
  } finally {
    cleanup(repo);
  }
});

test("clean repository and local attestation matching HEAD verifies only local exact head", () => {
  const repo = initRepo();
  try {
    const attestationPath = writeAttestation(repo);
    const result = run(repo, ["--mode", "local-head", "--attestation-path", attestationPath]);
    assert.equal(result.status, 0, result.combined);
    assert.match(result.stdout, /CURRENT_HEAD_LOCALLY_VERIFIED=true/);
    assert.match(result.stdout, /CURRENT_HEAD_CI_VERIFIED=false/);
    assert.match(result.stdout, /CODEQL_CURRENT_HEAD_VERIFIED=false/);
    assert.match(result.stdout, /FINAL_RELEASE_EVIDENCE_COMPLETE=false/);
  } finally {
    cleanup(repo);
  }
});

test("dirty worktree fails exact-head validation", () => {
  const repo = initRepo();
  try {
    const attestationPath = writeAttestation(repo);
    writeFileSync(join(repo, "dirty.txt"), "dirty", "utf8");
    const result = run(repo, ["--mode", "local-head", "--attestation-path", attestationPath]);
    assert.notEqual(result.status, 0);
    assert.match(result.combined, /worktree is dirty/);
  } finally {
    cleanup(repo);
  }
});

test("well-formed stale SHA fails local-head validation", () => {
  const repo = initRepo();
  try {
    const attestationPath = writeAttestation(repo, { actualHeadSha: OTHER_SHA });
    const result = run(repo, ["--mode", "local-head", "--attestation-path", attestationPath]);
    assert.notEqual(result.status, 0);
    assert.match(result.combined, /does not match HEAD/);
  } finally {
    cleanup(repo);
  }
});

test("HEAD advancing after attestation generation fails", () => {
  const repo = initRepo();
  try {
    const attestationPath = writeAttestation(repo);
    git(repo, ["commit", "--allow-empty", "-q", "-m", "advance"]);
    const result = run(repo, ["--mode", "local-head", "--attestation-path", attestationPath]);
    assert.notEqual(result.status, 0);
    assert.match(result.combined, /does not match HEAD/);
  } finally {
    cleanup(repo);
  }
});

test("missing SHA fails", () => {
  const repo = initRepo();
  try {
    const attestationPath = writeAttestation(repo, { actualHeadSha: undefined });
    const result = run(repo, ["--mode", "local-head", "--attestation-path", attestationPath]);
    assert.notEqual(result.status, 0);
    assert.match(result.combined, /actualHeadSha is missing|actualHeadSha.*must be/);
  } finally {
    cleanup(repo);
  }
});

for (const [name, badSha] of [
  ["uppercase", GOOD_SHA.toUpperCase()],
  ["shortened", GOOD_SHA.slice(0, 12)],
  ["malformed", `${GOOD_SHA.slice(0, 39)}z`],
  ["non-hex", "not-a-sha"]
]) {
  test(`${name} SHA fails`, () => {
    const repo = initRepo();
    try {
      const attestationPath = writeAttestation(repo, { actualHeadSha: badSha });
      const result = run(repo, ["--mode", "local-head", "--attestation-path", attestationPath]);
      assert.notEqual(result.status, 0);
      assert.match(result.combined, /actualHeadSha must be a 40-character lowercase hex SHA|does not match HEAD/);
    } finally {
      cleanup(repo);
    }
  });
}

test("workflow run metadata pointing to another SHA fails CI validation", () => {
  const repo = initRepo();
  try {
    const path = writeAttestation(repo, {
      workflowRunId: "123456789",
      workflowName: "CI",
      workflowConclusion: "success",
      codeqlStatus: "success",
      verificationMode: "ci",
      evidenceComplete: true,
      remainingUnproven: [],
      workflowHeadSha: OTHER_SHA
    }, "build/evidence/ci-head-attestation.json");
    const result = run(repo, ["--mode", "ci", "--ci-metadata-path", path]);
    assert.notEqual(result.status, 0);
    assert.match(result.combined, /workflow metadata belongs to another SHA/);
  } finally {
    cleanup(repo);
  }
});

test("successful workflow conclusion on another push SHA fails", () => {
  const repo = initRepo();
  try {
    const path = writeAttestation(repo, {
      workflowRunId: "123456789",
      workflowName: "CI",
      workflowConclusion: "success",
      codeqlStatus: "success",
      verificationMode: "ci",
      evidenceComplete: true,
      remainingUnproven: [],
      eventName: "push",
      githubSha: OTHER_SHA
    }, "build/evidence/ci-head-attestation.json");
    const result = run(repo, ["--mode", "ci", "--ci-metadata-path", path]);
    assert.notEqual(result.status, 0);
    assert.match(result.combined, /push github\.sha .* does not match/);
  } finally {
    cleanup(repo);
  }
});

test("PR head and tested synthetic merge SHA are represented separately and accepted when allowed", () => {
  const repo = initRepo();
  try {
    const path = writeAttestation(repo, {
      workflowRunId: "123456789",
      workflowName: "CI",
      workflowConclusion: "success",
      codeqlStatus: "success",
      verificationMode: "ci",
      evidenceComplete: true,
      remainingUnproven: [],
      eventName: "pull_request",
      prHeadSha: OTHER_SHA,
      testedMergeSha: head(repo),
      syntheticMergeAllowed: true,
      workflowHeadSha: head(repo)
    }, "build/evidence/ci-head-attestation.json");
    const result = run(repo, ["--mode", "ci", "--ci-metadata-path", path]);
    assert.equal(result.status, 0, result.combined);
    assert.match(result.stdout, /CURRENT_HEAD_CI_VERIFIED=true/);
    assert.match(result.stdout, /FINAL_RELEASE_EVIDENCE_COMPLETE=true/);
  } finally {
    cleanup(repo);
  }
});

test("offline local mode cannot mark manually supplied CodeQL success as externally verified", () => {
  const repo = initRepo();
  try {
    const attestationPath = writeAttestation(repo, { codeqlStatus: "success" });
    const result = run(repo, ["--mode", "local-head", "--attestation-path", attestationPath]);
    assert.notEqual(result.status, 0);
    assert.match(result.combined, /cannot accept manually supplied CI\/CodeQL success claims/);
  } finally {
    cleanup(repo);
  }
});

test("closure gate checks run once per intended ledger context", () => {
  const repo = initRepo({ withLedger: true });
  try {
    const result = run(repo, ["--mode", "structural"]);
    assert.equal(result.status, 0, result.combined);
    assert.match(result.stdout, /closureGateInvocations=4/);
    assert.doesNotMatch(result.combined, /duplicate closure gate invocation/);
  } finally {
    cleanup(repo);
  }
});

test("repeated structural validation is deterministic and does not mutate records", () => {
  const repo = initRepo({ withLedger: true });
  try {
    const first = run(repo, ["--mode", "structural"]);
    const second = run(repo, ["--mode", "structural"]);
    assert.equal(first.status, 0, first.combined);
    assert.equal(second.status, 0, second.combined);
    assert.equal(second.stdout, first.stdout);
    assert.equal(git(repo, ["status", "--short"]), "");
  } finally {
    cleanup(repo);
  }
});

test("generated exact-head attestation path is ignored and not included in tracked release source", () => {
  const repo = initRepo();
  try {
    const attestationPath = writeAttestation(repo);
    assert.equal(git(repo, ["check-ignore", attestationPath]), attestationPath);
    assert.throws(() => git(repo, ["ls-files", "--error-unmatch", attestationPath]));
    const result = run(repo, ["--mode", "local-head", "--attestation-path", attestationPath]);
    assert.equal(result.status, 0, result.combined);
  } finally {
    cleanup(repo);
  }
});

test("syntactically valid tracked evidence alone cannot produce final GO state", () => {
  const repo = initRepo();
  try {
    const result = run(repo, ["--mode", "structural"]);
    assert.equal(result.status, 0, result.combined);
    assert.match(result.stdout, /CURRENT_HEAD_CI_VERIFIED=false/);
    assert.match(result.stdout, /CODEQL_CURRENT_HEAD_VERIFIED=false/);
    assert.match(result.stdout, /FINAL_RELEASE_EVIDENCE_COMPLETE=false/);
  } finally {
    cleanup(repo);
  }
});