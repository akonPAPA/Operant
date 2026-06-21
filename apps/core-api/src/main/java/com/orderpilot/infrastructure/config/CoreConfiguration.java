package com.orderpilot.infrastructure.config;

import com.orderpilot.application.services.connector.LocalDevelopmentSecretVaultService;
import com.orderpilot.application.services.connector.SecretVaultService;
import com.orderpilot.application.services.runtime.DefaultRuntimeUnitEstimator;
import com.orderpilot.application.services.runtime.InMemoryRateLimitStore;
import com.orderpilot.application.services.runtime.PermissiveRuntimeFeaturePolicy;
import com.orderpilot.application.services.runtime.PersistentRuntimeFeaturePolicy;
import com.orderpilot.application.services.runtime.RateLimitStore;
import com.orderpilot.application.services.runtime.RuntimeFeaturePolicy;
import com.orderpilot.application.services.runtime.RuntimeControlProperties;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimator;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConfigurationProperties(prefix = "orderpilot.runtime-control")
  RuntimeControlProperties runtimeControlProperties() {
    return new RuntimeControlProperties();
  }

  @Bean
  @ConditionalOnMissingBean(SecretVaultService.class)
  SecretVaultService secretVaultService(Clock clock) {
    return new LocalDevelopmentSecretVaultService(clock);
  }

  // OP-CAP-16C/16K: in-process rate-limit store — the default for single-node/dev/test. It is gated to
  // orderpilot.runtime.rate.store=in-memory (default when unset) so it is mutually exclusive with the
  // Redis store wired by RuntimeRateRedisConfiguration (store=redis); @ConditionalOnMissingBean still
  // lets a test substitute its own RateLimitStore.
  @Bean
  @ConditionalOnMissingBean(RateLimitStore.class)
  @ConditionalOnProperty(name = "orderpilot.runtime.rate.store", havingValue = "in-memory", matchIfMissing = true)
  RateLimitStore rateLimitStore() {
    return new InMemoryRateLimitStore();
  }

  // OP-CAP-16E: persistent, tenant-scoped feature entitlement policy backed by tenant_runtime_plan /
  // feature_entitlement. Tenants without any plan row keep the safe permissive compatibility default
  // (see PersistentRuntimeFeaturePolicy). A test or future bean of the same type overrides this via
  // @ConditionalOnMissingBean. (PermissiveRuntimeFeaturePolicy remains available as a manual fallback.)
  // Uses ObjectProvider so the bean also resolves in web/unit slices that do not load JPA
  // repositories: when the entitlement repositories are unavailable it falls back to the permissive
  // compatibility default (preserving 16D behavior); full application contexts get the persistent,
  // tenant-scoped policy.
  @Bean
  @ConditionalOnMissingBean(RuntimeFeaturePolicy.class)
  RuntimeFeaturePolicy runtimeFeaturePolicy(
      ObjectProvider<TenantRuntimePlanRepository> planRepositoryProvider,
      ObjectProvider<FeatureEntitlementRepository> entitlementRepositoryProvider,
      Clock clock) {
    TenantRuntimePlanRepository planRepository = planRepositoryProvider.getIfAvailable();
    FeatureEntitlementRepository entitlementRepository = entitlementRepositoryProvider.getIfAvailable();
    if (planRepository == null || entitlementRepository == null) {
      return new PermissiveRuntimeFeaturePolicy();
    }
    return new PersistentRuntimeFeaturePolicy(planRepository, entitlementRepository, clock);
  }

  // OP-CAP-16F: cheap, O(1) runtime unit estimator (no I/O, no parsing). A test or future bean of the
  // same type overrides this via @ConditionalOnMissingBean.
  @Bean
  @ConditionalOnMissingBean(RuntimeUnitEstimator.class)
  RuntimeUnitEstimator runtimeUnitEstimator() {
    return new DefaultRuntimeUnitEstimator();
  }
}
