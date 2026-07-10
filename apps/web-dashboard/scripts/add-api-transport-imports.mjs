import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const dir = "lib";
for (const name of readdirSync(dir)) {
  if (!name.endsWith("-api.ts")) continue;
  const path = join(dir, name);
  let src = readFileSync(path, "utf8");
  if (!src.includes("dashboardCoreApiBaseUrl")) continue;
  if (/from\s+["']\.\/api-transport["']/.test(src)) continue;
  writeFileSync(path, `import { dashboardCoreApiBaseUrl } from "./api-transport";\n${src}`);
  console.log("import added", path);
}

// draft-review-api patched manually pattern
const draft = join(dir, "draft-review-api.ts");
let draftSrc = readFileSync(draft, "utf8");
if (draftSrc.includes("dashboardCoreApiBaseUrl") && !/from\s+["']\.\/api-transport["']/.test(draftSrc)) {
  writeFileSync(draft, `import { dashboardCoreApiBaseUrl } from "./api-transport";\n${draftSrc}`);
  console.log("import added", draft);
}
