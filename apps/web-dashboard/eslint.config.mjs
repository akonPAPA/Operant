import nextCoreWebVitals from "eslint-config-next/core-web-vitals";

const config = [
  // Generated / ephemeral artifacts — never lint input.
  {
    ignores: [
      "**/.next-e2e-dev/",
      ".next-e2e-dev/**",
      "**/.next-e2e-demo/",
      ".next-e2e-demo/**",
      "**/.next-e2e-denied/",
      ".next-e2e-denied/**",
      "**/.next-e2e-unavailable/",
      ".next-e2e-unavailable/**",
      "**/playwright-report/",
      "playwright-report/**",
      "**/test-results/",
      "test-results/**"
    ]
  },
  ...(Array.isArray(nextCoreWebVitals) ? nextCoreWebVitals : [nextCoreWebVitals])
];

export default config;
