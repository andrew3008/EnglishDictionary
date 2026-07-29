# S-04 N1-N12 Resolution

| ID | Result | Technical resolution |
|---|---|---|
|N1|PASS|Resource attribute only; proposed rename is blocked by technical decision.|
|N2|PASS|Two active Agent families have different consumers; consolidate under one Agent owner.|
|N3|PASS|Spring/Agent spelling drift confirmed; target force-record-values remains security-gated.|
|N4|PASS|mode and route-ratios type drift confirmed and represented without String flattening.|
|N5|PASS|Spring and Agent built-in scrubbing defaults are equal; ownership remains security-gated.|
|N6|PASS|Five Agent outbound readers have no callers; Spring equivalents remain active.|
|N7|PASS|w3c.diagnostics-only has no reader and is proposed for deletion.|
|N8|PASS|Published semconv key conflicts with active semantic.validation-mode.|
|N9|PASS|enabled, sdk.mode, three removed async aliases and service.k8s-pod-uid are fixture-only with no production reader.|
|N10|PASS|Stale sdk.mode documents are listed; SDK_MODE constant resolves to platform.tracing.mode.|
|N11|PASS|Actuator environment spelling is a relaxed-binding alias, not another property.|
|N12|PASS|PerfAdminController obsolete ObjectNames: NOT_FOUND; no production code changed.|
