/**
 * Start the Next.js standalone server for E2E (shell-free).
 * Usage: node e2e/standalone-server.mjs --port 3100
 *
 * Production deployments run server.js directly. E2E chooses the child process NODE_ENV
 * through Playwright webServer env instead of using production-module escape hatches.
 */
import { spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function parsePort(argv) {
  const index = argv.indexOf("--port");
  if (index < 0 || !argv[index + 1]) {
    throw new Error("--port is required");
  }
  const port = Number(argv[index + 1]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`invalid port: ${argv[index + 1]}`);
  }
  return port;
}

export function findStandaloneServerJs(rootDir) {
  const preferred = [
    join(rootDir, ".next", "standalone", "apps", "web-dashboard", "server.js"),
    join(rootDir, ".next", "standalone", "server.js")
  ].filter((candidate) => existsSync(candidate));
  if (preferred.length === 1) {
    return preferred[0];
  }
  if (preferred.length > 1) {
    throw new Error(`ambiguous preferred standalone server.js candidates: ${preferred.join(", ")}`);
  }

  const standaloneRoot = join(rootDir, ".next", "standalone");
  if (!existsSync(standaloneRoot)) {
    throw new Error(`standalone output missing at ${standaloneRoot}; run next build first`);
  }
  const matches = [];
  const stack = [standaloneRoot];
  while (stack.length > 0) {
    const current = stack.pop();
    for (const entry of readdirSync(current)) {
      if (entry === "node_modules") {
        continue;
      }
      const full = join(current, entry);
      const stat = statSync(full);
      if (stat.isDirectory()) {
        stack.push(full);
      } else if (entry === "server.js" && !full.includes(`${join("node_modules", "next")}`)) {
        matches.push(full);
      }
    }
  }
  if (matches.length === 0) {
    throw new Error("no standalone server.js found under .next/standalone");
  }
  if (matches.length > 1) {
    throw new Error(`ambiguous standalone server.js candidates: ${matches.join(", ")}`);
  }
  return matches[0];
}

export function prepareStandaloneAssets(rootDir, serverJsPath) {
  const serverDir = dirname(serverJsPath);
  const staticSrc = join(rootDir, ".next", "static");
  const staticDest = join(serverDir, ".next", "static");
  if (existsSync(staticSrc)) {
    mkdirSync(dirname(staticDest), { recursive: true });
    cpSync(staticSrc, staticDest, { recursive: true });
  }
  const publicSrc = join(rootDir, "public");
  const publicDest = join(serverDir, "public");
  if (existsSync(publicSrc)) {
    cpSync(publicSrc, publicDest, { recursive: true });
  }
}

function main() {
  const port = parsePort(process.argv);
  const serverJs = findStandaloneServerJs(appRoot);
  prepareStandaloneAssets(appRoot, serverJs);
  const serverDir = dirname(serverJs);
  const child = spawn(process.execPath, [serverJs], {
    cwd: serverDir,
    env: {
      ...process.env,
      PORT: String(port),
      HOSTNAME: "127.0.0.1"
    },
    stdio: "inherit",
    shell: false
  });
  child.on("exit", (code, signal) => {
    if (signal) {
      process.kill(process.pid, signal);
      return;
    }
    process.exit(code ?? 0);
  });
}

const isCli =
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isCli) {
  main();
}
