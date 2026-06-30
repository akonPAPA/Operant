export type FrontendAuthority =
  | Readonly<{
      available: true;
      mode: "demo";
      tenantId: string;
    }>
  | Readonly<{
      available: false;
      mode: "unavailable";
      reason: string;
    }>;

export type FrontendAuthorityInput = {
  nodeEnv?: string;
  demoMode?: string;
  demoTenantId?: string;
};

export declare const FRONTEND_AUTHORITY_UNAVAILABLE_MESSAGE: string;

export function resolveFrontendAuthority(input?: FrontendAuthorityInput): FrontendAuthority;
export function frontendAuthority(): FrontendAuthority;
export function demoTenantId(): string;
export function hasDemoAuthority(): boolean;
export function requireDemoTenantId(): string;
export function missingFrontendAuthorityMessage(area: string): string;
