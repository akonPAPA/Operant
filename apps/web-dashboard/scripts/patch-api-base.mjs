import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const dir = "lib";
const patterns = [
  [/process\.env\.CORE_API_BASE_URL\s*\?\?\s*process\.env\.NEXT_PUBLIC_CORE_API_URL\s*\?\?\s*DEFAULT_BASE_URL/g, "dashboardCoreApiBaseUrl()"],
  [/process\.env\.NEXT_PUBLIC_CORE_API_URL\s*\?\?\s*process\.env\.CORE_API_BASE_URL\s*\?\?\s*DEFAULT_BASE_URL/g, "dashboardCoreApiBaseUrl()"],
  [/process\.env\.NEXT_PUBLIC_CORE_API_URL\s*\?\?\s*DEFAULT_BASE_URL/g, "dashboardCoreApiBaseUrl()"],
  [/const baseUrl = process\.env\.CORE_API_BASE_URL \?\? process\.env\.NEXT_PUBLIC_CORE_API_URL \?\? DEFAULT_BASE_URL;/g, "const baseUrl = dashboardCoreApiBaseUrl();"],
  [/const baseUrl = process\.env\.NEXT_PUBLIC_CORE_API_URL \?\? process\.env\.CORE_API_BASE_URL \?\? DEFAULT_BASE_URL;/g, "const baseUrl = dashboardCoreApiBaseUrl();"]
];

for (const name of readdirSync(dir)) {
  if (!name.endsWith("-api.ts")) continue;
  const path = join(dir, name);
  let src = readFileSync(path, "utf8");
  if (!/NEXT_PUBLIC_CORE_API_URL|CORE_API_BASE_URL/.test(src)) continue;
  let next = src;
  for (const [re, rep] of patterns) {
    next = next.replace(re, rep);
  }
  if (next === src) continue;
  if (!next.includes("dashboardCoreApiBaseUrl")) {
    continue;
  }
  if (!/from\s+["']\.\/api-transport["']/.test(next)) {
    next = `import { dashboardCoreApiBaseUrl } from "./api-transport";\n${next}`;
  }
  writeFileSync(path, next);
  console.log("patched", path);
}
