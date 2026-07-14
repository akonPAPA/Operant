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
import {
  closeSync,
  constants,
  fstatSync,
  ftruncateSync,
  openSync,
  readdirSync,
  readFileSync,
  readlinkSync,
  rmSync,
  statSync,
  writeSync
} from "node:fs";
import { createRequire } from "node:module";
import { join, relative, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { findStandaloneServerJs, prepareStandaloneAssets } from "./standalone-server.mjs";

const require = createRequire(import.meta.url);
const nextBin = require.resolve("next/dist/bin/next");
const playwrightBin = require.resolve("@playwright/test/cli");
const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const prodDist = join(appRoot, ".next");
const devDist = join(appRoot, ".next-e2e-dev");
const NO_FOLLOW = typeof constants.O_NOFOLLOW === "number" ? constants.O_NOFOLLOW : 0;

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

/**
 * Open, verify, and read one regular file through a single descriptor.
 *
 * The descriptor pins the opened object, so there is no check-by-path followed by a second
 * path-based use. O_NOFOLLOW prevents a symlink swap where the platform supports it.
 */
function readRegularFileSnapshot(path) {
  const fd = openSync(path, constants.O_RDONLY | NO_FOLLOW);
  try {
    const stat = fstatSync(fd);
    if (!stat.isFile()) {
      throw new Error(`Expected a regular file: ${path}`);
    }
    return { bytes: readFileSync(fd), mode: stat.mode & 0o777 };
  } finally {
    closeSync(fd);
  }
}

/**
 * Restore through one read/write descriptor. Missing files are recreated with O_EXCL; if another
 * process wins that creation race, restoration fails instead of following or overwriting its path.
 */
function restoreRegularFile(path, before, mode) {
  let fd;
  let created = false;
  try {
    try {
      fd = openSync(path, constants.O_RDWR | NO_FOLLOW);
    } catch (error) {
      if (!error || typeof error !== "object" || error.code !== "ENOENT") {
        throw error;
      }
      fd = openSync(
        path,
        constants.O_RDWR | constants.O_CREAT | constants.O_EXCL | NO_FOLLOW,
        mode
      );
      created = true;
    }

    const stat = fstatSync(fd);
    if (!stat.isFile()) {
      throw new Error(`Refusing to restore a non-regular file: ${path}`);
    }

    const current = created ? Buffer.alloc(0) : readFileSync(fd);
    if (current.equals(before)) {
      return false;
    }

    ftruncateSync(fd, 0);
    writeSync(fd, before, 0, before.length, 0);
    return true;
  } finally {
    if (fd !== undefined) {
      closeSync(fd);
    }
  }
}

/** Deterministic content-hash manifest of every file under dir (relative path + SHA-256). */
function artifactManifest(dir) {
  const entries = [];
  const stack = [dir];
  while (stack.length > 0) {
    const current = stack.pop();
    const children = readdirSync(current, { withFileTypes: true })
      .sort((left, right) => left.name.localeCompare(right.name));
    for (const child of children) {
      const full = join(current, child.name);
      const relativePath = relative(dir, full).replaceAll("\\", "/");
      if (child.isDirectory()) {
        stack.push(full);
      } else if (child.isFile()) {
        const { bytes } = readRegularFileSnapshot(full);
        const digest = createHash("sha256").update(bytes).digest("hex");
        entries.push(`F ${relativePath} ${digest}`);
      } else if (child.isSymbolicLink()) {
        // Hash the link itself; do not follow it into an object outside the artifact tree.
        entries.push(`L ${relativePath} ${readlinkSync(full)}`);
      }
    }
  }
  entries.sort();
  return createHash("sha256").update(entries.join("\n")).digest("hex") + ` (${entries.length} entries)`;
}

function cleanDevDist() {
  rmSync(devDist, { recursive: true, force: true });
}

// F15: `next dev` on the isolated distDir rewrites TRACKED generated-config sources in the app
// root — next-env.d.ts (routes.d.ts import path) and tsconfig.json (distDir include globs).
// Snapshot each required tracked file through a pinned descriptor and restore it through the same
// descriptor model, so the cleanup has no check-then-use filesystem race.
const TRACKED_GENERATED = ["next-env.d.ts", "tsconfig.json"].map((name) => {
  const path = join(appRoot, name);
  const { bytes, mode } = readRegularFileSnapshot(path);
  return { name, path, before: bytes, mode };
});

function restoreTrackedGenerated() {
  for (const { name, path, before, mode } of TRACKED_GENERATED) {
    if (restoreRegularFile(path, before, mode)) {
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
    try {
      if (!statSync(join(prodDist, "standalone")).isDirectory()) {
        throw new Error("not a directory");
      }
    } catch {
      console.error("F13: expected standalone output under .next/standalone");
      exitCode = exitCode === 0 ? 1 : exitCode;
    }
  }
} finally {
  cleanDevDist();
  restoreTrackedGenerated();
}
process.exit(exitCode);
