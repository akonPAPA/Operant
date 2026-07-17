import "server-only";

import { loadValidatedOidcConfiguration } from "./bff-oidc-config.ts";
import { loadOidcProviderRuntime, type LoadOidcProviderRuntimeOptions } from "./bff-oidc-runtime.ts";
import { readConfiguredOidcIdentityMappingSource } from "./bff-oidc-identity-mapping.ts";
import { validateBffProductionConfig } from "./bff-proxy.ts";

export type OidcProductionReadinessResult =
  | Readonly<{ ok: true }>
  | Readonly<{ ok: false; code: "OIDC_CONFIG_INVALID" | "OIDC_PROVIDER_INVALID" | "OIDC_MAPPING_INVALID" | "BFF_CONFIG_INVALID" }>;

export async function validateOidcProductionReadiness(options: Pick<LoadOidcProviderRuntimeOptions, "fetch" | "cache" | "now" | "timeoutMs" | "maxBodyBytes"> = {}): Promise<OidcProductionReadinessResult> {
  const bffError = await validateBffProductionConfig();
  if (bffError) return { ok: false, code: "BFF_CONFIG_INVALID" };
  const configuration = loadValidatedOidcConfiguration();
  if (!configuration.ok) return { ok: false, code: "OIDC_CONFIG_INVALID" };
  const mapping = readConfiguredOidcIdentityMappingSource();
  if (!mapping.ok) return { ok: false, code: "OIDC_MAPPING_INVALID" };
  const provider = await loadOidcProviderRuntime(configuration.configuration, {
    ...options,
    fetch: options.fetch ?? ((input, init) => fetch(input, init))
  });
  if (!provider.ok) return { ok: false, code: "OIDC_PROVIDER_INVALID" };
  return { ok: true };
}
