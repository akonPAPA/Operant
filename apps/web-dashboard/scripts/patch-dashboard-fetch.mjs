import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const dir = "lib";
const skip = new Set(["api-transport.ts", "bff/dashboard-fetch.ts"]);

for (const name of readdirSync(dir)) {
  if (!name.endsWith("-api.ts")) continue;
  const path = join(dir, name);
  if (skip.has(name)) continue;
  let src = readFileSync(path, "utf8");
  if (!src.includes("await fetch(") || src.includes("dashboardFetchUrl")) continue;
  if (!src.includes("dashboardCoreApiBaseUrl") && !src.includes("baseUrl")) continue;

  if (!src.includes("dashboardFetchUrl")) {
    if (!src.includes('from "./bff/dashboard-fetch"')) {
      src = `import { dashboardFetchHeaders, dashboardFetchUrl } from "./bff/dashboard-fetch";\n${src}`;
    }
  }

  src = src.replace(
    /await fetch\(`\$\{([^}]+)\}\$\{([^}]+)\}`/g,
    "await fetch(dashboardFetchUrl($2)"
  );
  src = src.replace(
    /await fetch\(`\$\{([^}]+)\}\$\{path\}`/g,
    "await fetch(dashboardFetchUrl(path)"
  );
  src = src.replace(
    /headers:\s*\{([^}]*)\.\.\.\(init\?\.headers/g,
    "headers: { ...dashboardFetchHeaders(init), $1...(init?.headers"
  );
  writeFileSync(path, src);
  console.log("patched fetch", path);
}
