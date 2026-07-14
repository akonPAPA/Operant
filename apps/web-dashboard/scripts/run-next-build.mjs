import { spawnSync } from "node:child_process";
import {
  closeSync,
  constants,
  fstatSync,
  ftruncateSync,
  openSync,
  readFileSync,
  writeSync
} from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const nextBin = require.resolve("next/dist/bin/next");
const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const NO_FOLLOW = typeof constants.O_NOFOLLOW === "number" ? constants.O_NOFOLLOW : 0;

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

const TRACKED_GENERATED = ["next-env.d.ts", "tsconfig.json"].map((name) => {
  const path = join(appRoot, name);
  const { bytes, mode } = readRegularFileSnapshot(path);
  return { name, path, before: bytes, mode };
});

function restoreTrackedGenerated() {
  for (const { name, path, before, mode } of TRACKED_GENERATED) {
    if (restoreRegularFile(path, before, mode)) {
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
