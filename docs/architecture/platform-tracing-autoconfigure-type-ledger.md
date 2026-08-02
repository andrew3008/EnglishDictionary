# Autoconfigure Production Type Ledger

Baseline: `HEAD b4e4f5f`. Scope: 91 top-level production types originally owned by `platform-tracing-spring-boot-autoconfigure`, plus every created after-state type. Nested types follow their enclosing type.

| Original FQCN | Disposition | Final target | Slice | Status |
|---|---|---|---|---|
| `space.br1440.platform.tracing.autoconfigure.actuator.LegacyTracingDiagnosticsEndpoint` | DELETE | `-` | S3 | DONE |
| `space.br1440.platform.tracing.autoconfigure.actuator.OtelEffectiveConfigSnapshot` | DELETE | `-` | S6b→post-P6 | DONE (moved to `diagnostics.jmx` at S6b, deleted post-P6 per [ADR-remove-dual-channel-diagnostics.md](../decisions/ADR-remove-dual-channel-diagnostics.md)) |
| `space.br1440.platform.tracing.autoconfigure.actuator.ResourceEffectiveSnapshot` | DELETE | `-` | S6b→post-P6 | DONE (moved to `diagnostics.jmx` at S6b, deleted post-P6 per [ADR-remove-dual-channel-diagnostics.md](../decisions/ADR-remove-dual-channel-diagnostics.md)) |
| `space.br1440.platform.tracing.autoconfigure.aspect.TracedAspect` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.aop.TracedAspect` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.async.PlatformContextTaskDecorator` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.async.PlatformContextTaskDecorator` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.async.ThreadPoolTaskExecutorContextPropagationBeanPostProcessor` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.async.ThreadPoolTaskExecutorDecoratorBeanPostProcessor` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.async.TracingAsyncContextAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.async.TracingAsyncContextAutoConfiguration` | S6b | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames` | S5 | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings` | S5 | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsRegistrar` | S5 | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver` | S5 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.AcceptedPolicyLeaves` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedPolicyLeaves` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.AcceptedPolicyProjection` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedPolicyProjection` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.AcceptedSnapshotWithSequence` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedSnapshotWithSequence` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyReader` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedTracingPolicyReader` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader` | RENAME | `space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicyReader` | S1 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.AcceptedPolicyActuatorEndpoint` | DELETE | `-` | S3 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.ReplaceSamplingRouteRatiosRequest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.ReplaceSamplingRouteRatiosRequest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.SetDiagnosticsLogLevelRequest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.SetDiagnosticsLogLevelRequest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.SetExporterEnabledRequest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.SetExporterEnabledRequest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.SetSamplingEnabledRequest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.SetSamplingEnabledRequest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.SetSamplingRatioRequest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.SetSamplingRatioRequest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.SetTraceControlHeadersEnabledRequest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.SetTraceControlHeadersEnabledRequest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.SetValidationEnabledRequest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.SetValidationEnabledRequest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.SetValidationStrictRequest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.SetValidationStrictRequest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.SpringActuatorTracingPolicyAdapter` | RENAME | `space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyMutationService` | S4 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.TracingControlManagementContextConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.servlet.control.TracingControlManagementContextConfiguration` | S4 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.TracingControlMvcController` | MOVE | `space.br1440.platform.tracing.autoconfigure.servlet.control.TracingControlMvcController` | S4 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.actuator.TracingPolicyControlAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyControlAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.AgentReadinessView` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.AgentReadinessView` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.configserver.ConfigServerPolicyManifestBuilder` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerPolicyManifestBuilder` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.configserver.ConfigServerPolicyProvenanceClassifier` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerPolicyProvenanceClassifier` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.configserver.ConfigServerTracingPolicyAdapter` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingPolicyAdapter` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.configserver.ConfigServerTracingPolicyAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingPolicyAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.configserver.TracingPolicyRefreshCoalescer` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.configserver.TracingPolicyRefreshCoalescer` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.PlatformEnvironmentPosture` | MOVE | `space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture` | S5 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.PlatformEnvironmentPostureResolver` | DELETE | `-` | S5 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxControlAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxControlAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxTracingPolicySnapshotClient` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxTracingPolicySnapshotClient` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationAuthority` | MOVE | `space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority` | S5 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationAuthorityAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationAuthorityGuard` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityGuard` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationGuardException` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyContractViolationException` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyContractViolationException` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyDeliveryResult` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyManifestException` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyManifestException` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient` | RENAME | `space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicySnapshotApplier` | S1 | DONE |
| `space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySource` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicySource` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.diagnostics.SpanFactoryDiagnostics` | KEEP | `space.br1440.platform.tracing.autoconfigure.diagnostics.SpanFactoryDiagnostics` | UNCHANGED | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.diagnostics.TracingRuntimeDiagnosticsAutoConfiguration` | S6a | DONE |
| `space.br1440.platform.tracing.autoconfigure.errorhandling.RequestTraceContextSnapshotSupplier` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestTraceContextSnapshotSupplier` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.health.TracingHealthIndicator` | MOVE | `space.br1440.platform.tracing.autoconfigure.diagnostics.health.TracingHealthIndicator` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient` | MOVE | `space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxObjectNames` | MOVE | `space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.CandidateDigest` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.CandidateDigest` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec` | MOVE | `space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlOpenTypeCodec` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.kafka.KafkaBatchLinksAspect` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.kafka.KafkaBatchLinksAspect` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.kafka.KafkaRequestIdentityAspect` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.kafka.KafkaRequestIdentityAspect` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaOutboundAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaOutboundAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaProducerFactoryCustomizer` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaProducerFactoryCustomizer` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaProducerInterceptor` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaProducerInterceptor` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.metrics.MeteredSpanHandle` | KEEP | `space.br1440.platform.tracing.autoconfigure.metrics.MeteredSpanHandle` | UNCHANGED | DONE |
| `space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime` | KEEP | `space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime` | UNCHANGED | DONE |
| `space.br1440.platform.tracing.autoconfigure.metrics.MicrometerSemconvMetrics` | KEEP | `space.br1440.platform.tracing.autoconfigure.metrics.MicrometerSemconvMetrics` | UNCHANGED | DONE |
| `space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingConfigMetricsBinder` | KEEP | `space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingConfigMetricsBinder` | UNCHANGED | DONE |
| `space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics` | KEEP | `space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics` | UNCHANGED | DONE |
| `space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSafeWrapperMetricsBinder` | KEEP | `space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSafeWrapperMetricsBinder` | UNCHANGED | DONE |
| `space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSamplerMetricsBinder` | KEEP | `space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSamplerMetricsBinder` | UNCHANGED | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.propagation.PlatformContextPropagationAutoConfiguration` | S6a | DONE |
| `space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration` | KEEP | `space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration` | UNCHANGED | DONE |
| `space.br1440.platform.tracing.autoconfigure.RequestContextSupplierAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.SemanticLayerAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.semantic.SemanticLayerAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.servicename.PlatformLocalServiceNameProvider` | MOVE | `space.br1440.platform.tracing.autoconfigure.identity.PlatformLocalServiceNameProvider` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.servicename.PlatformRemoteServiceNameProvider` | MOVE | `space.br1440.platform.tracing.autoconfigure.identity.PlatformRemoteServiceNameProvider` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.ServiceNameProviderAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.identity.ServiceNameProviderAutoConfiguration` | S6b | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.status.PolicyAbsenceReason` | S3 | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.status.TracingStatusAssembler` | S3 | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration` | S3 | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.status.TracingStatusEndpoint` | S3 | DONE |
| `-` | CREATE | `space.br1440.platform.tracing.autoconfigure.status.TracingStatusSnapshot` | S3 | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.AgentExtensionDescriptor` | MOVE | `space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionDescriptor` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.AgentExtensionObserver` | MOVE | `space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionObserver` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.AgentRuntimeState` | MOVE | `space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.MicrometerTracingSuppressionResolver` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.observation.MicrometerTracingSuppressionResolver` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector` | MOVE | `space.br1440.platform.tracing.autoconfigure.agent.OtelAgentDetector` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.RemoteServiceMdcBoundarySupport` | MOVE | `space.br1440.platform.tracing.autoconfigure.identity.RemoteServiceMdcBoundarySupport` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.RequestIdBoundarySupport` | MOVE | `space.br1440.platform.tracing.autoconfigure.identity.RequestIdBoundarySupport` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport` | MOVE | `space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.SdkMode` | RENAME | `space.br1440.platform.tracing.autoconfigure.bootstrap.TracingMode` | S5 | DONE |
| `space.br1440.platform.tracing.autoconfigure.bootstrap.TracingMode` | DELETE | replaced by `platform.tracing.enabled` boolean on `TracingBootstrapSettings` | boolean-enabled | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics` | RENAME | `space.br1440.platform.tracing.autoconfigure.agent.TracingModeDiagnostics` | S5 | DONE |
| `space.br1440.platform.tracing.autoconfigure.agent.TracingModeDiagnostics` | RENAME | `space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics` | boolean-enabled | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.SdkModeResolver` | RENAME | `space.br1440.platform.tracing.autoconfigure.agent.TracingRuntimeStateResolver` | S6a | DONE |
| `space.br1440.platform.tracing.autoconfigure.agent.TracingRuntimeStateResolver` | RENAME | `space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeContractValidator` | boolean-enabled | DONE |
| — | ADD | `space.br1440.platform.tracing.autoconfigure.agent.AgentDeploymentExpectation` (internal cross-package; not application API/SPI; consumers: TracingRuntimeAutoConfiguration, AgentExtensionObserver, owning-module tests) | boolean-enabled | DONE |
| `space.br1440.platform.tracing.autoconfigure.support.SuppressMicrometerTracingCondition` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.observation.SuppressMicrometerTracingCondition` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.TracingAopAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration` | KEEP | `space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration` | UNCHANGED | DONE |
| `space.br1440.platform.tracing.autoconfigure.TracingDiagnosticsActuatorAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.diagnostics.TracingDiagnosticsActuatorAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.TracingObservationAutoConfiguration` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.observation.TracingObservationAutoConfiguration` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.TracingObservationSuppressStartupRunner` | MOVE | `space.br1440.platform.tracing.autoconfigure.integrations.observation.TracingObservationSuppressStartupRunner` | S6b | DONE |
| `space.br1440.platform.tracing.autoconfigure.TracingProperties` | KEEP | `space.br1440.platform.tracing.autoconfigure.TracingProperties` | UNCHANGED | DONE |

Summary: original=91; after-state=99; create=11; delete=3. The two Servlet management types deliberately move to `platform-tracing-autoconfigure-webmvc`.

