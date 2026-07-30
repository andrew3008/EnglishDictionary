# S-04 N1-N12 Resolution

| ID | Result | Technical resolution |
|---|---|---|
|N1|PASS|P6.1 applied: resource attribute renamed to platform.tracing.resource.applied-policy-version; config key platform.tracing.resource.policy-version remains KEEP.|
|N2|PASS|P6.1 resolved: both Agent baggage families consolidate under platform.tracing.javaagent.propagation.baggage.*; enabled/deny-patterns shared; allowlist-keys (span attrs) and allowed-keys (W3C propagation) remain distinct leaves.|
|N3|PASS|P6.1 resolved: Spring force-record-header-values and Agent force-record-values merge to platform.tracing.javaagent.sampling.force-record-values (Category B startup-only; Decision 14).|
|N4|PASS|mode and route-ratios type drift confirmed and represented without String flattening.|
|N5|PASS|P6.1/P6.7 resolved: scrubbing built-in-rules/enabled owned solely by platform.tracing.javaagent.scrubbing.* (Category B; Decision 14); Spring binding deleted.|
|N6|PASS|Five Agent outbound readers have no callers; Spring equivalents remain active.|
|N7|PASS|P6.1 applied: w3c.diagnostics-only dead constant deleted (NO_READER).|
|N8|PASS|P6.1 applied: published contract corrected to platform.tracing.semantic.validation-mode; stale semconv name is DELETE.|
|N9|PASS|enabled, sdk.mode, three removed async aliases and service.k8s-pod-uid are fixture-only with no production reader.|
|N10|PASS|Stale sdk.mode documents are listed; SDK_MODE constant resolves to platform.tracing.mode.|
|N11|PASS|Actuator environment spelling is a relaxed-binding alias, not another property.|
|N12|PASS|PerfAdminController obsolete ObjectNames: NOT_FOUND; no production code changed.|
