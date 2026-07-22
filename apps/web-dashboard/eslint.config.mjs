import { globalIgnores } from "eslint/config";
import nextCoreWebVitals from "eslint-config-next/core-web-vitals";

const ephemeralIgnores = [
  ".next/**",
  ".next-e2e-dev/**",
  ".next-e2e-demo/**",
  ".next-e2e-denied/**",
  ".next-e2e-unavailable/**",
  ".next-e2e-no-review/**",
  ".next-e2e-quote-actor/**",
  ".next-e2e-quote-readonly/**",
  ".next-e2e-no-quotes/**",
  "playwright-report/**",
  "test-results/**"
];

const config = [
  // Generated / ephemeral artifacts — never lint input.
  globalIgnores(ephemeralIgnores),
  ...(Array.isArray(nextCoreWebVitals) ? nextCoreWebVitals : [nextCoreWebVitals]),
  // Re-assert after next config so generated trees cannot be re-included.
  globalIgnores(ephemeralIgnores)
];

export default config;
