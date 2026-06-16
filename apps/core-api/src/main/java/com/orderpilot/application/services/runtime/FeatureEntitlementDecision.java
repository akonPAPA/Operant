package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16D Runtime Guard Live Wiring — outcome of a feature entitlement check.
 *
 * <p>Carries only stable tokens. It never exposes tenant plan internals — a denial reports the
 * feature and a stable reason code, nothing about pricing, plan name, or subscription state.
 *
 * @param available whether the feature is available to the tenant
 * @param featureType the feature checked
 * @param reasonCode stable reason token (see {@link RuntimeGuardReasonCodes})
 * @param httpStatusHint advisory HTTP status (200 available, 403 not available)
 */
public record FeatureEntitlementDecision(
    boolean available, RuntimeFeatureType featureType, String reasonCode, int httpStatusHint) {}
