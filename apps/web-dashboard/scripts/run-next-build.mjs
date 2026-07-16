import { spawnSync } from "node:child_process";
import { closeSync, constants, fstatSync, ftruncateSync, lstatSync, openSync, readFileSync, writeSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

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
const require = createRequire(import.meta.url);
const nextBin = require.resolve("next/dist/bin/next");
const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

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
      console.log(`F15: restored canonical ${name} after next build removed it`);
    } else if (!current.equals(before)) {
      writeExistingFileNoSymlink(path, before);
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
