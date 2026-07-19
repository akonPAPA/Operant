// WP2 — consolidated. EmptyState now lives in the shared page-state language (`page-states.tsx`)
// alongside LoadingState/ErrorState/UnavailableState/AccessDeniedState so there is a single source
// of truth. Re-exported here to preserve the existing import path.
export { EmptyState } from "./page-states.tsx";
