import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const nextBin = require.resolve("next/dist/bin/next");
const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const TRACKED_GENERATED = ["next-env.d.ts", "tsconfig.json"].map((name) => {
  const path = join(appRoot, name);
  return { name, path, before: existsSync(path) ? readFileSync(path) : null };
});

function restoreTrackedGenerated() {
  for (const { name, path, before } of TRACKED_GENERATED) {
    if (before !== null && (!existsSync(path) || !readFileSync(path).equals(before))) {
      writeFileSync(path, before);
      console.log(`F15: restored canonical ${name} after next build rewrote it`);
    }
  }
}

const env = { ...process.env };
delete env.ORDERPILOT_NEXT_DIST_DIR;

let exitCode = 1;
try {
  const result = spawnSync(process.execPath, [nextBin, "build"], {
    stdio: "inherit",
    shell: false,
    cwd: appRoot,
    env
  });
  if (result.error) {
    console.error(result.error);
    exitCode = 1;
  } else if (result.signal) {
    exitCode = 1;
  } else {
    exitCode = result.status ?? 1;
  }
} finally {
  restoreTrackedGenerated();
}

process.exit(exitCode);
