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
 */
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
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
  const entries = [];
  const stack = [dir];
  while (stack.length > 0) {
    const current = stack.pop();
    for (const name of readdirSync(current).sort()) {
      const full = join(current, name);
      const stat = statSync(full);
      if (stat.isDirectory()) {
        stack.push(full);
      } else if (stat.isFile()) {
        const digest = createHash("sha256").update(readFileSync(full)).digest("hex");
        entries.push(`${relative(dir, full).replaceAll("\\", "/")} ${digest}`);
      }
    }
  }
  entries.sort();
  return createHash("sha256").update(entries.join("\n")).digest("hex") + ` (${entries.length} files)`;
}

function cleanDevDist() {
  rmSync(devDist, { recursive: true, force: true });
}

// F15: `next dev` on the isolated distDir rewrites TRACKED generated-config sources in the app
// root — next-env.d.ts (routes.d.ts import path) and tsconfig.json (distDir include globs).
// Snapshot the canonical committed content now and restore it deterministically after the run
// (success or failure), so E2E never leaves a modified tracked file in the main worktree.
const TRACKED_GENERATED = ["next-env.d.ts", "tsconfig.json"].map((name) => {
  const path = join(appRoot, name);
  return { name, path, before: existsSync(path) ? readFileSync(path) : null };
});

function restoreTrackedGenerated() {
  for (const { name, path, before } of TRACKED_GENERATED) {
    if (before !== null && (!existsSync(path) || !readFileSync(path).equals(before))) {
      writeFileSync(path, before);
      console.log(`F15: restored canonical ${name} after the E2E dev runtime rewrote it`);
    }
  }
}

let exitCode = 0;
cleanDevDist();
try {
  exitCode = run([nextBin, "build"]);
  if (exitCode !== 0) {
    process.exit(exitCode);
  }

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
  if (existsSync(join(prodDist, "standalone")) === false) {
    console.error("F13: expected standalone output under .next/standalone");
    exitCode = exitCode === 0 ? 1 : exitCode;
  }
} finally {
  cleanDevDist();
  restoreTrackedGenerated();
}
process.exit(exitCode);
