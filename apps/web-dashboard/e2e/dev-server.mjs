/**
 * Start the Next.js development server for E2E on a given port (shell-free).
 * Usage: node e2e/dev-server.mjs --port 3100
 *
 * This is the genuine NON-production local/test runtime for the E2E harness. It is the only
 * server where explicit local/test bootstrap may mint a session, and it exists precisely so the
 * harness never has to weaken isProductionNodeRuntime() or launch the production standalone
 * artifact under a fake NODE_ENV. `next dev` runs a real Node development runtime (NODE_ENV is
 * never "production"), so isProductionNodeRuntime() reports false and the existing local/test
 * bootstrap authorization rules apply unchanged.
 *
 * The production fail-closed target (:3101) is launched separately from the production standalone
 * artifact via standalone-server.mjs; the two runtimes are intentionally different processes.
 */
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const require = createRequire(import.meta.url);
const nextBin = require.resolve("next/dist/bin/next");
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

function main() {
  const port = parsePort(process.argv);
  // Guard against ever booting the dev server as a production Node runtime: `next dev` must remain
  // non-production so local/test bootstrap is legitimately available on this port only.
  if (process.env.NODE_ENV === "production") {
    throw new Error("dev-server.mjs must not run with NODE_ENV=production");
  }
  const child = spawn(
    process.execPath,
    [nextBin, "dev", "--port", String(port), "--hostname", "127.0.0.1"],
    {
      cwd: appRoot,
      env: { ...process.env },
      stdio: "inherit",
      shell: false
    }
  );
  child.on("exit", (code, signal) => {
    if (signal) {
      process.kill(process.pid, signal);
      return;
    }
    process.exit(code ?? 0);
  });
}

const isCli =
  process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isCli) {
  main();
}
