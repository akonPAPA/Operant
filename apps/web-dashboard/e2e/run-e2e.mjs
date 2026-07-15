/**
 * P1-B E2E runner: builds the dashboard as a production (non-demo) bundle and then runs
 * Playwright. The explicit env override beats any local .env.local demo configuration so
 * the tested bundle is the real production browser transport (same-origin /api/bff).
 *
 * F13 — artifact isolation is enforced here, not assumed:
 *  - the production build writes `.next` (ORDERPILOT_NEXT_DIST_DIR is explicitly cleared);
 *  - the E2E dev runtime (dev-server.mjs) writes only the isolated, gitignored `.next-e2e-dev`;
 *  - standalone assets are prepared BEFORE the artifact snapshot, so the snapshot covers the
 *    exact production artifact the :3101 server runs;
 *  - after Playwright finishes, the `.next` manifest is re-hashed: any mutation of the production
 *    artifact during E2E fails the run;
 *  - the isolated dev dist dir is removed before and after every run (success or failure), so
 *    repeated runs are deterministic and nothing leaks into the worktree (it is gitignored too).
 *
 * Cleanup is best-effort for normal build failures, Playwright failures, and handled exits. SIGKILL
 * terminates the process immediately, so no Node finally block can guarantee restoration in that case.
 */
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { closeSync, constants, fstatSync, ftruncateSync, lstatSync, openSync, readFileSync, readdirSync, rmSync, writeSync } from "node:fs";
import { createRequire } from "node:module";
import { isAbsolute, join, relative, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { findStandaloneServerJs, prepareStandaloneAssets } from "./standalone-server.mjs";

const require = createRequire(import.meta.url);
const nextBin = require.resolve("next/dist/bin/next");
const playwrightBin = require.resolve("@playwright/test/cli");
const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const prodDist = join(appRoot, ".next");
const devDist = join(appRoot, ".next-e2e-dev");
const demoDevDist = join(appRoot, ".next-e2e-demo");

const NOFOLLOW = process.platform === "win32" ? 0 : (constants.O_NOFOLLOW ?? 0);

function assertInsideAppRoot(path) {
  const absolute = resolve(path);
  const rel = relative(appRoot, absolute);
  if (rel === "" || (!rel.startsWith("..") && !isAbsolute(rel))) return absolute;
  throw new Error(`refusing out-of-tree path: ${path}`);
}

function lstatNoSymlink(path) {
  const absolute = assertInsideAppRoot(path);
  const stat = lstatSync(absolute);
  if (stat.isSymbolicLink()) throw new Error(`refusing symlink path: ${relative(appRoot, absolute)}`);
  return { absolute, stat };
}

function pathExistsNoSymlink(path) {
  try { lstatNoSymlink(path); return true; }
  catch (error) { if (error?.code === "ENOENT") return false; throw error; }
}

function readFileNoSymlink(path) {
  const { absolute } = lstatNoSymlink(path);
  const fd = openSync(absolute, constants.O_RDONLY | NOFOLLOW);
  try { if (!fstatSync(fd).isFile()) throw new Error(`expected file: ${relative(appRoot, absolute)}`); return readFileSync(fd); }
  finally { closeSync(fd); }
}

function writeExistingFileNoSymlink(path, bytes) {
  const { absolute } = lstatNoSymlink(path);
  const fd = openSync(absolute, constants.O_RDWR | NOFOLLOW);
  try {
    if (!fstatSync(fd).isFile()) throw new Error(`expected file: ${relative(appRoot, absolute)}`);
    ftruncateSync(fd, 0);
    writeSync(fd, bytes);
  } finally {
    closeSync(fd);
  }
}

function createFileNoSymlink(path, bytes) {
  const absolute = assertInsideAppRoot(path);
  const fd = openSync(absolute, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | NOFOLLOW, 0o600);
  try { if (!fstatSync(fd).isFile()) throw new Error(`expected file: ${relative(appRoot, absolute)}`); writeSync(fd, bytes); }
  finally { closeSync(fd); }
}
const overrides = {
  NEXT_PUBLIC_ORDERPILOT_DEMO_MODE: "false",
  ORDERPILOT_DEMO_MODE: "false"
};

function run(nodeArgs) {
  const env = { ...process.env, ...overrides };
  // The production build must never be redirected into the dev dist dir.
  delete env.ORDERPILOT_NEXT_DIST_DIR;
  const result = spawnSync(process.execPath, nodeArgs, {
    stdio: "inherit",
    shell: false,
    cwd: appRoot,
    env
  });
  if (result.error) {
    console.error(result.error);
    return 1;
  }
  if (result.signal) {
    return 1;
  }
  return result.status ?? 1;
}

/** Deterministic content-hash manifest of every file under dir (relative path + SHA-256). */
function artifactManifest(dir) {
  const rootDir = assertInsideAppRoot(dir);
  const entries = [];
  const stack = [rootDir];
  while (stack.length > 0) {
    const current = stack.pop();
    for (const entry of readdirSync(current, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const full = assertInsideAppRoot(join(current, entry.name));
      const { stat } = lstatNoSymlink(full);
      if (stat.isDirectory()) stack.push(full);
      else if (stat.isFile()) {
        const digest = createHash("sha256").update(readFileNoSymlink(full)).digest("hex");
        entries.push(`${relative(rootDir, full).replaceAll("\\", "/")} ${digest}`);
      }
    }
  }
  entries.sort();
  return createHash("sha256").update(entries.join("\n")).digest("hex") + ` (${entries.length} files)`;
}

function cleanDevDist() {
  rmSync(devDist, { recursive: true, force: true });
  rmSync(demoDevDist, { recursive: true, force: true });
}

// F15: `next dev` on the isolated distDir rewrites TRACKED generated-config sources in the app
// root — next-env.d.ts (routes.d.ts import path) and tsconfig.json (distDir include globs).
// Snapshot the canonical committed content now and restore it deterministically after the run
// (success or failure), so E2E never leaves a modified tracked file in the main worktree.
const TRACKED_GENERATED = ["next-env.d.ts", "tsconfig.json"].map((name) => {
  const path = join(appRoot, name);
  return { name, path, before: pathExistsNoSymlink(path) ? readFileNoSymlink(path) : null };
});

function restoreTrackedGenerated() {
  for (const { name, path, before } of TRACKED_GENERATED) {
    if (before === null) continue;
    const current = pathExistsNoSymlink(path) ? readFileNoSymlink(path) : null;
    if (current === null) {
      createFileNoSymlink(path, before);
      console.log(`F15: restored canonical ${name} after the E2E dev runtime removed it`);
    } else if (!current.equals(before)) {
      writeExistingFileNoSymlink(path, before);
      console.log(`F15: restored canonical ${name} after the E2E dev runtime rewrote it`);
    }
  }
}

let exitCode = 0;
cleanDevDist();
try {
  exitCode = run([nextBin, "build"]);
  if (exitCode === 0) {
    // Prepare the standalone runtime assets NOW so the artifact snapshot below covers the exact
    // tree the :3101 production server executes, and nothing mutates `.next` after the snapshot.
    prepareStandaloneAssets(appRoot, findStandaloneServerJs(appRoot));
    const before = artifactManifest(prodDist);
    console.log(`F13 production artifact snapshot: ${before}`);

    exitCode = run([playwrightBin, "test"]);

    const after = artifactManifest(prodDist);
    if (after !== before) {
      console.error(
        `F13 VIOLATION: the production artifact .next changed during E2E.\n before: ${before}\n after:  ${after}`
      );
      exitCode = exitCode === 0 ? 1 : exitCode;
    } else {
      console.log(`F13 production artifact unchanged after E2E: ${after}`);
    }
    if (pathExistsNoSymlink(join(prodDist, "standalone")) === false) {
      console.error("F13: expected standalone output under .next/standalone");
      exitCode = exitCode === 0 ? 1 : exitCode;
    }
  }
} finally {
  cleanDevDist();
  restoreTrackedGenerated();
}
process.exit(exitCode);
