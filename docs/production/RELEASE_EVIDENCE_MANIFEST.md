# Release Evidence Manifest

**Base anchor SHA:** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72` (`main`, PR #262)

| Evidence ID | Commit SHA | Type | Command / artifact | Result | Gates supported |
| --- | --- | --- | --- | --- | --- |
| EV-P1A-001 | `7f05a1751d04d22ef572d8d6aca0dcbdc457df72` (uncommitted P1-A working tree) | junit | `mvn -Dtest=ProductionConfigurationValidatorTest test` | BUILD SUCCESS; 10 tests, 0 failures (`apps/core-api/.logs/mvn-p1a-config.log`) | P1-GATE-01 partial |
| EV-P1A-002 | same working tree | junit | `mvn -Dtest=GatewayHeaderAuthProductionGuardTest test` | BUILD SUCCESS; 11 tests, 0 failures (same log) | P1-GATE-01 partial |
| EV-P1A-003 | same working tree | junit | `mvn -Dtest=ProductionAuthenticationReadinessGuardTest,ProductionIntakeSecurityGuardTest test` | BUILD SUCCESS; 7 tests, 0 failures (same log) | P1-GATE-01 partial |
| EV-P1A-004 | same working tree | junit | `mvn -Dtest=ProductionConfigurationValidatorTest,GatewayHeaderAuthProductionGuardTest,ProductionAuthenticationReadinessGuardTest,ProductionIntakeSecurityGuardTest,GatewayHeaderReplayProtectionTest,ApiSecurityWebConfigTest,ApiHeaderAuthenticationFilterDisabledModeTest,TrustedGatewaySignerVerifierCompatibilityTest test` | BUILD SUCCESS; 55 tests, 0 failures (`apps/core-api/.logs/mvn-p1a-broader.log`) | P1-GATE-01 partial (config/security regression) |

**Update policy:** After each bounded slice, append rows with the exact `git rev-parse HEAD` used for the test run. Do not mark PASS in matrices until logs show `BUILD SUCCESS`.
