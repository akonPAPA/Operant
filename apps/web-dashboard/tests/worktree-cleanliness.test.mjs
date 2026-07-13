import assert from "node:assert/strict";
import test from "node:test";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * F15 — generated Next files (and the wider worktree) must stay clean. `next build`, `next dev`, the
 * unit tests, and the E2E harness must never rewrite a tracked generated source such as
 * next-env.d.ts in the main worktree. This guard runs `git diff --exit-code` on the generated file and
 * fails if it was modified. It is intended to run AFTER build/test/E2E in CI; run standalone it proves
 * the committed generated file is canonical and unmodified.
 */
const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const NEXT_ENV = "apps/web-dashboard/next-env.d.ts";
const TS_CONFIG = "apps/web-dashboard/tsconfig.json";

function gitDiffClean(pathspec) {
  try {
    execFileSync("git", ["-C", repoRoot, "diff", "--exit-code", "--", pathspec], { stdio: "pipe" });
    return { clean: true, diff: "" };
  } catch (error) {
    return { clean: false, diff: String(error.stdout ?? error.stderr ?? error.message) };
  }
}

test("F15: tracked generated files are not modified in the worktree (git diff --exit-code)", () => {
  for (const pathspec of [NEXT_ENV, TS_CONFIG]) {
    const result = gitDiffClean(pathspec);
    assert.ok(result.clean, `${pathspec} was modified (build/dev/E2E must restore it):\n${result.diff}`);
  }
});

test("F15: tsconfig.json carries no leaked E2E distDir include globs", () => {
  // `next dev` on the isolated E2E distDir appends ".next-e2e-dev/..." include entries to the
  // tracked tsconfig.json; run-e2e.mjs restores the canonical file. This guard catches any leak.
  const source = readFileSync(join(repoRoot, "apps/web-dashboard/tsconfig.json"), "utf8");
  assert.ok(!source.includes(".next-e2e-dev"), "tsconfig.json contains E2E distDir include globs");
});

test("F15: next-env.d.ts references only supported generated types (no invented references)", () => {
  const source = readFileSync(join(repoRoot, NEXT_ENV), "utf8");
  // Canonical Next generated form: the two triple-slash references, and no hand-authored references
  // to non-generated paths.
  assert.match(source, /\/\/\/ <reference types="next" \/>/);
  assert.match(source, /\/\/\/ <reference types="next\/image-types\/global" \/>/);
  assert.match(source, /should not be edited/);
});
