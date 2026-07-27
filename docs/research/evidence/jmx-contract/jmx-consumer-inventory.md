# JMX consumer inventory (S-03)

This is a repository-grounded, preliminary E1 inventory. It is not the final
E7 disposition. `NO_NAMED_CONSUMER_FOUND` means that searches of production
Java, tests, Gradle fixtures, documentation, runbooks and performance sources
found no named caller; it does not prove that external JMX consumers do not
exist.

Consumer abbreviations:

- `CLIENT`: production `PlatformTracingJmxClient`
- `ACTUATOR`: `TracingActuatorEndpoint` or metrics/read-model projection
- `HEALTH`: `AgentExtensionObserver` / Spring health
- `TEST`: repository unit, smoke, E2E, benchmark or performance fixture
- `DOC`: current documentation/runbook reference

## Control

| Member | Kind | Known consumer / use case | Preliminary disposition |
|---|---|---|---|
| `applyPolicy(CompositeData)` | mutation operation | TEST, DOC; aggregate runtime-policy mutation; external/unknown possible | REWRITE_CANDIDATE |
| `readAppliedState()` | read operation | TEST, DOC; applied-state diagnostics; external/unknown possible | KEEP_CANDIDATE |

## Diagnostics

| Member | Kind | Known consumer / use case | Preliminary disposition |
|---|---|---|---|
| `ConfigAuditTrail` | read attribute | CLIENT, ACTUATOR; config audit diagnostics | KEEP_CANDIDATE |
| `ConfigReloadMetrics` | read attribute | CLIENT, ACTUATOR; reload metrics | KEEP_CANDIDATE |
| `InvalidConfigCount` | read attribute | CLIENT, ACTUATOR; rejected config diagnostics | KEEP_CANDIDATE |
| `PlatformLogLevel` | writable attribute | CLIENT; runtime log-level control and readback | REWRITE_CANDIDATE |
| `PropagationEnabled` | writable attribute | CLIENT; runtime propagation control and readback | REWRITE_CANDIDATE |
| `SafeWrapperMetrics` | read attribute | CLIENT, ACTUATOR; safe-wrapper metrics | KEEP_CANDIDATE |

## Export

| Member | Kind | Known consumer / use case | Preliminary disposition |
|---|---|---|---|
| `ExportDroppedAfterShutdownTotal` | read attribute | CLIENT, ACTUATOR; exporter diagnostics | KEEP_CANDIDATE |
| `ExportDroppedOverflowTotal` | read attribute | CLIENT, ACTUATOR, TEST; overflow diagnostics | KEEP_CANDIDATE |
| `ExportEnabled` | writable attribute | CLIENT; runtime export control and readback | REWRITE_CANDIDATE |
| `ExportFailuresTotal` | read attribute | CLIENT, ACTUATOR; exporter diagnostics | KEEP_CANDIDATE |
| `ExportQueueCapacity` | read attribute | CLIENT, ACTUATOR; queue utilization | KEEP_CANDIDATE |
| `ExportQueueSize` | read attribute | CLIENT, ACTUATOR; queue utilization | KEEP_CANDIDATE |
| `ExportTimeoutsTotal` | read attribute | CLIENT, ACTUATOR; exporter diagnostics | KEEP_CANDIDATE |
| `SafeExporterMetrics` | read attribute | CLIENT, ACTUATOR; safe exporter diagnostics | KEEP_CANDIDATE |

## Extension readiness

| Member | Kind | Known consumer / use case | Preliminary disposition |
|---|---|---|---|
| `Capabilities` | read attribute | HEALTH, TEST; capability handshake | KEEP_CANDIDATE |
| `ExportPathProtected` | read attribute | HEALTH, TEST; controlled Agent readiness | KEEP_CANDIDATE |
| `ExtensionVersion` | read attribute | HEALTH, TEST; compatibility diagnostics | KEEP_CANDIDATE |
| `FailureCode` | read attribute | HEALTH, TEST; fail-closed diagnostics | KEEP_CANDIDATE |
| `FailureMessage` | read attribute | HEALTH, TEST; fail-closed diagnostics | KEEP_CANDIDATE |
| `LifecycleState` | read attribute | HEALTH, TEST; extension lifecycle | KEEP_CANDIDATE |
| `Profile` | read attribute | HEALTH, TEST; capability-profile handshake | KEEP_CANDIDATE |
| `PropagationHooksInstalled` | read attribute | HEALTH, TEST; controlled Agent readiness | KEEP_CANDIDATE |
| `ProtocolVersion` | read attribute | HEALTH, TEST; handshake version | KEEP_CANDIDATE |
| `RequiredCapabilities` | read attribute | HEALTH, TEST; capability diagnostics | KEEP_CANDIDATE |
| `RequiredSpanProcessorsInstalled` | read attribute | HEALTH, TEST; controlled Agent readiness | KEEP_CANDIDATE |
| `SamplerInstalled` | read attribute | HEALTH, TEST; controlled Agent readiness | KEEP_CANDIDATE |
| `SanitizerInstalled` | read attribute | HEALTH, TEST; controlled Agent readiness | KEEP_CANDIDATE |

## Processor metrics

| Member | Kind | Known consumer / use case | Preliminary disposition |
|---|---|---|---|
| `ActiveSpanCount` | read attribute | TEST; watchdog diagnostics | UNKNOWN_PENDING_OWNER |
| `ActiveTraceCount` | read attribute | TEST; watchdog diagnostics | UNKNOWN_PENDING_OWNER |
| `DroppedAttributesTotal` | read attribute | NO_NAMED_CONSUMER_FOUND; external/unknown possible | UNKNOWN_PENDING_OWNER |
| `DroppedEventsTotal` | read attribute | NO_NAMED_CONSUMER_FOUND; external/unknown possible | UNKNOWN_PENDING_OWNER |
| `DroppedLinksTotal` | read attribute | NO_NAMED_CONSUMER_FOUND; external/unknown possible | UNKNOWN_PENDING_OWNER |
| `ForcedSpanCloses` | read attribute | TEST; watchdog diagnostics | UNKNOWN_PENDING_OWNER |
| `ForcedTraceCloses` | read attribute | TEST; watchdog diagnostics | UNKNOWN_PENDING_OWNER |
| `ProcessorErrorsByName` | read attribute | CLIENT, ACTUATOR; processor diagnostics | KEEP_CANDIDATE |
| `ProcessorErrorsTotal` | read attribute | CLIENT, ACTUATOR; processor diagnostics | KEEP_CANDIDATE |

## Sampling

| Member | Kind | Known consumer / use case | Preliminary disposition |
|---|---|---|---|
| `DropPathPrefixes` | writable attribute | CLIENT, ACTUATOR; policy readback/direct mutation | REWRITE_CANDIDATE |
| `ForceRecordValues` | writable attribute | CLIENT, ACTUATOR; policy readback/direct mutation | REWRITE_CANDIDATE |
| `RouteRatios` | writable attribute | CLIENT, ACTUATOR; policy readback/direct mutation | REWRITE_CANDIDATE |
| `SamplerDecisionCounts` | read attribute | CLIENT, ACTUATOR, TEST; sampling diagnostics | KEEP_CANDIDATE |
| `SamplerEnabled` | writable attribute | CLIENT, ACTUATOR; policy readback/direct mutation | REWRITE_CANDIDATE |
| `SamplingConfigLastUpdatedSource` | read attribute | CLIENT, ACTUATOR; provenance | KEEP_CANDIDATE |
| `SamplingConfigVersion` | read attribute | CLIENT, ACTUATOR, TEST; version/readback | KEEP_CANDIDATE |
| `SamplingRatio` | writable attribute | CLIENT, ACTUATOR; policy readback/direct mutation | REWRITE_CANDIDATE |
| `getSamplerDecisionCount(String,String)` | read operation | CLIENT; reason-specific diagnostics | KEEP_CANDIDATE |
| `resetSamplerCounters()` | mutation operation | NO_NAMED_CONSUMER_FOUND; external/unknown possible | UNKNOWN_PENDING_OWNER |
| `updateSamplingPolicy(boolean,double,Map,String[],String[])` | mutation operation | TEST, DOC; legacy aggregate mutation | REWRITE_CANDIDATE |
| `updateSamplingPolicy(boolean,double,String[],String[],String[],double[],String)` | mutation operation | CLIENT; runtime-config mutation | REWRITE_CANDIDATE |

## Scrubbing

| Member | Kind | Known consumer / use case | Preliminary disposition |
|---|---|---|---|
| `ScrubbingConfigLastUpdatedSource` | read attribute | CLIENT, ACTUATOR; provenance | KEEP_CANDIDATE |
| `ScrubbingConfigVersion` | read attribute | CLIENT, ACTUATOR; version/readback | KEEP_CANDIDATE |
| `ScrubbingEnabled` | read attribute | CLIENT, ACTUATOR; state readback | KEEP_CANDIDATE |
| `ScrubbingMetrics` | read attribute | CLIENT, ACTUATOR; scrubbing diagnostics | KEEP_CANDIDATE |
| `updateScrubbingPolicy(boolean,String[])` | mutation operation | TEST, DOC; legacy mutation | REWRITE_CANDIDATE |
| `updateScrubbingPolicy(boolean,String[],String)` | mutation operation | CLIENT; runtime-config mutation | REWRITE_CANDIDATE |

## Validation

| Member | Kind | Known consumer / use case | Preliminary disposition |
|---|---|---|---|
| `ValidationConfigLastUpdatedSource` | read attribute | CLIENT, ACTUATOR; provenance | KEEP_CANDIDATE |
| `ValidationConfigVersion` | read attribute | CLIENT, ACTUATOR; version/readback | KEEP_CANDIDATE |
| `ValidationEnabled` | read attribute | CLIENT, ACTUATOR; state readback | KEEP_CANDIDATE |
| `ValidationStrict` | read attribute | CLIENT, ACTUATOR; state readback | KEEP_CANDIDATE |
| `ValidationStrictRuntimeAllowed` | read attribute | CLIENT, ACTUATOR; mutation constraint | KEEP_CANDIDATE |
| `updateValidationPolicy(boolean,boolean)` | mutation operation | CLIENT, TEST; direct mutation | REWRITE_CANDIDATE |
| `updateValidationPolicy(boolean,boolean,String)` | mutation operation | CLIENT; runtime-config mutation | REWRITE_CANDIDATE |

## Search conclusion

The named repository consumers are concentrated in the Spring JMX client,
Actuator/metrics projection, readiness observer and test/performance fixtures.
No repository-owned Spring client exists for the Control MBean because
`PlatformTracingJmxObjectNames` duplicates only seven names. External JMX
consumers remain `external/unknown` until an owner survey is completed.
