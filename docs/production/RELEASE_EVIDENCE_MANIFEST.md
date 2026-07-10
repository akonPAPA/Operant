# Release Evidence Manifest

**Base anchor SHA:** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72` (`main`, PR #262)  
**P1-A implementation SHA:** `53bdf708c9a437ea66fcd17f0be67bd2bf12a3de` (`feature/p1-a-production-truth-and-config`)

| Evidence ID | Commit SHA | Type | Command / artifact | Result | Gates supported |
| --- | --- | --- | --- | --- | --- |
| EV-P1A-001 | `53bdf708c9a437ea66fcd17f0be67bd2bf12a3de` | junit | `cd apps/core-api && mvn -Dtest=ProductionConfigurationValidatorTest,GatewayHeaderAuthProductionGuardTest,ProductionAuthenticationReadinessGuardTest,ProductionIntakeSecurityGuardTest test` | BUILD SUCCESS; **36** tests, 0 failures (`apps/core-api/.logs/mvn-p1a-config.log`) | P1-GATE-01 **PARTIAL / NOT_PASS** |
| EV-P1A-002 | `53bdf708c9a437ea66fcd17f0be67bd2bf12a3de` | junit | `cd apps/core-api && mvn -Dtest=ProductionConfigurationValidatorTest,GatewayHeaderAuthProductionGuardTest,ProductionAuthenticationReadinessGuardTest,ProductionIntakeSecurityGuardTest,GatewayHeaderReplayProtectionTest,ApiSecurityWebConfigTest,ApiHeaderAuthenticationFilterDisabledModeTest,TrustedGatewaySignerVerifierCompatibilityTest test` | BUILD SUCCESS; **63** tests, 0 failures (`apps/core-api/.logs/mvn-p1a-broader.log`) | P1-GATE-01 **PARTIAL / NOT_PASS** (config/security regression) |

## P1-GATE-01 status

| Status | **PARTIAL / NOT_PASS** |
| --- | --- |
| Proven at implementation SHA | Core API fail-closed startup validation for production-like Spring profiles; focused + broader security/configuration JUnit evidence above |
| Not proven | Next.js production environment validation; clean-host production startup with real deploy config; CI against this feature branch |
| Explicit non-claim | **PASS** not recorded for P1-GATE-01 |

**Update policy:** Append rows with exact `git rev-parse HEAD` used for each test run. Do not mark PASS without `BUILD SUCCESS` and gate-specific runtime proof.
