/**
 * F13 — the E2E harness runs `next dev` and the production standalone artifact at the same time.
 * They must never share a mutable `.next`: the dev runtime may only write to an isolated,
 * gitignored dist directory selected via ORDERPILOT_NEXT_DIST_DIR. The override is restricted to
 * a `.next-*` sibling name so it can never escape the app root or redirect a production build;
 * production builds (env unset) always use the default `.next`.
 */
const distDirOverride = process.env.ORDERPILOT_NEXT_DIST_DIR;
if (distDirOverride !== undefined && !/^\.next-[a-z0-9-]+$/.test(distDirOverride)) {
  throw new Error(
    `ORDERPILOT_NEXT_DIST_DIR must match ^\\.next-[a-z0-9-]+$ (got ${JSON.stringify(distDirOverride)})`
  );
}

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  distDir: distDirOverride ?? ".next"
};

export default nextConfig;
