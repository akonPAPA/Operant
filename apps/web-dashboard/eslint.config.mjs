import nextCoreWebVitals from "eslint-config-next/core-web-vitals";

const config = [
  // F13: the isolated E2E dev dist dir is generated output, never lint input.
  { ignores: ["**/.next-e2e-dev/", ".next-e2e-dev/**"] },
  ...(Array.isArray(nextCoreWebVitals) ? nextCoreWebVitals : [nextCoreWebVitals])
];

export default config;
