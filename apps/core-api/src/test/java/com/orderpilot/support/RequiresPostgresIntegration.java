package com.orderpilot.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("postgres-integration")
@EnabledIfSystemProperty(
    named = "orderpilot.postgres.integration.enabled",
    matches = "true",
    disabledReason = "Postgres integration tests require explicit local/CI Postgres opt-in")
public @interface RequiresPostgresIntegration {
}
