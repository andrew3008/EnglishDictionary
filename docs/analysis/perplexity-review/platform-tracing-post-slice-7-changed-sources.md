# PlatformTracing Post-Slice 7 Changed Sources

## How to read this file

This file contains full source code for Java classes/interfaces/enums/records changed or created during PlatformTracing refactoring through Slice 7.

**Bundle type:** best-effort source bundle without git diff (git metadata unavailable in workspace).

Use together with:
- platform-tracing-post-slice-7-file-index.md
- ../platform-tracing-post-slice-7-review-package.md
- ../platform-tracing-refactoring-plan.md
- ../../decisions/ADR-platform-tracing-micrometer-observation-boundary.md

Patch file platform-tracing-post-slice-7.patch was **not generated** — see file index for instructions.

## Metadata

- Generated: 2026-07-07 12:25:57 +03:00
- Base ref: n/a (git unavailable)
- Head ref: n/a (git unavailable)
- Branch: n/a (git unavailable)
- Source count: 90

## Table of contents

- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/DatabaseSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualdatabasespanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/DatabaseTracing.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualdatabasetracingjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/HttpClientSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualhttpclientspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/HttpServerSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualhttpserverspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/HttpTracing.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualhttptracingjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/KafkaBatchSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualkafkabatchspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/KafkaConsumerSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualkafkaconsumerspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/KafkaProducerSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualkafkaproducerspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/KafkaTracing.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualkafkatracingjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/ManualTracing.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualmanualtracingjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/OperationSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualoperationspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/PlatformSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualplatformspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/RpcClientSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualrpcclientspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/RpcServerSpanBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualrpcserverspanbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/RpcTracing.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualrpctracingjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/TraceContextView.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualtracecontextviewjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/TransportTracing.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapimanualtransporttracingjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/PlatformTracing.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapiplatformtracingjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/RemoteContext.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanremotecontextjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/DefaultSpanSpecBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecdefaultspanspecbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/ImmutableSpanOptions.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecimmutablespanoptionsjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanAttributeValue.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecspanattributevaluejava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanHandle.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecspanhandlejava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanOptions.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecspanoptionsjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpec.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecspanspecjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpecBuilder.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecspanspecbuilderjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpecImpl.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecspanspecimpljava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpecReason.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecspanspecreasonjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpecifiedSpan.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspecspecifiedspanjava)
- [platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/Topology.java](#platformtracingapi--platform-tracing-apisrcmainjavaspacebr1440platformtracingapispanspectopologyjava)
- [platform-tracing-api — platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/manual/arch/V3ManualApiArchTest.java](#platformtracingapi--platform-tracing-apisrctestjavaspacebr1440platformtracingapimanualarchv3manualapiarchtestjava)
- [platform-tracing-api — platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/span/RemoteContextTest.java](#platformtracingapi--platform-tracing-apisrctestjavaspacebr1440platformtracingapispanremotecontexttestjava)
- [platform-tracing-api — platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/span/spec/SpanSpecBuilderFinalStateTest.java](#platformtracingapi--platform-tracing-apisrctestjavaspacebr1440platformtracingapispanspecspanspecbuilderfinalstatetestjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/DefaultPlatformTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoredefaultplatformtracingjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/DefaultTracingImplementation.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimpldefaulttracingimplementationjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/DelegatingTracingImplementation.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimpldelegatingtracingimplementationjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/ImmutableTracingState.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimplimmutabletracingstatejava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/NoOpTracingImplementation.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimplnooptracingimplementationjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/OperationSpanSpecs.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimploperationspanspecsjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/SemanticSpanSpecs.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimplsemanticspanspecsjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/SpanAttributeValueConverter.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimplspanattributevalueconverterjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/TracingImplementation.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimpltracingimplementationjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/TracingMode.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimpltracingmodejava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/TracingState.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoreimpltracingstatejava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/AbstractSemanticSpanBuilder.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualabstractsemanticspanbuilderjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DatabaseSpanBuilderImpl.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualdatabasespanbuilderimpljava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultHttpTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualdefaulthttptracingjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultKafkaTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualdefaultkafkatracingjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultManualTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualdefaultmanualtracingjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultRpcTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualdefaultrpctracingjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultTraceContextView.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualdefaulttracecontextviewjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultTransportTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualdefaulttransporttracingjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/NoOpManualTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualnoopmanualtracingjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/NoOpSpanHandle.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualnoopspanhandlejava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/NoOpTraceContextView.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualnooptracecontextviewjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/OperationSpanBuilderImpl.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualoperationspanbuilderimpljava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/ScopedExecution.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualscopedexecutionjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/SpanHandleImpl.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualspanhandleimpljava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/SpecifiedSpanImpl.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualspecifiedspanimpljava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/StubTransportTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcoremanualstubtransporttracingjava)
- [platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/NoOpPlatformTracing.java](#platformtracingcore--platform-tracing-coresrcmainjavaspacebr1440platformtracingcorenoopplatformtracingjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/arch/TracingImplementationArchTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcorearchtracingimplementationarchtestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/impl/TracingImplementationRoutingTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoreimpltracingimplementationroutingtestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/DatabaseSpanBuilderTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualdatabasespanbuildertestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/HttpSpanBuilderTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualhttpspanbuildertestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/KafkaConsumerBatchLinksTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualkafkaconsumerbatchlinkstestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/KafkaSpanBuilderTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualkafkaspanbuildertestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/OperationSpanBuilderTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualoperationspanbuildertestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/RpcSpanBuilderTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualrpcspanbuildertestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/ScopedExecutionTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualscopedexecutiontestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/SpanOptionsTopologyTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualspanoptionstopologytestjava)
- [platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/SpecifiedSpanTest.java](#platformtracingcore--platform-tracing-coresrctestjavaspacebr1440platformtracingcoremanualspecifiedspantestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/actuator/TracingActuatorEndpoint.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfigureactuatortracingactuatorendpointjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/diagnostics/ManualTracingDiagnostics.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfigurediagnosticsmanualtracingdiagnosticsjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/diagnostics/TracingDiagnosticsMapper.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfigurediagnosticstracingdiagnosticsmapperjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/diagnostics/TracingDiagnosticsView.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfigurediagnosticstracingdiagnosticsviewjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredSpanHandle.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfiguremetricsmeteredspanhandlejava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredTracingImplementation.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfiguremetricsmeteredtracingimplementationjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingActuatorAutoConfiguration.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfiguretracingactuatorautoconfigurationjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingCoreAutoConfiguration.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfiguretracingcoreautoconfigurationjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingMetricsAutoConfiguration.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrcmainjavaspacebr1440platformtracingautoconfiguretracingmetricsautoconfigurationjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/actuator/TracingActuatorEndpointProcessorErrorsTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfigureactuatortracingactuatorendpointprocessorerrorstestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/actuator/TracingActuatorEndpointTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfigureactuatortracingactuatorendpointtestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/BeanTopologyTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfigurebeantopologytestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/diagnostics/DiagnosticsBoundaryTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfigurediagnosticsdiagnosticsboundarytestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/diagnostics/ObservationCoexistenceTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfigurediagnosticsobservationcoexistencetestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/diagnostics/SpringBootContextMatrixTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfigurediagnosticsspringbootcontextmatrixtestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/diagnostics/TracingDiagnosticsViewJsonContractTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfigurediagnosticstracingdiagnosticsviewjsoncontracttestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredMetricsCountTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfiguremetricsmeteredmetricscounttestjava)
- [platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredTopologyMatrixTest.java](#platformtracingspringbootautoconfigure--platform-tracing-spring-boot-autoconfiguresrctestjavaspacebr1440platformtracingautoconfiguremetricsmeteredtopologymatrixtestjava)

## Sources by module: platform-tracing-api


## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/DatabaseSpanBuilder.java

**Status:** ADDED  
**Main type:** `DatabaseSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Database builder API  
**Lines:** 18

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Database semantic builder under {@link TransportTracing#database()}.
 */
public interface DatabaseSpanBuilder extends PlatformSpanBuilder<DatabaseSpanBuilder> {

    @Nonnull
    DatabaseSpanBuilder system(@Nonnull String dbSystem);

    @Nonnull
    DatabaseSpanBuilder operation(@Nonnull String operation);

    @Nonnull
    DatabaseSpanBuilder collection(@Nonnull String collection);
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/DatabaseTracing.java

**Status:** ADDED  
**Main type:** `DatabaseTracing`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Database transport API  
**Lines:** 7

```java
package space.br1440.platform.tracing.api.manual;

/**
 * Database transport tracing entry (Slice 3B).
 */
public interface DatabaseTracing extends DatabaseSpanBuilder {
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/HttpClientSpanBuilder.java

**Status:** ADDED  
**Main type:** `HttpClientSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** HTTP client builder  
**Lines:** 21

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * HTTP client semantic builder under {@link HttpTracing#client()}.
 */
public interface HttpClientSpanBuilder extends PlatformSpanBuilder<HttpClientSpanBuilder> {

    @Nonnull
    HttpClientSpanBuilder method(@Nonnull String httpMethod);

    @Nonnull
    HttpClientSpanBuilder url(@Nonnull String rawUrl);

    @Nonnull
    HttpClientSpanBuilder statusCode(long statusCode);

    @Nonnull
    HttpClientSpanBuilder serverAddress(@Nonnull String address);
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/HttpServerSpanBuilder.java

**Status:** ADDED  
**Main type:** `HttpServerSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** HTTP server builder  
**Lines:** 18

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * HTTP server semantic builder under {@link HttpTracing#server()}.
 */
public interface HttpServerSpanBuilder extends PlatformSpanBuilder<HttpServerSpanBuilder> {

    @Nonnull
    HttpServerSpanBuilder method(@Nonnull String httpMethod);

    @Nonnull
    HttpServerSpanBuilder route(@Nonnull String route);

    @Nonnull
    HttpServerSpanBuilder statusCode(long statusCode);
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/HttpTracing.java

**Status:** ADDED  
**Main type:** `HttpTracing`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** HTTP transport API  
**Lines:** 15

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * HTTP transport tracing entry (Slice 3A).
 */
public interface HttpTracing {

    @Nonnull
    HttpServerSpanBuilder server();

    @Nonnull
    HttpClientSpanBuilder client();
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/KafkaBatchSpanBuilder.java

**Status:** ADDED  
**Main type:** `KafkaBatchSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Kafka batch builder  
**Lines:** 10

```java
package space.br1440.platform.tracing.api.manual;

/**
 * Kafka batch consumer span builder returned by {@link KafkaConsumerSpanBuilder#batch(String)}.
 * <p>
 * Batch processing spans should use {@link #root()} with pre-start {@link #linkedTo} or
 * {@link #fromRemoteContext} links to referenced message contexts.
 */
public interface KafkaBatchSpanBuilder extends PlatformSpanBuilder<KafkaBatchSpanBuilder> {
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/KafkaConsumerSpanBuilder.java

**Status:** ADDED  
**Main type:** `KafkaConsumerSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Kafka consumer builder  
**Lines:** 21

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Kafka consumer semantic builder under {@link KafkaTracing#consumer()}.
 */
public interface KafkaConsumerSpanBuilder extends PlatformSpanBuilder<KafkaConsumerSpanBuilder> {

    @Nonnull
    KafkaConsumerSpanBuilder destination(@Nonnull String topic);

    @Nonnull
    KafkaConsumerSpanBuilder operation(@Nonnull String operation);

    /**
     * Batch consumer entry point (ROOT+links semantics finalized in Slice 5B).
     */
    @Nonnull
    KafkaBatchSpanBuilder batch(@Nonnull String destination);
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/KafkaProducerSpanBuilder.java

**Status:** ADDED  
**Main type:** `KafkaProducerSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Kafka producer builder  
**Lines:** 15

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Kafka producer semantic builder under {@link KafkaTracing#producer()}.
 */
public interface KafkaProducerSpanBuilder extends PlatformSpanBuilder<KafkaProducerSpanBuilder> {

    @Nonnull
    KafkaProducerSpanBuilder destination(@Nonnull String topic);

    @Nonnull
    KafkaProducerSpanBuilder operation(@Nonnull String operation);
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/KafkaTracing.java

**Status:** ADDED  
**Main type:** `KafkaTracing`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Kafka transport API  
**Lines:** 17

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.KafkaSemconvVersion;

/**
 * Kafka transport tracing entry (Slice 3C).
 */
@KafkaSemconvVersion("1.28.0")
public interface KafkaTracing {

    @Nonnull
    KafkaProducerSpanBuilder producer();

    @Nonnull
    KafkaConsumerSpanBuilder consumer();
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/ManualTracing.java

**Status:** ADDED  
**Main type:** `ManualTracing`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Public manual entry  
**Lines:** 22

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpecifiedSpan;

/**
 * Entry point for platform-governed manual tracing ({@code PlatformTracing.manual()} in v3 cutover).
 */
public interface ManualTracing {

    @Nonnull
    OperationSpanBuilder operation(@Nonnull String name);

    @Nonnull
    TransportTracing transport();

    @Nonnull
    SpecifiedSpan spanFromSpec(@Nonnull SpanSpec spec);

}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/OperationSpanBuilder.java

**Status:** ADDED  
**Main type:** `OperationSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Operation builder API  
**Lines:** 7

```java
package space.br1440.platform.tracing.api.manual;

/**
 * Builder for application-level manual operation spans ({@code manual().operation(name)}).
 */
public interface OperationSpanBuilder extends PlatformSpanBuilder<OperationSpanBuilder> {
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/PlatformSpanBuilder.java

**Status:** ADDED  
**Main type:** `PlatformSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** SpanFromSpec entry  
**Lines:** 45

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.function.Supplier;

/**
 * Common scoped-execution contract for v3 manual semantic builders.
 * <p>
 * Distinct from legacy {@link space.br1440.platform.tracing.api.span.builder.PlatformSpanBuilder}
 * in {@code span.builder} (removed in Slice 1B).
 */
public interface PlatformSpanBuilder<B extends PlatformSpanBuilder<B>> {

    @Nonnull
    B child();

    @Nonnull
    B root();

    @Nonnull
    B detached();

    @Nonnull
    B linkedTo(@Nonnull SpanLinkContext... links);

    @Nonnull
    B fromRemoteContext(@Nonnull String... traceparents);

    @Nonnull
    SpanHandle start();

    void run(@Nonnull Runnable action);

    @Nonnull
    <T> T call(@Nonnull Supplier<T> supplier);

    @Nonnull
    <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception;

}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/RpcClientSpanBuilder.java

**Status:** ADDED  
**Main type:** `RpcClientSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** RPC client builder  
**Lines:** 21

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * RPC client semantic builder under {@link RpcTracing#client()}.
 */
public interface RpcClientSpanBuilder extends PlatformSpanBuilder<RpcClientSpanBuilder> {

    @Nonnull
    RpcClientSpanBuilder system(@Nonnull String rpcSystem);

    @Nonnull
    RpcClientSpanBuilder service(@Nonnull String service);

    @Nonnull
    RpcClientSpanBuilder method(@Nonnull String method);

    @Nonnull
    RpcClientSpanBuilder serverAddress(@Nonnull String address);
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/RpcServerSpanBuilder.java

**Status:** ADDED  
**Main type:** `RpcServerSpanBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** RPC server builder  
**Lines:** 18

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * RPC server semantic builder under {@link RpcTracing#server()}.
 */
public interface RpcServerSpanBuilder extends PlatformSpanBuilder<RpcServerSpanBuilder> {

    @Nonnull
    RpcServerSpanBuilder system(@Nonnull String rpcSystem);

    @Nonnull
    RpcServerSpanBuilder service(@Nonnull String service);

    @Nonnull
    RpcServerSpanBuilder method(@Nonnull String method);
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/RpcTracing.java

**Status:** ADDED  
**Main type:** `RpcTracing`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** RPC transport API  
**Lines:** 17

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.RpcSemconvVersion;

/**
 * RPC transport tracing entry (Slice 3C).
 */
@RpcSemconvVersion("1.28.0")
public interface RpcTracing {

    @Nonnull
    RpcServerSpanBuilder server();

    @Nonnull
    RpcClientSpanBuilder client();
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/TraceContextView.java

**Status:** ADDED  
**Main type:** `TraceContextView`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Read-only context view  
**Lines:** 22

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

import java.util.Optional;

/**
 * Read-only view of the active trace/span context for correlation, logging, and error models.
 * <p>
 * Does not expose OpenTelemetry {@code Context}, {@code Span}, or {@code SpanContext}.
 */
public interface TraceContextView {

    @Nonnull
    Optional<String> traceId();

    @Nonnull
    Optional<String> spanId();

    @Nonnull
    Optional<String> correlationId();
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/manual/TransportTracing.java

**Status:** ADDED  
**Main type:** `TransportTracing`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Transport grouping  
**Lines:** 22

```java
package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Semantic transport/protocol builders grouped under {@link ManualTracing#transport()}.
 */
public interface TransportTracing {

    @Nonnull
    HttpTracing http();

    @Nonnull
    DatabaseTracing database();

    @Nonnull
    RpcTracing rpc();

    @Nonnull
    KafkaTracing kafka();

}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/PlatformTracing.java

**Status:** MODIFIED  
**Main type:** `PlatformTracing`  
**Slice / area:** Slice 1B cutover  
**Why it matters for review:** Narrow v3 facade  
**Lines:** 23

```java
package space.br1440.platform.tracing.api;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;

/**
 * Narrow public facade for platform manual tracing (v3 cutover, Slice 1B).
 * <p>
 * Application code obtains read-only context via {@link #traceContext()} and creates governed
 * manual spans via {@link #manual()}. Implementation details live in {@code platform-tracing-core};
 * the bean is wired through {@code platform-tracing-spring-boot-autoconfigure}.
 */
public interface PlatformTracing {

    @Nonnull
    TraceContextView traceContext();

    @Nonnull
    ManualTracing manual();

}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/RemoteContext.java

**Status:** ADDED  
**Main type:** `RemoteContext`  
**Slice / area:** Slice 5B Links/Kafka batch  
**Why it matters for review:** Traceparent parsing  
**Lines:** 78

```java
package space.br1440.platform.tracing.api.span;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * W3C trace context parsing for pre-start span links.
 * <p>
 * Lenient {@link #parseTraceparent(String)} is intended for batch extraction loops where
 * missing or malformed headers are skipped. Strict {@link #requireTraceparent(String)} is
 * used by builder {@code fromRemoteContext(...)} and fails fast on invalid input.
 */
public final class RemoteContext {

    private static final String INVALID_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);

    private RemoteContext() {
    }

    /**
     * Parses a W3C {@code traceparent} header value into a link context, ignoring invalid input.
     */
    @Nonnull
    public static Optional<SpanLinkContext> parseTraceparent(@Nullable String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length < 4) {
            return Optional.empty();
        }
        String traceId = normalizeHex(parts[1], 32);
        String spanId = normalizeHex(parts[2], 16);
        if (traceId == null || spanId == null) {
            return Optional.empty();
        }
        if (INVALID_ID.equals(traceId) || INVALID_SPAN_ID.equals(spanId)) {
            return Optional.empty();
        }
        byte flags;
        try {
            flags = (byte) Integer.parseInt(parts[3], 16);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        return Optional.of(new SpanLinkContext(traceId, spanId, flags, null));
    }

    /**
     * Parses a W3C {@code traceparent} header value or throws if it cannot produce a valid link.
     */
    @Nonnull
    public static SpanLinkContext requireTraceparent(@Nonnull String traceparent) {
        Objects.requireNonNull(traceparent, "traceparent");
        return parseTraceparent(traceparent)
                .orElseThrow(() -> new IllegalArgumentException("invalid traceparent: " + traceparent));
    }

    @Nullable
    private static String normalizeHex(@Nonnull String raw, int expectedLength) {
        if (raw.length() != expectedLength) {
            return null;
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return null;
            }
        }
        return normalized;
    }
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/DefaultSpanSpecBuilder.java

**Status:** ADDED  
**Main type:** `DefaultSpanSpecBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** SpanSpec builder impl  
**Lines:** 197

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteContext;
import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class DefaultSpanSpecBuilder implements SpanSpecBuilder {

    private final String name;
    private SpanCategory category;
    private Topology topology = Topology.CHILD;
    private boolean topologyExplicit;
    private final List<SpanLinkContext> links = new ArrayList<>();
    private final Map<String, SpanAttributeValue> attributes = new LinkedHashMap<>();
    private SpanSpecReason reason;
    private String reference;
    private boolean reasonSet;
    private boolean referenceSet;

    DefaultSpanSpecBuilder(@Nonnull String name) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    @Override
    @Nonnull
    public SpanSpecBuilder category(@Nonnull SpanCategory category) {
        this.category = Objects.requireNonNull(category, "category");
        return this;
    }

    @Override
    @Nonnull
    public SpanSpecBuilder child() {
        return setTopology(Topology.CHILD);
    }

    @Override
    @Nonnull
    public SpanSpecBuilder root() {
        return setTopology(Topology.ROOT);
    }

    @Override
    @Nonnull
    public SpanSpecBuilder detached() {
        return setTopology(Topology.DETACHED);
    }

    @Override
    @Nonnull
    public SpanSpecBuilder linkedTo(@Nonnull SpanLinkContext... links) {
        Objects.requireNonNull(links, "links");
        for (SpanLinkContext link : links) {
            Objects.requireNonNull(link, "link");
            this.links.add(link);
        }
        return this;
    }

    @Override
    @Nonnull
    public SpanSpecBuilder fromRemoteContext(@Nonnull String... traceparents) {
        Objects.requireNonNull(traceparents, "traceparents");
        for (String traceparent : traceparents) {
            linkedTo(RemoteContext.requireTraceparent(Objects.requireNonNull(traceparent, "traceparent")));
        }
        return this;
    }

    @Override
    @Nonnull
    public SpanSpecBuilder attribute(@Nonnull String key, @Nonnull String value) {
        return putAttribute(key, SpanAttributeValue.of(value));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder attribute(@Nonnull String key, long value) {
        return putAttribute(key, SpanAttributeValue.of(value));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder attribute(@Nonnull String key, double value) {
        return putAttribute(key, SpanAttributeValue.of(value));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder attribute(@Nonnull String key, boolean value) {
        return putAttribute(key, SpanAttributeValue.of(value));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder stringListAttribute(@Nonnull String key, @Nonnull List<String> values) {
        return putAttribute(key, SpanAttributeValue.stringList(values));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder longListAttribute(@Nonnull String key, @Nonnull List<Long> values) {
        return putAttribute(key, SpanAttributeValue.longList(values));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder doubleListAttribute(@Nonnull String key, @Nonnull List<Double> values) {
        return putAttribute(key, SpanAttributeValue.doubleList(values));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder booleanListAttribute(@Nonnull String key, @Nonnull List<Boolean> values) {
        return putAttribute(key, SpanAttributeValue.booleanList(values));
    }

    @Override
    @Nonnull
    public SpanSpecBuilder reason(@Nonnull SpanSpecReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (reasonSet) {
            throw new IllegalStateException("reason(...) already set");
        }
        this.reason = reason;
        reasonSet = true;
        return this;
    }

    @Override
    @Nonnull
    public SpanSpecBuilder reference(@Nonnull String reference) {
        Objects.requireNonNull(reference, "reference");
        if (referenceSet) {
            throw new IllegalStateException("reference(...) already set");
        }
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        this.reference = reference;
        referenceSet = true;
        return this;
    }

    @Override
    @Nonnull
    public SpanSpec build() {
        if (category == null) {
            throw new IllegalStateException("category(...) is required");
        }
        if (reason == null) {
            throw new IllegalStateException("reason(...) is required");
        }
        if (reason == SpanSpecReason.TEMPORARY_WORKAROUND && reference == null) {
            throw new IllegalStateException("TEMPORARY_WORKAROUND requires reference(...)");
        }
        SpanOptions options = ImmutableSpanOptions.of(topology, List.copyOf(links));
        ImmutableSpanOptions.validateTopologyLinks(topology, links);
        return new SpanSpecImpl(name, category, options, attributes, reason,
                Optional.ofNullable(reference));
    }

    private SpanSpecBuilder setTopology(@Nonnull Topology topology) {
        Objects.requireNonNull(topology, "topology");
        if (topologyExplicit) {
            throw new IllegalStateException("topology already set; first topology setter wins");
        }
        this.topology = topology;
        topologyExplicit = true;
        return this;
    }

    private SpanSpecBuilder putAttribute(@Nonnull String key, @Nonnull SpanAttributeValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.isBlank()) {
            throw new IllegalArgumentException("attribute key must not be blank");
        }
        if (attributes.containsKey(key)) {
            throw new IllegalStateException("duplicate attribute key: " + key);
        }
        attributes.put(key, value);
        return this;
    }
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/ImmutableSpanOptions.java

**Status:** ADDED  
**Main type:** `ImmutableSpanOptions`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Immutable options  
**Lines:** 58

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

final class ImmutableSpanOptions implements SpanOptions {

    private final Topology topology;
    private final List<SpanLinkContext> links;

    private ImmutableSpanOptions(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        this.topology = topology;
        this.links = links;
    }

    static SpanOptions child() {
        return new ImmutableSpanOptions(Topology.CHILD, List.of());
    }

    static SpanOptions root() {
        return new ImmutableSpanOptions(Topology.ROOT, List.of());
    }

    static SpanOptions detached() {
        return new ImmutableSpanOptions(Topology.DETACHED, List.of());
    }

    static SpanOptions of(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        return new ImmutableSpanOptions(topology, List.copyOf(links));
    }

    @Override
    @Nonnull
    public Topology topology() {
        return topology;
    }

    @Override
    @Nonnull
    public List<SpanLinkContext> links() {
        return links;
    }

    static void validateTopologyLinks(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        if (links.isEmpty()) {
            return;
        }
        if (topology == Topology.DETACHED) {
            throw new IllegalStateException("DETACHED topology forbids pre-start links");
        }
        if (topology == Topology.CHILD) {
            throw new IllegalStateException("CHILD topology forbids pre-start links in v1");
        }
    }
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanAttributeValue.java

**Status:** ADDED  
**Main type:** `SpanAttributeValue`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Typed attribute values  
**Lines:** 158

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.Objects;

/**
 * Whitelist attribute value for {@link SpanSpec}.
 * <p>
 * Supports only OpenTelemetry-compatible scalar and homogeneous list types.
 */
public sealed interface SpanAttributeValue permits
        SpanAttributeValue.StringValue,
        SpanAttributeValue.LongValue,
        SpanAttributeValue.DoubleValue,
        SpanAttributeValue.BooleanValue,
        SpanAttributeValue.StringListValue,
        SpanAttributeValue.LongListValue,
        SpanAttributeValue.DoubleListValue,
        SpanAttributeValue.BooleanListValue {

    @Nonnull
    static SpanAttributeValue of(@Nonnull String value) {
        Objects.requireNonNull(value, "value");
        return new StringValue(value);
    }

    @Nonnull
    static SpanAttributeValue of(long value) {
        return new LongValue(value);
    }

    @Nonnull
    static SpanAttributeValue of(double value) {
        return new DoubleValue(value);
    }

    @Nonnull
    static SpanAttributeValue of(boolean value) {
        return new BooleanValue(value);
    }

    @Nonnull
    static SpanAttributeValue stringList(@Nonnull List<String> values) {
        return new StringListValue(copyStringList(values));
    }

    @Nonnull
    static SpanAttributeValue longList(@Nonnull List<Long> values) {
        return new LongListValue(copyLongList(values));
    }

    @Nonnull
    static SpanAttributeValue doubleList(@Nonnull List<Double> values) {
        return new DoubleListValue(copyDoubleList(values));
    }

    @Nonnull
    static SpanAttributeValue booleanList(@Nonnull List<Boolean> values) {
        return new BooleanListValue(copyBooleanList(values));
    }

    record StringValue(@Nonnull String value) implements SpanAttributeValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record LongValue(long value) implements SpanAttributeValue {
    }

    record DoubleValue(double value) implements SpanAttributeValue {
    }

    record BooleanValue(boolean value) implements SpanAttributeValue {
    }

    record StringListValue(@Nonnull List<String> values) implements SpanAttributeValue {
        public StringListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<String> values() {
            return List.copyOf(values);
        }
    }

    record LongListValue(@Nonnull List<Long> values) implements SpanAttributeValue {
        public LongListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<Long> values() {
            return List.copyOf(values);
        }
    }

    record DoubleListValue(@Nonnull List<Double> values) implements SpanAttributeValue {
        public DoubleListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<Double> values() {
            return List.copyOf(values);
        }
    }

    record BooleanListValue(@Nonnull List<Boolean> values) implements SpanAttributeValue {
        public BooleanListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<Boolean> values() {
            return List.copyOf(values);
        }
    }

    private static List<String> copyStringList(@Nonnull List<String> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().map(v -> {
            Objects.requireNonNull(v, "list element");
            return v;
        }).toList());
    }

    private static List<Long> copyLongList(@Nonnull List<Long> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().map(v -> {
            Objects.requireNonNull(v, "list element");
            return v;
        }).toList());
    }

    private static List<Double> copyDoubleList(@Nonnull List<Double> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().map(v -> {
            Objects.requireNonNull(v, "list element");
            return v;
        }).toList());
    }

    private static List<Boolean> copyBooleanList(@Nonnull List<Boolean> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().map(v -> {
            Objects.requireNonNull(v, "list element");
            return v;
        }).toList());
    }
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanHandle.java

**Status:** ADDED  
**Main type:** `SpanHandle`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Span handle API  
**Lines:** 18

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Minimal lifecycle handle for a started manual span (v3 API).
 * <p>
 * Distinct from legacy {@link space.br1440.platform.tracing.api.span.SpanScope}.
 */
public interface SpanHandle extends AutoCloseable {

    void recordException(@Nullable Throwable throwable);

    @Override
    void close();

}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanOptions.java

**Status:** MODIFIED  
**Main type:** `SpanOptions`  
**Slice / area:** Slice 5A Topology  
**Why it matters for review:** Topology+links validation  
**Lines:** 43

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

/**
 * Immutable public value model for span topology and pre-start links.
 * <p>
 * Not the preferred application-facing builder grammar; use {@link SpanSpecBuilder} convenience methods.
 */
public interface SpanOptions {

    @Nonnull
    Topology topology();

    @Nonnull
    List<SpanLinkContext> links();

    @Nonnull
    static SpanOptions child() {
        return ImmutableSpanOptions.child();
    }

    @Nonnull
    static SpanOptions root() {
        return ImmutableSpanOptions.root();
    }

    @Nonnull
    static SpanOptions detached() {
        return ImmutableSpanOptions.detached();
    }

    /**
     * Runtime/build-time guard for topology + pre-start link policy (Slice 1A / 5A).
     */
    static void validateTopologyLinks(@Nonnull Topology topology, @Nonnull List<SpanLinkContext> links) {
        ImmutableSpanOptions.validateTopologyLinks(topology, links);
    }
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpec.java

**Status:** ADDED  
**Main type:** `SpanSpec`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Span specification model  
**Lines:** 37

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.Map;
import java.util.Optional;

/**
 * Immutable governed span specification for {@code manual().spanFromSpec(spec)}.
 */
public interface SpanSpec {

    @Nonnull
    static SpanSpecBuilder builder(@Nonnull String name) {
        return new DefaultSpanSpecBuilder(name);
    }

    @Nonnull
    String name();

    @Nonnull
    SpanCategory category();

    @Nonnull
    SpanOptions options();

    @Nonnull
    Map<String, SpanAttributeValue> attributes();

    @Nonnull
    SpanSpecReason reason();

    @Nonnull
    Optional<String> reference();
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpecBuilder.java

**Status:** ADDED  
**Main type:** `SpanSpecBuilder`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** SpanSpec builder API  
**Lines:** 65

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

/**
 * Fluent builder for immutable {@link SpanSpec} values.
 */
public interface SpanSpecBuilder {

    @Nonnull
    SpanSpecBuilder category(@Nonnull SpanCategory category);

    @Nonnull
    SpanSpecBuilder child();

    @Nonnull
    SpanSpecBuilder root();

    @Nonnull
    SpanSpecBuilder detached();

    @Nonnull
    SpanSpecBuilder linkedTo(@Nonnull SpanLinkContext... links);

    @Nonnull
    SpanSpecBuilder fromRemoteContext(@Nonnull String... traceparents);

    @Nonnull
    SpanSpecBuilder attribute(@Nonnull String key, @Nonnull String value);

    @Nonnull
    SpanSpecBuilder attribute(@Nonnull String key, long value);

    @Nonnull
    SpanSpecBuilder attribute(@Nonnull String key, double value);

    @Nonnull
    SpanSpecBuilder attribute(@Nonnull String key, boolean value);

    @Nonnull
    SpanSpecBuilder stringListAttribute(@Nonnull String key, @Nonnull List<String> values);

    @Nonnull
    SpanSpecBuilder longListAttribute(@Nonnull String key, @Nonnull List<Long> values);

    @Nonnull
    SpanSpecBuilder doubleListAttribute(@Nonnull String key, @Nonnull List<Double> values);

    @Nonnull
    SpanSpecBuilder booleanListAttribute(@Nonnull String key, @Nonnull List<Boolean> values);

    @Nonnull
    SpanSpecBuilder reason(@Nonnull SpanSpecReason reason);

    @Nonnull
    SpanSpecBuilder reference(@Nonnull String reference);

    @Nonnull
    SpanSpec build();
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpecImpl.java

**Status:** ADDED  
**Main type:** `SpanSpecImpl`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** SpanSpec impl  
**Lines:** 72

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class SpanSpecImpl implements SpanSpec {

    private final String name;
    private final SpanCategory category;
    private final SpanOptions options;
    private final Map<String, SpanAttributeValue> attributes;
    private final SpanSpecReason reason;
    private final Optional<String> reference;

    SpanSpecImpl(@Nonnull String name,
                 @Nonnull SpanCategory category,
                 @Nonnull SpanOptions options,
                 @Nonnull Map<String, SpanAttributeValue> attributes,
                 @Nonnull SpanSpecReason reason,
                 @Nonnull Optional<String> reference) {
        this.name = Objects.requireNonNull(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.options = Objects.requireNonNull(options, "options");
        this.attributes = Map.copyOf(attributes);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.reference = Objects.requireNonNull(reference, "reference");
    }

    @Override
    @Nonnull
    public String name() {
        return name;
    }

    @Override
    @Nonnull
    public SpanCategory category() {
        return category;
    }

    @Override
    @Nonnull
    public SpanOptions options() {
        return options;
    }

    @Override
    @Nonnull
    public Map<String, SpanAttributeValue> attributes() {
        return attributes;
    }

    @Override
    @Nonnull
    public SpanSpecReason reason() {
        return reason;
    }

    @Override
    @Nonnull
    public Optional<String> reference() {
        return reference;
    }
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpanSpecReason.java

**Status:** ADDED  
**Main type:** `SpanSpecReason`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Governance reason enum  
**Lines:** 14

```java
package space.br1440.platform.tracing.api.span.spec;

/**
 * Mandatory governance reason for governed {@code spanFromSpec} usage via {@link SpanSpec}.
 * <p>
 * Generic catch-all values ({@code OTHER}, {@code UNKNOWN}, {@code CUSTOM}, {@code MISC}) are forbidden.
 */
public enum SpanSpecReason {
    UNSUPPORTED_PROTOCOL,
    UNSUPPORTED_LIBRARY,
    LEGACY_INTEGRATION,
    PLATFORM_EDGE_CASE,
    TEMPORARY_WORKAROUND
}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/SpecifiedSpan.java

**Status:** ADDED  
**Main type:** `SpecifiedSpan`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Scoped span API  
**Lines:** 25

```java
package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.function.Supplier;

/**
 * Immutable terminal surface returned by {@link space.br1440.platform.tracing.api.manual.ManualTracing#spanFromSpec(SpanSpec)}.
 */
public interface SpecifiedSpan {

    @Nonnull
    SpanHandle start();

    void run(@Nonnull Runnable action);

    @Nonnull
    <T> T call(@Nonnull Supplier<T> supplier);

    @Nonnull
    <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception;

}
```

## platform-tracing-api — platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/span/spec/Topology.java

**Status:** ADDED  
**Main type:** `Topology`  
**Slice / area:** Slice 1A API skeleton  
**Why it matters for review:** Replaces SpanRelation  
**Lines:** 10

```java
package space.br1440.platform.tracing.api.span.spec;

/**
 * Span topology relative to the active context.
 */
public enum Topology {
    CHILD,
    ROOT,
    DETACHED
}
```

## platform-tracing-api — platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/manual/arch/V3ManualApiArchTest.java

**Status:** ADDED  
**Main type:** `V3ManualApiArchTest`  
**Slice / area:** Tests  
**Why it matters for review:** Public API arch gate  
**Lines:** 184

```java
package space.br1440.platform.tracing.api.manual.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slice 1A architecture guardrails for additive v3 manual/spec public API.
 */
@AnalyzeClasses(
        packages = {
                "space.br1440.platform.tracing.api.manual",
                "space.br1440.platform.tracing.api.span.spec"
        },
        importOptions = ImportOption.DoNotIncludeTests.class
)
class V3ManualApiArchTest {

    private static final Set<String> ALLOWED_STATIC_FACTORY_TYPES = Set.of(
            "SpanOptions",
            "SpanSpec",
            "SpanAttributeValue"
    );

    private static final Set<String> FORBIDDEN_PUBLIC_NAMES = Set.of(
            "current",
            "currentTraceContext",
            "businessSpan",
            "internalSpan",
            "advanced",
            "escapeHatch",
            "customSpan",
            "rawSpan",
            "raw",
            "justification",
            "execute",
            "manualInstrumentation",
            "instrumented",
            "spans"
    );

    @ArchTest
    static final ArchRule publicFacadesAndBuildersHaveNoDefaultMethods =
            classes()
                    .that().areInterfaces()
                    .should(notHaveDefaultMethods())
                    .because("v3 public facades and builders must not use behavioral default methods");

    @ArchTest
    static final ArchRule noBehavioralStaticHelpersOnFacades =
            classes()
                    .that().areInterfaces()
                    .should(notDeclareBehavioralStaticMethodsExceptAllowedFactories());

    @ArchTest
    static final ArchRule noOpenTelemetryTypesInV3PublicApi =
            noClasses()
                    .that().resideInAnyPackage(
                            "space.br1440.platform.tracing.api.manual..",
                            "space.br1440.platform.tracing.api.span.spec..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api..",
                            "io.opentelemetry.context..",
                            "io.opentelemetry.sdk..")
                    .because("v3 public API must not expose or depend on OpenTelemetry types");

    @ArchTest
    static final ArchRule noAbstractClassesInV3ApiPackages =
            classes()
                    .that().resideInAnyPackage(
                            "space.br1440.platform.tracing.api.manual..",
                            "space.br1440.platform.tracing.api.span.spec..")
                    .should(notBeAbstractClass())
                    .because("abstract skeleton implementations are forbidden in v3 API packages");

    @ArchTest
    static final ArchRule forbiddenStalePublicNamesAbsent =
            classes()
                    .that().resideInAnyPackage(
                            "space.br1440.platform.tracing.api.manual..",
                            "space.br1440.platform.tracing.api.span.spec..")
                    .should(notDeclareForbiddenStaleNames());

    private static ArchCondition<JavaClass> notBeAbstractClass() {
        return new ArchCondition<>("not be abstract") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.isInterface()) {
                    return;
                }
                if (item.getModifiers().contains(JavaModifier.ABSTRACT)) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            "Abstract class " + item.getFullName() + " is forbidden"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notHaveDefaultMethods() {
        return new ArchCondition<>("not declare default methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!item.isInterface()) {
                    return;
                }
                for (JavaMethod method : item.getMethods()) {
                    if (isDefaultInterfaceMethod(method)) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                "Default method " + method.getFullName() + " is forbidden"));
                    }
                }
            }
        };
    }

    private static boolean isDefaultInterfaceMethod(JavaMethod method) {
        return method.getOwner().isInterface()
                && !method.getModifiers().contains(JavaModifier.ABSTRACT)
                && !method.getModifiers().contains(JavaModifier.STATIC)
                && !method.getModifiers().contains(JavaModifier.PRIVATE);
    }

    private static ArchCondition<JavaClass> notDeclareBehavioralStaticMethodsExceptAllowedFactories() {
        return new ArchCondition<>("not declare behavioral static helpers except value/spec factories") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (ALLOWED_STATIC_FACTORY_TYPES.contains(item.getSimpleName())) {
                    return;
                }
                for (JavaMethod method : item.getMethods()) {
                    if (!method.getModifiers().contains(JavaModifier.STATIC)) {
                        continue;
                    }
                    if (method.getModifiers().contains(JavaModifier.PRIVATE)
                            || method.getModifiers().contains(JavaModifier.PROTECTED)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(
                            method,
                            "Static method " + method.getFullName()
                                    + " is forbidden on public facade/builder types"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDeclareForbiddenStaleNames() {
        return new ArchCondition<>("not declare forbidden stale public names") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    if (FORBIDDEN_PUBLIC_NAMES.contains(method.getName())) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                "Forbidden stale public name: " + method.getName()));
                    }
                }
                if (FORBIDDEN_PUBLIC_NAMES.contains(item.getSimpleName())
                        || "CurrentTraceContext".equals(item.getSimpleName())
                        || "AdvancedTracing".equals(item.getSimpleName())
                        || "ManualInstrumentation".equals(item.getSimpleName())
                        || "InstrumentedTracing".equals(item.getSimpleName())) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            "Forbidden stale public type name: " + item.getSimpleName()));
                }
            }
        };
    }
}
```

## platform-tracing-api — platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/span/RemoteContextTest.java

**Status:** ADDED  
**Main type:** `RemoteContextTest`  
**Slice / area:** Slice 5B Links/Kafka batch  
**Why it matters for review:** Traceparent parsing tests  
**Lines:** 60

```java
package space.br1440.platform.tracing.api.span;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteContextTest {

    private static final String VALID_TRACEPARENT =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";

    @Test
    void parseTraceparent_validValue_returnsSampledLink() {
        Optional<SpanLinkContext> parsed = RemoteContext.parseTraceparent(VALID_TRACEPARENT);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().traceId()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
        assertThat(parsed.get().spanId()).isEqualTo("0102030405060708");
        assertThat(parsed.get().traceFlags()).isEqualTo((byte) 0x01);
        assertThat(parsed.get().traceState()).isNull();
    }

    @Test
    void parseTraceparent_uppercaseHex_isNormalized() {
        Optional<SpanLinkContext> parsed = RemoteContext.parseTraceparent(
                "00-0102030405060708090A0B0C0D0E0F10-0102030405060708-01");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().traceId()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
    }

    @Test
    void parseTraceparent_invalidValues_areIgnored() {
        assertThat(RemoteContext.parseTraceparent(null)).isEmpty();
        assertThat(RemoteContext.parseTraceparent("")).isEmpty();
        assertThat(RemoteContext.parseTraceparent("not-a-traceparent")).isEmpty();
        assertThat(RemoteContext.parseTraceparent(
                "00-00000000000000000000000000000000-0000000000000000-00")).isEmpty();
        assertThat(RemoteContext.parseTraceparent(
                "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-zz")).isEmpty();
    }

    @Test
    void requireTraceparent_validValue_returnsLink() {
        SpanLinkContext link = RemoteContext.requireTraceparent(VALID_TRACEPARENT);

        assertThat(link.traceId()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
        assertThat(link.spanId()).isEqualTo("0102030405060708");
    }

    @Test
    void requireTraceparent_invalidValue_throws() {
        assertThatThrownBy(() -> RemoteContext.requireTraceparent("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid traceparent");
    }
}
```

## platform-tracing-api — platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/span/spec/SpanSpecBuilderFinalStateTest.java

**Status:** ADDED  
**Main type:** `SpanSpecBuilderFinalStateTest`  
**Slice / area:** Tests  
**Why it matters for review:** Builder final-state gate  
**Lines:** 176

```java
package space.br1440.platform.tracing.api.span.spec;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanSpecBuilderFinalStateTest {

    private static final SpanLinkContext LINK =
            SpanLinkContext.sampled("01234567890123456789012345678901", "0123456789012345");

    private SpanSpecBuilder base() {
        return SpanSpec.builder("test-span")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE);
    }

    @Test
    void rootThenLinkedTo_isValid() {
        SpanSpec spec = base().root().linkedTo(LINK).build();

        assertThat(spec.options().topology()).isEqualTo(Topology.ROOT);
        assertThat(spec.options().links()).containsExactly(LINK);
    }

    @Test
    void linkedToThenRoot_isValid() {
        SpanSpec spec = base().linkedTo(LINK).root().build();

        assertThat(spec.options().topology()).isEqualTo(Topology.ROOT);
        assertThat(spec.options().links()).containsExactly(LINK);
    }

    @Test
    void detachedThenLinkedTo_isInvalid() {
        assertThatThrownBy(() -> base().detached().linkedTo(LINK).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void linkedToThenDetached_isInvalid() {
        assertThatThrownBy(() -> base().linkedTo(LINK).detached().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void childThenLinkedTo_isInvalid() {
        assertThatThrownBy(() -> base().child().linkedTo(LINK).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    @Test
    void linkedToThenChild_isInvalid() {
        assertThatThrownBy(() -> base().linkedTo(LINK).child().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    @Test
    void childThenRoot_isInvalid() {
        assertThatThrownBy(() -> base().child().root().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topology already set");
    }

    @Test
    void rootThenDetached_isInvalid() {
        assertThatThrownBy(() -> base().root().detached().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topology already set");
    }

    @Test
    void repeatedReason_isInvalid() {
        assertThatThrownBy(() -> base()
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void repeatedReference_isInvalid() {
        assertThatThrownBy(() -> base()
                .reference("JIRA-1")
                .reference("JIRA-2")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void duplicateAttributeKey_isInvalid() {
        assertThatThrownBy(() -> base()
                .attribute("k", "a")
                .attribute("k", "b")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate attribute key");
    }

    @Test
    void repeatedLinkedTo_isAdditive() {
        SpanLinkContext second = SpanLinkContext.sampled(
                "fedcba9876543210fedcba9876543210fe",
                "fedcba9876543210");

        SpanSpec spec = base().root().linkedTo(LINK).linkedTo(second).build();

        assertThat(spec.options().links()).containsExactly(LINK, second);
    }

    @Test
    void temporaryWorkaroundWithoutReference_isInvalid() {
        assertThatThrownBy(() -> SpanSpec.builder("w")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.TEMPORARY_WORKAROUND)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEMPORARY_WORKAROUND");
    }

    @Test
    void temporaryWorkaroundWithReference_isValid() {
        SpanSpec spec = SpanSpec.builder("w")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.TEMPORARY_WORKAROUND)
                .reference("JIRA-123")
                .build();

        assertThat(spec.reason()).isEqualTo(SpanSpecReason.TEMPORARY_WORKAROUND);
        assertThat(spec.reference()).contains("JIRA-123");
    }

    @Test
    void forbiddenCatchAllReasonValues_areAbsent() {
        for (String forbidden : List.of("OTHER", "UNKNOWN", "CUSTOM", "MISC")) {
            assertThatThrownBy(() -> SpanSpecReason.valueOf(forbidden))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void categoryIsRequired() {
        assertThatThrownBy(() -> SpanSpec.builder("x")
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("category");
    }

    @Test
    void reasonIsRequired() {
        assertThatThrownBy(() -> SpanSpec.builder("x")
                .category(SpanCategory.INTERNAL)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void defaultTopologyIsChildWithoutExplicitSetter() {
        SpanSpec spec = base().build();

        assertThat(spec.options().topology()).isEqualTo(Topology.CHILD);
        assertThat(spec.options().links()).isEmpty();
    }
}
```

## Sources by module: platform-tracing-core


## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/DefaultPlatformTracing.java

**Status:** MODIFIED  
**Main type:** `DefaultPlatformTracing`  
**Slice / area:** Slice 1B cutover  
**Why it matters for review:** Thin facade over SPI  
**Lines:** 86

```java
package space.br1440.platform.tracing.core;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.impl.DelegatingTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingMode;
import space.br1440.platform.tracing.core.manual.DefaultManualTracing;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import io.opentelemetry.api.OpenTelemetry;

public class DefaultPlatformTracing implements PlatformTracing {

    private final TracingImplementation implementation;
    private final ManualTracing manualTracing;

    public DefaultPlatformTracing(@Nonnull TracingImplementation implementation) {
        this.implementation = implementation;
        this.manualTracing = new DefaultManualTracing(implementation, attributePolicyFor(implementation));
    }

    private static AttributePolicy attributePolicyFor(TracingImplementation implementation) {
        TracingImplementation core = unwrapDelegate(implementation);
        if (core instanceof DefaultTracingImplementation defaultImpl) {
            return defaultImpl.attributePolicy();
        }

        return new AttributePolicy();
    }

    private static TracingImplementation unwrapDelegate(TracingImplementation implementation) {
        if (implementation instanceof DelegatingTracingImplementation delegating) {
            return unwrapDelegate(delegating.delegate());
        }

        return implementation;
    }

    public DefaultPlatformTracing(@Nonnull OpenTelemetry openTelemetry) {
        this(openTelemetry, new AttributePolicy());
    }

    public DefaultPlatformTracing(@Nonnull OpenTelemetry openTelemetry,
                                  @Nonnull AttributePolicy policy) {
        this(openTelemetry, policy, ExceptionRecorder.secureDefault());
    }

    public DefaultPlatformTracing(@Nonnull OpenTelemetry openTelemetry,
                                  @Nonnull AttributePolicy policy,
                                  @Nonnull ExceptionRecorder exceptionRecorder) {
        this(new DefaultTracingImplementation(openTelemetry, policy, exceptionRecorder));
    }

    @Override
    @Nonnull
    public TraceContextView traceContext() {
        return implementation.currentTraceContext();
    }

    @Override
    @Nonnull
    public ManualTracing manual() {
        return manualTracing;
    }

    public boolean isFacadeEnabled() {
        return implementation.state().mode() == TracingMode.ENABLED;
    }

    public void setFacadeEnabled(boolean enabled) {
        TracingImplementation core = unwrapDelegate(implementation);
        if (core instanceof DefaultTracingImplementation defaultImpl) {
            defaultImpl.setKillSwitchEnabled(enabled);
        }
    }

    @Nonnull
    public TracingImplementation tracingImplementation() {
        return implementation;
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/DefaultTracingImplementation.java

**Status:** ADDED  
**Main type:** `DefaultTracingImplementation`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** OTel-backed SPI  
**Lines:** 185

```java
package space.br1440.platform.tracing.core.impl;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanTopologySpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.manual.DefaultTraceContextView;
import space.br1440.platform.tracing.core.manual.NoOpSpanHandle;
import space.br1440.platform.tracing.core.manual.SpanHandleImpl;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.span.OwningSpanScope;
import space.br1440.platform.tracing.core.span.SpanKinds;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link TracingImplementation} backed by OpenTelemetry SDK/API (Slice 2).
 */
public final class DefaultTracingImplementation implements TracingImplementation {

    public static final String INSTRUMENTATION_NAME = "space.br1440.platform.tracing";

    private final Tracer tracer;
    private final AttributePolicy attributePolicy;
    private final ExceptionRecorder exceptionRecorder;
    private final TraceContextView traceContextView;
    private final AtomicBoolean killSwitchEnabled = new AtomicBoolean(true);

    public DefaultTracingImplementation(@Nonnull OpenTelemetry openTelemetry,
                                        @Nonnull AttributePolicy policy,
                                        @Nonnull ExceptionRecorder exceptionRecorder) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(policy, "policy");
        this.attributePolicy = policy;
        this.exceptionRecorder = Objects.requireNonNull(exceptionRecorder, "exceptionRecorder");
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        this.traceContextView = new DefaultTraceContextView(this::currentTraceId, this::currentSpanId);
    }

    /**
     * Runtime kill-switch preserved from Slice 1B {@code facadeEnabled} semantics.
     */
    public void setKillSwitchEnabled(boolean enabled) {
        killSwitchEnabled.set(enabled);
    }

    public boolean isKillSwitchEnabled() {
        return killSwitchEnabled.get();
    }

    @Nonnull
    public AttributePolicy attributePolicy() {
        return attributePolicy;
    }

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        Objects.requireNonNull(spec, "spec");
        SpanOptions.validateTopologyLinks(spec.options().topology(), spec.options().links());
        if (!killSwitchEnabled.get()) {
            return NoOpSpanHandle.INSTANCE;
        }

        Topology topology = spec.options().topology();
        SpanBuilder builder = tracer.spanBuilder(spec.name())
                .setSpanKind(SpanKinds.toSpanKind(spec.category()))
                .setAttribute(PlatformAttributes.PLATFORM_TYPE, spec.category().value());

        if (topology == Topology.ROOT || topology == Topology.DETACHED) {
            builder.setParent(Context.root());
        }

        for (var link : spec.options().links()) {
            builder.addLink(toRemoteSpanContext(link));
        }

        var span = builder.startSpan();
        Scope scope = span.makeCurrent();
        var spanScope = new OwningSpanScope(span, scope, exceptionRecorder);
        applySpecAttributes(spanScope, spec.attributes());
        return SpanHandleImpl.wrap(spanScope);
    }

    @Override
    @Nonnull
    public TraceContextView currentTraceContext() {
        return traceContextView;
    }

    @Override
    public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        Objects.requireNonNull(span, "span").recordException(throwable);
    }

    @Override
    @Nonnull
    public TracingState state() {
        if (!killSwitchEnabled.get()) {
            return ImmutableTracingState.of(
                    TracingMode.DISABLED_BY_CONFIGURATION,
                    Optional.of("runtime.kill-switch"),
                    Map.of("source", "setFacadeEnabled(false)"));
        }
        return ImmutableTracingState.enabled();
    }

    @Nonnull
    Optional<String> currentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context.getTraceId()) : Optional.empty();
    }

    @Nonnull
    Optional<String> currentSpanId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context.getSpanId()) : Optional.empty();
    }

    private void applySpecAttributes(@Nonnull space.br1440.platform.tracing.api.span.SpanScope scope,
                                       @Nonnull Map<String, SpanAttributeValue> attributes) {
        for (Map.Entry<String, SpanAttributeValue> entry : attributes.entrySet()) {
            applyAttribute(scope, entry.getKey(), entry.getValue());
        }
    }

    private void applyAttribute(@Nonnull space.br1440.platform.tracing.api.span.SpanScope scope,
                                @Nonnull String key,
                                @Nonnull SpanAttributeValue value) {
        switch (value) {
            case SpanAttributeValue.StringValue sv -> scope.setAttribute(key, sv.value());
            case SpanAttributeValue.LongValue lv -> scope.setAttribute(key, lv.value());
            case SpanAttributeValue.DoubleValue dv -> scope.setAttribute(key, dv.value());
            case SpanAttributeValue.BooleanValue bv -> scope.setAttribute(key, bv.value());
            case SpanAttributeValue.StringListValue slv -> scope.setAttribute(key, String.join(",", slv.values()));
            case SpanAttributeValue.LongListValue llv -> scope.setAttribute(key, llv.values().toString());
            case SpanAttributeValue.DoubleListValue dlv -> scope.setAttribute(key, dlv.values().toString());
            case SpanAttributeValue.BooleanListValue blv -> scope.setAttribute(key, blv.values().toString());
        }
    }

    @Nonnull
    private static SpanContext toRemoteSpanContext(@Nonnull space.br1440.platform.tracing.api.span.SpanLinkContext link) {
        return SpanContext.createFromRemoteParent(
                link.traceId(),
                link.spanId(),
                TraceFlags.fromByte(link.traceFlags()),
                resolveTraceState(link.traceState()));
    }

    @Nonnull
    private static TraceState resolveTraceState(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return TraceState.getDefault();
        }
        TraceStateBuilder builder = TraceState.builder();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            int separator = trimmed.indexOf('=');
            if (separator > 0 && separator < trimmed.length() - 1) {
                builder.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
            }
        }
        return builder.build();
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/DelegatingTracingImplementation.java

**Status:** ADDED  
**Main type:** `DelegatingTracingImplementation`  
**Slice / area:** Slice 6 Metering  
**Why it matters for review:** Decorator marker  
**Lines:** 12

```java
package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;

/**
 * Marker for {@link TracingImplementation} decorators that fully delegate behavior.
 */
public interface DelegatingTracingImplementation extends TracingImplementation {

    @Nonnull
    TracingImplementation delegate();
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/ImmutableTracingState.java

**Status:** ADDED  
**Main type:** `ImmutableTracingState`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** TracingState impl  
**Lines:** 53

```java
package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ImmutableTracingState implements TracingState {

    private static final TracingState ENABLED = new ImmutableTracingState(
            TracingMode.ENABLED, Optional.empty(), Map.of());

    private final TracingMode mode;
    private final Optional<String> reason;
    private final Map<String, String> details;

    private ImmutableTracingState(@Nonnull TracingMode mode,
                                  @Nonnull Optional<String> reason,
                                  @Nonnull Map<String, String> details) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.details = Map.copyOf(details);
    }

    static TracingState enabled() {
        return ENABLED;
    }

    static TracingState of(@Nonnull TracingMode mode,
                           @Nonnull Optional<String> reason,
                           @Nonnull Map<String, String> details) {
        return new ImmutableTracingState(mode, reason, details);
    }

    @Override
    @Nonnull
    public TracingMode mode() {
        return mode;
    }

    @Override
    @Nonnull
    public Optional<String> reason() {
        return reason;
    }

    @Override
    @Nonnull
    public Map<String, String> details() {
        return details;
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/NoOpTracingImplementation.java

**Status:** ADDED  
**Main type:** `NoOpTracingImplementation`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** Disabled/unavailable SPI  
**Lines:** 83

```java
package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.manual.DefaultTraceContextView;
import space.br1440.platform.tracing.core.manual.NoOpSpanHandle;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * No-op {@link TracingImplementation} for disabled/unavailable tracing (Slice 2).
 */
public final class NoOpTracingImplementation implements TracingImplementation {

    private final TracingState state;
    private final TraceContextView traceContextView;

    private NoOpTracingImplementation(@Nonnull TracingState state) {
        this.state = Objects.requireNonNull(state, "state");
        this.traceContextView = new DefaultTraceContextView(this::currentTraceId, this::currentSpanId);
    }

    public static NoOpTracingImplementation disabledByConfiguration(@Nonnull String reason) {
        return new NoOpTracingImplementation(ImmutableTracingState.of(
                TracingMode.DISABLED_BY_CONFIGURATION,
                Optional.of(reason),
                Map.of()));
    }

    public static NoOpTracingImplementation unavailable(@Nonnull String reason) {
        return new NoOpTracingImplementation(ImmutableTracingState.of(
                TracingMode.UNAVAILABLE,
                Optional.of(reason),
                Map.of()));
    }

    public static NoOpTracingImplementation noop() {
        return new NoOpTracingImplementation(ImmutableTracingState.of(
                TracingMode.NOOP,
                Optional.empty(),
                Map.of()));
    }

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    @Nonnull
    public TraceContextView currentTraceContext() {
        return traceContextView;
    }

    @Override
    public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        Objects.requireNonNull(span, "span");
    }

    @Override
    @Nonnull
    public TracingState state() {
        return state;
    }

    @Nonnull
    Optional<String> currentTraceId() {
        return Optional.empty();
    }

    @Nonnull
    Optional<String> currentSpanId() {
        return Optional.empty();
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/OperationSpanSpecs.java

**Status:** ADDED  
**Main type:** `OperationSpanSpecs`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** Operation spec helpers  
**Lines:** 51

```java
package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;

import java.util.List;
import java.util.Objects;

/**
 * Builds governed {@link SpanSpec} instances for {@code manual().operation(...)} routing.
 */
public final class OperationSpanSpecs {

    private OperationSpanSpecs() {
    }

    @Nonnull
    public static SpanSpec from(@Nonnull String name,
                         @Nonnull SpanCategory category,
                         @Nonnull Topology topology,
                         @Nonnull List<SpanLinkContext> links) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(links, "links");

        var builder = SpanSpec.builder(name)
                .category(category)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE);

        switch (topology) {
            case CHILD -> builder.child();
            case ROOT -> {
                builder.root();
                if (!links.isEmpty()) {
                    builder.linkedTo(links.toArray(SpanLinkContext[]::new));
                }
            }
            case DETACHED -> builder.detached();
        }
        return builder.build();
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/SemanticSpanSpecs.java

**Status:** ADDED  
**Main type:** `SemanticSpanSpecs`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** Semantic spec helpers  
**Lines:** 96

```java
package space.br1440.platform.tracing.core.impl;

import io.opentelemetry.api.common.Attributes;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecBuilder;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.ValidatedAttributes;
import space.br1440.platform.tracing.core.span.PlatformSpanNameBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates semantic attributes and builds governed {@link SpanSpec} for manual transport builders.
 */
public final class SemanticSpanSpecs {

    private SemanticSpanSpecs() {
    }

    @Nonnull
    public static SpanSpec build(@Nonnull SpanCategory category,
                                 @Nullable String explicitName,
                                 @Nonnull Topology topology,
                                 @Nonnull List<SpanLinkContext> links,
                                 @Nonnull Map<String, SpanAttributeValue> attributes,
                                 @Nonnull AttributePolicy policy,
                                 @Nonnull String builderName) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(links, "links");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(builderName, "builderName");

        Map<String, SpanAttributeValue> enriched = new LinkedHashMap<>(attributes);
        String platformTypeKey = SemconvKeys.PLATFORM_TYPE.getKey();
        if (!enriched.containsKey(platformTypeKey)) {
            enriched.put(platformTypeKey, SpanAttributeValue.of(category.value()));
        }

        Attributes accumulated = SpanAttributeValueConverter.toAttributes(enriched);
        ValidatedAttributes validated = policy.validateAndNormalize(category, accumulated, builderName);
        String spanName = PlatformSpanNameBuilder.forCategory(category, validated.attributes(), explicitName);
        Map<String, SpanAttributeValue> normalized =
                SpanAttributeValueConverter.fromAttributes(validated.attributes());

        SpanSpecBuilder builder = SpanSpec.builder(spanName)
                .category(category)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE);
        applyTopology(builder, topology, links);
        applyAttributes(builder, normalized);
        return builder.build();
    }

    private static void applyTopology(SpanSpecBuilder builder, Topology topology, List<SpanLinkContext> links) {
        switch (topology) {
            case CHILD -> builder.child();
            case ROOT -> {
                builder.root();
                if (!links.isEmpty()) {
                    builder.linkedTo(links.toArray(SpanLinkContext[]::new));
                }
            }
            case DETACHED -> builder.detached();
        }
    }

    private static void applyAttributes(SpanSpecBuilder builder, Map<String, SpanAttributeValue> attributes) {
        Map<String, SpanAttributeValue> copy = new LinkedHashMap<>(attributes);
        for (Map.Entry<String, SpanAttributeValue> entry : copy.entrySet()) {
            switch (entry.getValue()) {
                case SpanAttributeValue.StringValue sv -> builder.attribute(entry.getKey(), sv.value());
                case SpanAttributeValue.LongValue lv -> builder.attribute(entry.getKey(), lv.value());
                case SpanAttributeValue.DoubleValue dv -> builder.attribute(entry.getKey(), dv.value());
                case SpanAttributeValue.BooleanValue bv -> builder.attribute(entry.getKey(), bv.value());
                case SpanAttributeValue.StringListValue slv -> builder.stringListAttribute(entry.getKey(), slv.values());
                case SpanAttributeValue.LongListValue llv -> builder.longListAttribute(entry.getKey(), llv.values());
                case SpanAttributeValue.DoubleListValue dlv ->
                        builder.doubleListAttribute(entry.getKey(), dlv.values());
                case SpanAttributeValue.BooleanListValue blv ->
                        builder.booleanListAttribute(entry.getKey(), blv.values());
            }
        }
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/SpanAttributeValueConverter.java

**Status:** ADDED  
**Main type:** `SpanAttributeValueConverter`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** Attribute conversion  
**Lines:** 94

```java
package space.br1440.platform.tracing.core.impl;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SpanAttributeValueConverter {

    private SpanAttributeValueConverter() {
    }

    @Nonnull
    static Attributes toAttributes(@Nonnull Map<String, SpanAttributeValue> attributes) {
        AttributesBuilder builder = Attributes.builder();
        for (Map.Entry<String, SpanAttributeValue> entry : attributes.entrySet()) {
            apply(builder, entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @Nonnull
    static Map<String, SpanAttributeValue> fromAttributes(@Nonnull Attributes attributes) {
        Map<String, SpanAttributeValue> map = new LinkedHashMap<>();
        attributes.forEach((key, value) -> map.put(key.getKey(), fromOtelValue(value)));
        return Map.copyOf(map);
    }

    private static void apply(AttributesBuilder builder, String key, SpanAttributeValue value) {
        switch (value) {
            case SpanAttributeValue.StringValue sv -> builder.put(key, sv.value());
            case SpanAttributeValue.LongValue lv -> builder.put(key, lv.value());
            case SpanAttributeValue.DoubleValue dv -> builder.put(key, dv.value());
            case SpanAttributeValue.BooleanValue bv -> builder.put(key, bv.value());
            case SpanAttributeValue.StringListValue slv ->
                    builder.put(key, slv.values().toArray(String[]::new));
            case SpanAttributeValue.LongListValue llv ->
                    builder.put(key, llv.values().stream().mapToLong(Long::longValue).toArray());
            case SpanAttributeValue.DoubleListValue dlv ->
                    builder.put(key, dlv.values().stream().mapToDouble(Double::doubleValue).toArray());
            case SpanAttributeValue.BooleanListValue blv -> {
                boolean[] values = new boolean[blv.values().size()];
                for (int i = 0; i < blv.values().size(); i++) {
                    values[i] = blv.values().get(i);
                }
                builder.put(key, values);
            }
        }
    }

    @Nonnull
    private static SpanAttributeValue fromOtelValue(Object value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof String s) {
            return SpanAttributeValue.of(s);
        }
        if (value instanceof Long l) {
            return SpanAttributeValue.of(l);
        }
        if (value instanceof Integer i) {
            return SpanAttributeValue.of(i.longValue());
        }
        if (value instanceof Double d) {
            return SpanAttributeValue.of(d);
        }
        if (value instanceof Boolean b) {
            return SpanAttributeValue.of(b);
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return SpanAttributeValue.stringList(List.of());
            }
            Object first = list.getFirst();
            if (first instanceof String) {
                return SpanAttributeValue.stringList(list.stream().map(String.class::cast).toList());
            }
            if (first instanceof Long) {
                return SpanAttributeValue.longList(list.stream().map(Long.class::cast).toList());
            }
            if (first instanceof Double) {
                return SpanAttributeValue.doubleList(list.stream().map(Double.class::cast).toList());
            }
            if (first instanceof Boolean) {
                return SpanAttributeValue.booleanList(list.stream().map(Boolean.class::cast).toList());
            }
        }
        return SpanAttributeValue.of(String.valueOf(value));
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/TracingImplementation.java

**Status:** ADDED  
**Main type:** `TracingImplementation`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** Internal SPI boundary  
**Lines:** 27

```java
package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;

/**
 * Internal single span-creation boundary for platform manual tracing (Slice 2).
 * <p>
 * Not application-facing API. Fully abstract SPI: no default methods, no behavioral static helpers.
 */
public interface TracingImplementation {

    @Nonnull
    SpanHandle startSpan(@Nonnull SpanSpec spec);

    @Nonnull
    TraceContextView currentTraceContext();

    void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable);

    @Nonnull
    TracingState state();
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/TracingMode.java

**Status:** ADDED  
**Main type:** `TracingMode`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** Internal mode enum  
**Lines:** 12

```java
package space.br1440.platform.tracing.core.impl;

/**
 * Internal supportability mode for platform tracing (Slice 2).
 */
public enum TracingMode {
    ENABLED,
    DISABLED_BY_CONFIGURATION,
    UNAVAILABLE,
    NOOP,
    TEST
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/impl/TracingState.java

**Status:** ADDED  
**Main type:** `TracingState`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** Internal supportability state  
**Lines:** 21

```java
package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;

import java.util.Map;
import java.util.Optional;

/**
 * Internal supportability state for {@link TracingImplementation} (Slice 2).
 */
public interface TracingState {

    @Nonnull
    TracingMode mode();

    @Nonnull
    Optional<String> reason();

    @Nonnull
    Map<String, String> details();
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/AbstractSemanticSpanBuilder.java

**Status:** MODIFIED  
**Main type:** `AbstractSemanticSpanBuilder`  
**Slice / area:** Slice 5A Topology  
**Why it matters for review:** Builder base + topology  
**Lines:** 145

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.PlatformSpanBuilder;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteContext;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanTopologySpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.core.impl.OperationSpanSpecs;
import space.br1440.platform.tracing.core.impl.SemanticSpanSpecs;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

abstract class AbstractSemanticSpanBuilder<B extends PlatformSpanBuilder<B>> implements PlatformSpanBuilder<B> {

    protected final TracingImplementation implementation;
    protected final AttributePolicy policy;
    protected final SpanCategory category;
    protected final String explicitName;
    protected final String builderName;
    protected Topology topology = Topology.CHILD;
    protected final List<SpanLinkContext> links = new ArrayList<>();
    protected final Map<String, SpanAttributeValue> attributes = new LinkedHashMap<>();

    AbstractSemanticSpanBuilder(@Nonnull TracingImplementation implementation,
                                @Nonnull AttributePolicy policy,
                                @Nonnull SpanCategory category,
                                @Nonnull String explicitName,
                                @Nonnull String builderName) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.category = Objects.requireNonNull(category, "category");
        this.explicitName = Objects.requireNonNull(explicitName, "explicitName");
        this.builderName = Objects.requireNonNull(builderName, "builderName");
        if (explicitName.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    protected abstract B self();

    protected void putAttribute(@Nonnull String key, @Nonnull SpanAttributeValue value) {
        if (attributes.containsKey(key)) {
            throw new IllegalStateException("duplicate attribute key: " + key);
        }
        attributes.put(key, value);
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B child() {
        topology = Topology.CHILD;
        return (B) self();
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B root() {
        topology = Topology.ROOT;
        return (B) self();
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B detached() {
        topology = Topology.DETACHED;
        return (B) self();
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B linkedTo(@Nonnull SpanLinkContext... linkContexts) {
        Objects.requireNonNull(linkContexts, "linkContexts");
        for (SpanLinkContext link : linkContexts) {
            links.add(Objects.requireNonNull(link, "link"));
        }
        return (B) self();
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public B fromRemoteContext(@Nonnull String... traceparents) {
        Objects.requireNonNull(traceparents, "traceparents");
        for (String traceparent : traceparents) {
            linkedTo(RemoteContext.requireTraceparent(Objects.requireNonNull(traceparent, "traceparent")));
        }
        return (B) self();
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return implementation.startSpan(toSpanSpec());
    }

    @Override
    public void run(@Nonnull Runnable action) {
        ScopedExecution.run(this::start, action);
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return ScopedExecution.call(this::start, supplier);
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return ScopedExecution.callChecked(this::start, supplier);
    }

    @Nonnull
    protected SpanSpec toSpanSpec() {
        SpanOptions.validateTopologyLinks(topology, links);
        if (category == SpanCategory.INTERNAL) {
            return OperationSpanSpecs.from(explicitName, category, topology, List.copyOf(links));
        }
        return SemanticSpanSpecs.build(
                category,
                explicitName,
                topology,
                List.copyOf(links),
                attributes,
                policy,
                builderName);
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DatabaseSpanBuilderImpl.java

**Status:** ADDED  
**Main type:** `DatabaseSpanBuilderImpl`  
**Slice / area:** Slice 3B Database  
**Why it matters for review:** Database builder impl  
**Lines:** 68

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.DatabaseSpanBuilder;
import space.br1440.platform.tracing.api.manual.DatabaseTracing;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

final class DatabaseSpanBuilderImpl extends AbstractSemanticSpanBuilder<DatabaseSpanBuilder>
        implements DatabaseTracing {

    DatabaseSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                            @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.DATABASE, SpanCategory.DATABASE.value(),
                "DatabaseSpanBuilder");
    }

    @Override
    protected DatabaseSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder system(@Nonnull String dbSystem) {
        putAttribute(SemconvKeys.DB_SYSTEM_NAME.getKey(), SpanAttributeValue.of(dbSystem));
        return this;
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder operation(@Nonnull String operation) {
        putAttribute(SemconvKeys.DB_OPERATION_NAME.getKey(), SpanAttributeValue.of(operation));
        return this;
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder collection(@Nonnull String collection) {
        putAttribute(SemconvKeys.DB_COLLECTION_NAME.getKey(), SpanAttributeValue.of(collection));
        return this;
    }

    @Override
    protected SpanSpec toSpanSpec() {
        requireAttribute(SemconvKeys.DB_OPERATION_NAME.getKey(), "operation");
        requireSystemAttribute();
        requireAttribute(SemconvKeys.DB_COLLECTION_NAME.getKey(), "collection");
        return super.toSpanSpec();
    }

    private void requireAttribute(@Nonnull String key, @Nonnull String label) {
        if (!attributes.containsKey(key)) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    private void requireSystemAttribute() {
        if (!attributes.containsKey(SemconvKeys.DB_SYSTEM_NAME.getKey())
                && !attributes.containsKey(SemconvKeys.DB_SYSTEM_LEGACY.getKey())) {
            throw new IllegalArgumentException("system is required");
        }
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultHttpTracing.java

**Status:** ADDED  
**Main type:** `DefaultHttpTracing`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** HTTP tracing impl  
**Lines:** 121

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.HttpClientSpanBuilder;
import space.br1440.platform.tracing.api.manual.HttpServerSpanBuilder;
import space.br1440.platform.tracing.api.manual.HttpTracing;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.sanitize.UrlSanitizer;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;

final class DefaultHttpTracing implements HttpTracing {

    private final TracingImplementation implementation;
    private final AttributePolicy policy;

    DefaultHttpTracing(@Nonnull TracingImplementation implementation,
                         @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public HttpServerSpanBuilder server() {
        return new HttpServerSpanBuilderImpl(implementation, policy);
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder client() {
        return new HttpClientSpanBuilderImpl(implementation, policy);
    }
}

final class HttpServerSpanBuilderImpl extends AbstractSemanticSpanBuilder<HttpServerSpanBuilder>
        implements HttpServerSpanBuilder {

    HttpServerSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                              @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.HTTP_SERVER, SpanCategory.HTTP_SERVER.value(),
                "HttpServerSpanBuilder");
    }

    @Override
    protected HttpServerSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public HttpServerSpanBuilder method(@Nonnull String httpMethod) {
        putAttribute(SemconvKeys.HTTP_REQUEST_METHOD.getKey(), SpanAttributeValue.of(httpMethod));
        return this;
    }

    @Override
    @Nonnull
    public HttpServerSpanBuilder route(@Nonnull String route) {
        putAttribute(SemconvKeys.HTTP_ROUTE.getKey(), SpanAttributeValue.of(route));
        return this;
    }

    @Override
    @Nonnull
    public HttpServerSpanBuilder statusCode(long statusCode) {
        putAttribute(SemconvKeys.HTTP_RESPONSE_STATUS_CODE.getKey(), SpanAttributeValue.of(statusCode));
        return this;
    }
}

final class HttpClientSpanBuilderImpl extends AbstractSemanticSpanBuilder<HttpClientSpanBuilder>
        implements HttpClientSpanBuilder {

    HttpClientSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                              @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.HTTP_CLIENT, SpanCategory.HTTP_CLIENT.value(),
                "HttpClientSpanBuilder");
    }

    @Override
    protected HttpClientSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder method(@Nonnull String httpMethod) {
        putAttribute(SemconvKeys.HTTP_REQUEST_METHOD.getKey(), SpanAttributeValue.of(httpMethod));
        return this;
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder url(@Nonnull String rawUrl) {
        String sanitized = UrlSanitizer.sanitize(rawUrl);
        if (sanitized == null || sanitized.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        putAttribute(SemconvKeys.URL_FULL.getKey(), SpanAttributeValue.of(sanitized));
        return this;
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder statusCode(long statusCode) {
        putAttribute(SemconvKeys.HTTP_RESPONSE_STATUS_CODE.getKey(), SpanAttributeValue.of(statusCode));
        return this;
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder serverAddress(@Nonnull String address) {
        putAttribute(SemconvKeys.SERVER_ADDRESS.getKey(), SpanAttributeValue.of(address));
        return this;
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultKafkaTracing.java

**Status:** ADDED  
**Main type:** `DefaultKafkaTracing`  
**Slice / area:** Slice 3C Kafka  
**Why it matters for review:** Kafka tracing impl  
**Lines:** 152

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.KafkaBatchSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaConsumerSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaProducerSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaTracing;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;

final class DefaultKafkaTracing implements KafkaTracing {

    private final TracingImplementation implementation;
    private final AttributePolicy policy;

    DefaultKafkaTracing(@Nonnull TracingImplementation implementation,
                          @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder producer() {
        return new KafkaProducerSpanBuilderImpl(implementation, policy);
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder consumer() {
        return new KafkaConsumerSpanBuilderImpl(implementation, policy);
    }
}

abstract class AbstractKafkaSpanBuilder<B extends space.br1440.platform.tracing.api.manual.PlatformSpanBuilder<B>>
        extends AbstractSemanticSpanBuilder<B> {

    AbstractKafkaSpanBuilder(@Nonnull TracingImplementation implementation,
                             @Nonnull AttributePolicy policy,
                             @Nonnull SpanCategory category,
                             @Nonnull String builderName) {
        super(implementation, policy, category, category.value(), builderName);
        putAttribute(SemconvKeys.MESSAGING_SYSTEM.getKey(), SpanAttributeValue.of("kafka"));
    }

    @Override
    protected SpanSpec toSpanSpec() {
        requireAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), "destination");
        requireAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), "operation");
        return super.toSpanSpec();
    }

    private void requireAttribute(@Nonnull String key, @Nonnull String label) {
        if (!attributes.containsKey(key)) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}

final class KafkaProducerSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaProducerSpanBuilder>
        implements KafkaProducerSpanBuilder {

    KafkaProducerSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                                 @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.KAFKA_PRODUCER, "KafkaProducerSpanBuilder");
    }

    @Override
    protected KafkaProducerSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder destination(@Nonnull String topic) {
        putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanAttributeValue.of(topic));
        return this;
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder operation(@Nonnull String operation) {
        putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanAttributeValue.of(operation));
        return this;
    }
}

final class KafkaConsumerSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaConsumerSpanBuilder>
        implements KafkaConsumerSpanBuilder {

    private final TracingImplementation implementation;
    private final AttributePolicy policy;

    KafkaConsumerSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                                 @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.KAFKA_CONSUMER, "KafkaConsumerSpanBuilder");
        this.implementation = implementation;
        this.policy = policy;
    }

    @Override
    protected KafkaConsumerSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder destination(@Nonnull String topic) {
        putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanAttributeValue.of(topic));
        return this;
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder operation(@Nonnull String operation) {
        putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanAttributeValue.of(operation));
        return this;
    }

    @Override
    @Nonnull
    public KafkaBatchSpanBuilder batch(@Nonnull String destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("destination must not be blank");
        }
        return new KafkaBatchSpanBuilderImpl(implementation, policy, destination);
    }
}

final class KafkaBatchSpanBuilderImpl extends AbstractKafkaSpanBuilder<KafkaBatchSpanBuilder>
        implements KafkaBatchSpanBuilder {

    KafkaBatchSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                              @Nonnull AttributePolicy policy,
                              @Nonnull String destination) {
        super(implementation, policy, SpanCategory.KAFKA_CONSUMER, "KafkaBatchSpanBuilder");
        putAttribute(SemconvKeys.MESSAGING_DESTINATION_NAME.getKey(), SpanAttributeValue.of(destination));
        putAttribute(SemconvKeys.MESSAGING_OPERATION.getKey(), SpanAttributeValue.of("process"));
    }

    @Override
    protected KafkaBatchSpanBuilder self() {
        return this;
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultManualTracing.java

**Status:** ADDED  
**Main type:** `DefaultManualTracing`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** ManualTracing impl  
**Lines:** 43

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.OperationSpanBuilder;
import space.br1440.platform.tracing.api.manual.TransportTracing;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpecifiedSpan;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;

public final class DefaultManualTracing implements ManualTracing {

    private final TracingImplementation implementation;
    private final AttributePolicy policy;

    public DefaultManualTracing(@Nonnull TracingImplementation implementation,
                                @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public OperationSpanBuilder operation(@Nonnull String name) {
        Objects.requireNonNull(name, "name");
        return new OperationSpanBuilderImpl(implementation, policy, name);
    }

    @Override
    @Nonnull
    public TransportTracing transport() {
        return new DefaultTransportTracing(implementation, policy);
    }

    @Override
    @Nonnull
    public SpecifiedSpan spanFromSpec(@Nonnull SpanSpec spec) {
        return new SpecifiedSpanImpl(implementation, spec);
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultRpcTracing.java

**Status:** ADDED  
**Main type:** `DefaultRpcTracing`  
**Slice / area:** Slice 3C RPC  
**Why it matters for review:** RPC tracing impl  
**Lines:** 140

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.RpcClientSpanBuilder;
import space.br1440.platform.tracing.api.manual.RpcServerSpanBuilder;
import space.br1440.platform.tracing.api.manual.RpcTracing;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;

final class DefaultRpcTracing implements RpcTracing {

    private final TracingImplementation implementation;
    private final AttributePolicy policy;

    DefaultRpcTracing(@Nonnull TracingImplementation implementation,
                      @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public RpcServerSpanBuilder server() {
        return new RpcServerSpanBuilderImpl(implementation, policy);
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder client() {
        return new RpcClientSpanBuilderImpl(implementation, policy);
    }
}

abstract class AbstractRpcSpanBuilder<B extends space.br1440.platform.tracing.api.manual.PlatformSpanBuilder<B>>
        extends AbstractSemanticSpanBuilder<B> {

    AbstractRpcSpanBuilder(@Nonnull TracingImplementation implementation,
                           @Nonnull AttributePolicy policy,
                           @Nonnull SpanCategory category,
                           @Nonnull String builderName) {
        super(implementation, policy, category, category.value(), builderName);
    }

    @Override
    protected SpanSpec toSpanSpec() {
        requireAttribute(SemconvKeys.RPC_SYSTEM.getKey(), "system");
        requireAttribute(SemconvKeys.RPC_SERVICE.getKey(), "service");
        requireAttribute(SemconvKeys.RPC_METHOD.getKey(), "method");
        return super.toSpanSpec();
    }

    private void requireAttribute(@Nonnull String key, @Nonnull String label) {
        if (!attributes.containsKey(key)) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}

final class RpcServerSpanBuilderImpl extends AbstractRpcSpanBuilder<RpcServerSpanBuilder>
        implements RpcServerSpanBuilder {

    RpcServerSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                             @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.RPC_SERVER, "RpcServerSpanBuilder");
    }

    @Override
    protected RpcServerSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public RpcServerSpanBuilder system(@Nonnull String rpcSystem) {
        putAttribute(SemconvKeys.RPC_SYSTEM.getKey(), SpanAttributeValue.of(rpcSystem));
        return this;
    }

    @Override
    @Nonnull
    public RpcServerSpanBuilder service(@Nonnull String service) {
        putAttribute(SemconvKeys.RPC_SERVICE.getKey(), SpanAttributeValue.of(service));
        return this;
    }

    @Override
    @Nonnull
    public RpcServerSpanBuilder method(@Nonnull String method) {
        putAttribute(SemconvKeys.RPC_METHOD.getKey(), SpanAttributeValue.of(method));
        return this;
    }
}

final class RpcClientSpanBuilderImpl extends AbstractRpcSpanBuilder<RpcClientSpanBuilder>
        implements RpcClientSpanBuilder {

    RpcClientSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                             @Nonnull AttributePolicy policy) {
        super(implementation, policy, SpanCategory.RPC_CLIENT, "RpcClientSpanBuilder");
    }

    @Override
    protected RpcClientSpanBuilder self() {
        return this;
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder system(@Nonnull String rpcSystem) {
        putAttribute(SemconvKeys.RPC_SYSTEM.getKey(), SpanAttributeValue.of(rpcSystem));
        return this;
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder service(@Nonnull String service) {
        putAttribute(SemconvKeys.RPC_SERVICE.getKey(), SpanAttributeValue.of(service));
        return this;
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder method(@Nonnull String method) {
        putAttribute(SemconvKeys.RPC_METHOD.getKey(), SpanAttributeValue.of(method));
        return this;
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder serverAddress(@Nonnull String address) {
        putAttribute(SemconvKeys.SERVER_ADDRESS.getKey(), SpanAttributeValue.of(address));
        return this;
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultTraceContextView.java

**Status:** ADDED  
**Main type:** `DefaultTraceContextView`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** TraceContextView impl  
**Lines:** 37

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.TraceContextView;

import java.util.Optional;
import java.util.function.Supplier;

public final class DefaultTraceContextView implements TraceContextView {

    private final Supplier<Optional<String>> traceIdSupplier;
    private final Supplier<Optional<String>> spanIdSupplier;

    public DefaultTraceContextView(@Nonnull Supplier<Optional<String>> traceIdSupplier,
                            @Nonnull Supplier<Optional<String>> spanIdSupplier) {
        this.traceIdSupplier = traceIdSupplier;
        this.spanIdSupplier = spanIdSupplier;
    }

    @Override
    @Nonnull
    public Optional<String> traceId() {
        return traceIdSupplier.get();
    }

    @Override
    @Nonnull
    public Optional<String> spanId() {
        return spanIdSupplier.get();
    }

    @Override
    @Nonnull
    public Optional<String> correlationId() {
        return Optional.empty();
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/DefaultTransportTracing.java

**Status:** ADDED  
**Main type:** `DefaultTransportTracing`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** Transport entry  
**Lines:** 51

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.DatabaseTracing;
import space.br1440.platform.tracing.api.manual.HttpTracing;
import space.br1440.platform.tracing.api.manual.KafkaTracing;
import space.br1440.platform.tracing.api.manual.RpcTracing;
import space.br1440.platform.tracing.api.manual.TransportTracing;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;

/**
 * Slice 3Aâ€“3C transport grouping with semantic builders for HTTP, database, RPC, and Kafka.
 */
public final class DefaultTransportTracing implements TransportTracing {

    private final TracingImplementation implementation;
    private final AttributePolicy policy;

    public DefaultTransportTracing(@Nonnull TracingImplementation implementation,
                                   @Nonnull AttributePolicy policy) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    @Nonnull
    public HttpTracing http() {
        return new DefaultHttpTracing(implementation, policy);
    }

    @Override
    @Nonnull
    public DatabaseTracing database() {
        return new DatabaseSpanBuilderImpl(implementation, policy);
    }

    @Override
    @Nonnull
    public RpcTracing rpc() {
        return new DefaultRpcTracing(implementation, policy);
    }

    @Override
    @Nonnull
    public KafkaTracing kafka() {
        return new DefaultKafkaTracing(implementation, policy);
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/NoOpManualTracing.java

**Status:** ADDED  
**Main type:** `NoOpManualTracing`  
**Slice / area:** Slice 1B cutover  
**Why it matters for review:** No-op manual tracing  
**Lines:** 132

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.OperationSpanBuilder;
import space.br1440.platform.tracing.api.manual.TransportTracing;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpecifiedSpan;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.Objects;
import java.util.function.Supplier;

public final class NoOpManualTracing implements ManualTracing {

    public static final NoOpManualTracing INSTANCE = new NoOpManualTracing();

    private NoOpManualTracing() {
    }

    @Override
    @Nonnull
    public OperationSpanBuilder operation(@Nonnull String name) {
        return NoOpOperationSpanBuilder.INSTANCE;
    }

    @Override
    @Nonnull
    public TransportTracing transport() {
        return StubTransportTracing.INSTANCE;
    }

    @Override
    @Nonnull
    public SpecifiedSpan spanFromSpec(@Nonnull SpanSpec spec) {
        return NoOpSpecifiedSpan.INSTANCE;
    }
}

final class NoOpOperationSpanBuilder implements OperationSpanBuilder {

    static final NoOpOperationSpanBuilder INSTANCE = new NoOpOperationSpanBuilder();

    private NoOpOperationSpanBuilder() {
    }

    @Override
    @Nonnull
    public OperationSpanBuilder child() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder root() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder detached() {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder linkedTo(@Nonnull space.br1440.platform.tracing.api.span.SpanLinkContext... links) {
        return this;
    }

    @Override
    @Nonnull
    public OperationSpanBuilder fromRemoteContext(@Nonnull String... traceparents) {
        return this;
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    public void run(@Nonnull Runnable action) {
        Objects.requireNonNull(action, "action").run();
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return Objects.requireNonNull(supplier, "supplier").get();
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return Objects.requireNonNull(supplier, "supplier").get();
    }
}

final class NoOpSpecifiedSpan implements SpecifiedSpan {

    static final NoOpSpecifiedSpan INSTANCE = new NoOpSpecifiedSpan();

    private NoOpSpecifiedSpan() {
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    public void run(@Nonnull Runnable action) {
        Objects.requireNonNull(action, "action").run();
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return Objects.requireNonNull(supplier, "supplier").get();
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return Objects.requireNonNull(supplier, "supplier").get();
    }
}

```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/NoOpSpanHandle.java

**Status:** ADDED  
**Main type:** `NoOpSpanHandle`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** No-op span handle  
**Lines:** 20

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

public final class NoOpSpanHandle implements SpanHandle {

    public static final NoOpSpanHandle INSTANCE = new NoOpSpanHandle();

    private NoOpSpanHandle() {
    }

    @Override
    public void recordException(@Nullable Throwable throwable) {
    }

    @Override
    public void close() {
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/NoOpTraceContextView.java

**Status:** ADDED  
**Main type:** `NoOpTraceContextView`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** No-op context view  
**Lines:** 32

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.TraceContextView;

import java.util.Optional;

public final class NoOpTraceContextView implements TraceContextView {

    public static final NoOpTraceContextView INSTANCE = new NoOpTraceContextView();

    private NoOpTraceContextView() {
    }

    @Override
    @Nonnull
    public Optional<String> traceId() {
        return Optional.empty();
    }

    @Override
    @Nonnull
    public Optional<String> spanId() {
        return Optional.empty();
    }

    @Override
    @Nonnull
    public Optional<String> correlationId() {
        return Optional.empty();
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/OperationSpanBuilderImpl.java

**Status:** ADDED  
**Main type:** `OperationSpanBuilderImpl`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** Operation builder impl  
**Lines:** 24

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.OperationSpanBuilder;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;

final class OperationSpanBuilderImpl extends AbstractSemanticSpanBuilder<OperationSpanBuilder>
        implements OperationSpanBuilder {

    OperationSpanBuilderImpl(@Nonnull TracingImplementation implementation,
                               @Nonnull AttributePolicy policy,
                               @Nonnull String name) {
        super(implementation, policy, SpanCategory.INTERNAL, name, "OperationSpanBuilder");
    }

    @Override
    protected OperationSpanBuilder self() {
        return this;
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/ScopedExecution.java

**Status:** ADDED  
**Main type:** `ScopedExecution`  
**Slice / area:** Slice 4 Scoped execution  
**Why it matters for review:** Exactly-once exception policy  
**Lines:** 59

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Internal helper for builder/SpecifiedSpan terminal methods (Slice 4).
 */
final class ScopedExecution {

    private ScopedExecution() {
    }

    static void run(@Nonnull Supplier<SpanHandle> handleSupplier, @Nonnull Runnable action) {
        Objects.requireNonNull(handleSupplier, "handleSupplier");
        Objects.requireNonNull(action, "action");
        try (SpanHandle handle = handleSupplier.get()) {
            try {
                action.run();
            } catch (RuntimeException e) {
                handle.recordException(e);
                throw e;
            }
        }
    }

    @Nonnull
    static <T> T call(@Nonnull Supplier<SpanHandle> handleSupplier, @Nonnull Supplier<T> supplier) {
        Objects.requireNonNull(handleSupplier, "handleSupplier");
        Objects.requireNonNull(supplier, "supplier");
        try (SpanHandle handle = handleSupplier.get()) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                handle.recordException(e);
                throw e;
            }
        }
    }

    @Nonnull
    static <T> T callChecked(@Nonnull Supplier<SpanHandle> handleSupplier,
                               @Nonnull ThrowingSupplier<T> supplier) throws Exception {
        Objects.requireNonNull(handleSupplier, "handleSupplier");
        Objects.requireNonNull(supplier, "supplier");
        try (SpanHandle handle = handleSupplier.get()) {
            try {
                return supplier.get();
            } catch (Exception e) {
                handle.recordException(e);
                throw e;
            }
        }
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/SpanHandleImpl.java

**Status:** ADDED  
**Main type:** `SpanHandleImpl`  
**Slice / area:** Slice 4 Scoped execution  
**Why it matters for review:** SpanHandle wrapper  
**Lines:** 46

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * {@link SpanHandle} backed by {@link SpanScope} with exactly-once exception recording per
 * {@link Throwable} instance (Slice 4).
 */
public final class SpanHandleImpl implements SpanHandle {

    private final SpanScope scope;
    private final Set<Throwable> recordedThrowables =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private SpanHandleImpl(@Nonnull SpanScope scope) {
        this.scope = scope;
    }

    @Nonnull
    public static SpanHandle wrap(@Nonnull SpanScope scope) {
        return new SpanHandleImpl(scope);
    }

    @Override
    public void recordException(@Nullable Throwable throwable) {
        if (throwable == null) {
            return;
        }
        if (!recordedThrowables.add(throwable)) {
            return;
        }
        scope.recordException(throwable);
    }

    @Override
    public void close() {
        scope.close();
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/SpecifiedSpanImpl.java

**Status:** ADDED  
**Main type:** `SpecifiedSpanImpl`  
**Slice / area:** Slice 4 Scoped execution  
**Why it matters for review:** SpecifiedSpan impl  
**Lines:** 45

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpecifiedSpan;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.core.impl.TracingImplementation;

import java.util.Objects;
import java.util.function.Supplier;

final class SpecifiedSpanImpl implements SpecifiedSpan {

    private final TracingImplementation implementation;
    private final SpanSpec spec;

    SpecifiedSpanImpl(@Nonnull TracingImplementation implementation, @Nonnull SpanSpec spec) {
        this.implementation = Objects.requireNonNull(implementation, "implementation");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    @Nonnull
    public SpanHandle start() {
        return implementation.startSpan(spec);
    }

    @Override
    public void run(@Nonnull Runnable action) {
        ScopedExecution.run(this::start, action);
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull Supplier<T> supplier) {
        return ScopedExecution.call(this::start, supplier);
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception {
        return ScopedExecution.callChecked(this::start, supplier);
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/manual/StubTransportTracing.java

**Status:** ADDED  
**Main type:** `StubTransportTracing`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** Transport stub  
**Lines:** 190

```java
package space.br1440.platform.tracing.core.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.manual.DatabaseSpanBuilder;
import space.br1440.platform.tracing.api.manual.DatabaseTracing;
import space.br1440.platform.tracing.api.manual.HttpClientSpanBuilder;
import space.br1440.platform.tracing.api.manual.HttpServerSpanBuilder;
import space.br1440.platform.tracing.api.manual.HttpTracing;
import space.br1440.platform.tracing.api.manual.KafkaConsumerSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaProducerSpanBuilder;
import space.br1440.platform.tracing.api.manual.KafkaTracing;
import space.br1440.platform.tracing.api.manual.RpcClientSpanBuilder;
import space.br1440.platform.tracing.api.manual.RpcServerSpanBuilder;
import space.br1440.platform.tracing.api.manual.RpcTracing;
import space.br1440.platform.tracing.api.manual.TransportTracing;

/**
 * Slice 1A/1B skeleton transport grouping; semantic builders arrive in Slice 3Aâ€“3C.
 */
final class StubTransportTracing implements TransportTracing {

    static final StubTransportTracing INSTANCE = new StubTransportTracing();

    private StubTransportTracing() {
    }

    @Override
    @Nonnull
    public HttpTracing http() {
        return StubHttpTracing.INSTANCE;
    }

    @Override
    @Nonnull
    public DatabaseTracing database() {
        return StubDatabaseTracing.INSTANCE;
    }

    @Override
    @Nonnull
    public RpcTracing rpc() {
        return StubRpcTracing.INSTANCE;
    }

    @Override
    @Nonnull
    public KafkaTracing kafka() {
        return StubKafkaTracing.INSTANCE;
    }
}

final class StubHttpTracing implements HttpTracing {
    static final StubHttpTracing INSTANCE = new StubHttpTracing();

    private StubHttpTracing() {
    }

    @Override
    @Nonnull
    public HttpServerSpanBuilder server() {
        throw new UnsupportedOperationException("HTTP server builder is unavailable in noop transport stub");
    }

    @Override
    @Nonnull
    public HttpClientSpanBuilder client() {
        throw new UnsupportedOperationException("HTTP client builder is unavailable in noop transport stub");
    }
}

final class StubDatabaseTracing implements DatabaseTracing {
    static final StubDatabaseTracing INSTANCE = new StubDatabaseTracing();

    private StubDatabaseTracing() {
    }

    private UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("Database builder is unavailable in noop transport stub");
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder system(@Nonnull String dbSystem) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder operation(@Nonnull String operation) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder collection(@Nonnull String collection) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder child() {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder root() {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder detached() {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder linkedTo(@Nonnull space.br1440.platform.tracing.api.span.SpanLinkContext... linkContexts) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public DatabaseSpanBuilder fromRemoteContext(@Nonnull String... traceparents) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public space.br1440.platform.tracing.api.span.spec.SpanHandle start() {
        throw unavailable();
    }

    @Override
    public void run(@Nonnull Runnable action) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public <T> T call(@Nonnull java.util.function.Supplier<T> supplier) {
        throw unavailable();
    }

    @Override
    @Nonnull
    public <T> T callChecked(@Nonnull space.br1440.platform.tracing.api.util.ThrowingSupplier<T> supplier)
            throws Exception {
        throw unavailable();
    }
}

final class StubRpcTracing implements RpcTracing {
    static final StubRpcTracing INSTANCE = new StubRpcTracing();

    private StubRpcTracing() {
    }

    @Override
    @Nonnull
    public RpcServerSpanBuilder server() {
        throw new UnsupportedOperationException("RPC server builder is unavailable in noop transport stub");
    }

    @Override
    @Nonnull
    public RpcClientSpanBuilder client() {
        throw new UnsupportedOperationException("RPC client builder is unavailable in noop transport stub");
    }
}

final class StubKafkaTracing implements KafkaTracing {
    static final StubKafkaTracing INSTANCE = new StubKafkaTracing();

    private StubKafkaTracing() {
    }

    @Override
    @Nonnull
    public KafkaProducerSpanBuilder producer() {
        throw new UnsupportedOperationException("Kafka producer builder is unavailable in noop transport stub");
    }

    @Override
    @Nonnull
    public KafkaConsumerSpanBuilder consumer() {
        throw new UnsupportedOperationException("Kafka consumer builder is unavailable in noop transport stub");
    }
}
```

## platform-tracing-core — platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/NoOpPlatformTracing.java

**Status:** MODIFIED  
**Main type:** `NoOpPlatformTracing`  
**Slice / area:** Slice 1B cutover  
**Why it matters for review:** No-op facade  
**Lines:** 44

```java
package space.br1440.platform.tracing.core;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.manual.DefaultManualTracing;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

public final class NoOpPlatformTracing implements PlatformTracing {

    public static final NoOpPlatformTracing INSTANCE = new NoOpPlatformTracing(NoOpTracingImplementation.noop());

    private final TracingImplementation implementation;
    private final ManualTracing manualTracing;

    private NoOpPlatformTracing(@Nonnull TracingImplementation implementation) {
        this.implementation = implementation;
        this.manualTracing = new DefaultManualTracing(implementation, new AttributePolicy());
    }

    public static NoOpPlatformTracing backedBy(@Nonnull TracingImplementation implementation) {
        return new NoOpPlatformTracing(implementation);
    }

    @Override
    @Nonnull
    public TraceContextView traceContext() {
        return implementation.currentTraceContext();
    }

    @Override
    @Nonnull
    public ManualTracing manual() {
        return manualTracing;
    }

    @Nonnull
    public TracingImplementation tracingImplementation() {
        return implementation;
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/arch/TracingImplementationArchTest.java

**Status:** ADDED  
**Main type:** `TracingImplementationArchTest`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** ArchUnit SPI gate  
**Lines:** 109

```java
package space.br1440.platform.tracing.core.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import space.br1440.platform.tracing.core.impl.TracingImplementation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slice 2 architecture guardrails for {@link TracingImplementation} boundary.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.core",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class TracingImplementationArchTest {

    @ArchTest
    static final ArchRule tracingImplementationHasNoDefaultMethods =
            classes().that().implement(TracingImplementation.class)
                    .should(notHaveDefaultMethods())
                    .because("TracingImplementation SPI must be fully abstract without default methods");

    @ArchTest
    static final ArchRule tracingImplementationInterfaceHasNoBehavioralStaticHelpers =
            classes().that().areInterfaces().and().haveSimpleName("TracingImplementation")
                    .should(notDeclarePublicStaticMethods())
                    .because("TracingImplementation interface must not expose behavioral static helpers");

    @ArchTest
    static final ArchRule noAbstractTracingImplementationSkeletons =
            classes().that().implement(TracingImplementation.class)
                    .should(notBeAbstract())
                    .because("abstract partial TracingImplementation skeletons are forbidden");

    @ArchTest
    static final ArchRule manualBuildersDoNotUseOtelDirectly =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.core.manual..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api.trace..",
                            "io.opentelemetry.context..")
                    .because("public manual builders must route through TracingImplementation, not OTel API");

    @ArchTest
    static final ArchRule platformTracingFacadeDoesNotUseOtelSpanApi =
            noClasses().that().haveSimpleName("DefaultPlatformTracing")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api.trace..",
                            "io.opentelemetry.context..",
                            "io.opentelemetry.sdk..")
                    .because("PlatformTracing facade must not use OTel span API directly");

    private static ArchCondition<JavaClass> notHaveDefaultMethods() {
        return new ArchCondition<>("not declare default methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!item.isInterface()) {
                    return;
                }
                for (JavaMethod method : item.getMethods()) {
                    if (method.getOwner().isInterface()
                            && !method.getModifiers().contains(JavaModifier.ABSTRACT)
                            && !method.getModifiers().contains(JavaModifier.STATIC)
                            && !method.getModifiers().contains(JavaModifier.PRIVATE)) {
                        events.add(SimpleConditionEvent.violated(method,
                                "Default method forbidden on TracingImplementation: " + method.getFullName()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDeclarePublicStaticMethods() {
        return new ArchCondition<>("not declare public static methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    if (method.getModifiers().contains(JavaModifier.STATIC)
                            && method.getModifiers().contains(JavaModifier.PUBLIC)) {
                        events.add(SimpleConditionEvent.violated(method,
                                "Public static method forbidden on TracingImplementation impl: "
                                        + method.getFullName()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notBeAbstract() {
        return new ArchCondition<>("not be abstract") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.getModifiers().contains(JavaModifier.ABSTRACT)) {
                    events.add(SimpleConditionEvent.violated(item,
                            "Abstract TracingImplementation forbidden: " + item.getFullName()));
                }
            }
        };
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/impl/TracingImplementationRoutingTest.java

**Status:** ADDED  
**Main type:** `TracingImplementationRoutingTest`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** Single creation boundary  
**Lines:** 156

```java
package space.br1440.platform.tracing.core.impl;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 2 hard gate: all manual span paths route through {@link TracingImplementation#startSpan(SpanSpec)}.
 */
class TracingImplementationRoutingTest {

    private RecordingTracingImplementation recording;
    private DefaultPlatformTracing tracing;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingImplementation();
        tracing = new DefaultPlatformTracing(recording);
    }

    @Test
    void operationStart_routesThroughStartSpan() {
        tracing.manual().operation("x").start().close();
        assertSingleSpecNamed("x", Topology.CHILD);
    }

    @Test
    void operationRun_routesThroughStartSpan() {
        tracing.manual().operation("x").run(() -> {
        });
        assertSingleSpecNamed("x", Topology.CHILD);
    }

    @Test
    void operationCall_routesThroughStartSpan() {
        tracing.manual().operation("x").call(() -> "ok");
        assertSingleSpecNamed("x", Topology.CHILD);
    }

    @Test
    void spanFromSpecStart_routesSameSpec() {
        SpanSpec spec = governedSpec("from-spec");
        tracing.manual().spanFromSpec(spec).start().close();
        assertThat(recording.receivedSpecs()).containsExactly(spec);
    }

    @Test
    void spanFromSpecRun_routesSameSpec() {
        SpanSpec spec = governedSpec("from-spec-run");
        tracing.manual().spanFromSpec(spec).run(() -> {
        });
        assertThat(recording.receivedSpecs()).containsExactly(spec);
    }

    @Test
    void topologyRoot_preserved() {
        tracing.manual().operation("root-op").root().start().close();
        assertSingleSpecNamed("root-op", Topology.ROOT);
    }

    @Test
    void topologyDetached_preserved() {
        tracing.manual().operation("detached-op").detached().start().close();
        assertSingleSpecNamed("detached-op", Topology.DETACHED);
    }

    @Test
    void topologyRootWithLinks_preserved() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        tracing.manual().operation("linked-root").root().linkedTo(link).start().close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo("linked-root");
        assertThat(spec.options().topology()).isEqualTo(Topology.ROOT);
        assertThat(spec.options().links()).containsExactly(link);
    }

    @Test
    void spanSpecAttributesReasonReference_preserved() {
        SpanSpec spec = SpanSpec.builder("attr-spec")
                .category(SpanCategory.DATABASE)
                .root()
                .attribute("db.system", "postgresql")
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .reference("ticket-42")
                .build();
        tracing.manual().spanFromSpec(spec).start().close();
        SpanSpec received = recording.receivedSpecs().getFirst();
        assertThat(received.category()).isEqualTo(SpanCategory.DATABASE);
        assertThat(received.attributes()).containsEntry("db.system",
                space.br1440.platform.tracing.api.span.spec.SpanAttributeValue.of("postgresql"));
        assertThat(received.reason()).isEqualTo(SpanSpecReason.LEGACY_INTEGRATION);
        assertThat(received.reference()).contains("ticket-42");
    }

    @Test
    void noOpFacade_routesThroughBoundary() {
        RecordingTracingImplementation recordingImpl = new RecordingTracingImplementation();
        recordingImpl.setState(ImmutableTracingState.of(
                TracingMode.NOOP, java.util.Optional.empty(), java.util.Map.of()));
        NoOpPlatformTracing noop = NoOpPlatformTracing.backedBy(recordingImpl);
        noop.manual().operation("noop-op").run(() -> {
        });
        assertThat(recordingImpl.receivedSpecs()).hasSize(1);
        assertThat(recordingImpl.receivedSpecs().getFirst().name()).isEqualTo("noop-op");
    }

    @Test
    void killSwitchDisabled_routesThroughBoundaryWithNoRealSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        DefaultPlatformTracing realTracing = new DefaultPlatformTracing(
                OpenTelemetrySdk.builder().setTracerProvider(provider).build());
        realTracing.setFacadeEnabled(false);
        realTracing.manual().operation("disabled").start().close();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
        assertThat(realTracing.tracingImplementation().state().mode())
                .isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
    }

    private void assertSingleSpecNamed(String name, Topology topology) {
        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo(name);
        assertThat(spec.options().topology()).isEqualTo(topology);
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
    }

    private static SpanSpec governedSpec(String name) {
        return SpanSpec.builder(name)
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .build();
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/DatabaseSpanBuilderTest.java

**Status:** ADDED  
**Main type:** `DatabaseSpanBuilderTest`  
**Slice / area:** Slice 3B Database  
**Why it matters for review:** Database builder tests  
**Lines:** 127

```java
package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.core.impl.RecordingTracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.SemconvMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3B hard gate: {@code manual().transport().database()} builder behavior.
 */
class DatabaseSpanBuilderTest {

    private RecordingTracingImplementation recording;
    private ManualTracing manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingImplementation();
        AttributePolicy strictPolicy = new AttributePolicy(ValidationMode.STRICT, false, SemconvMetrics.NOOP);
        manual = new DefaultManualTracing(recording, strictPolicy);
    }

    @Test
    void validBuilder_routesSpanSpecThroughTracingImplementation() {
        manual.transport().database()
                .operation("SELECT")
                .system("postgresql")
                .collection("orders")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.DATABASE);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
        assertThat(spec.name()).isEqualTo("SELECT orders");
        assertThat(spec.attributes()).containsKey("db.operation.name");
        assertThat(spec.attributes()).containsKey("db.system.name");
        assertThat(spec.attributes()).containsKey("db.collection.name");
    }

    @Test
    void missingOperation_rejected() {
        assertThatThrownBy(() ->
                manual.transport().database()
                        .system("postgresql")
                        .collection("orders")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void missingSystem_rejected() {
        assertThatThrownBy(() ->
                manual.transport().database()
                        .operation("SELECT")
                        .collection("orders")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system");
    }

    @Test
    void missingCollection_rejected() {
        assertThatThrownBy(() ->
                manual.transport().database()
                        .operation("SELECT")
                        .system("postgresql")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection");
    }

    @Test
    void rootTopology_works() {
        manual.transport().database()
                .operation("INSERT")
                .system("postgresql")
                .collection("orders")
                .root()
                .start()
                .close();

        assertThat(recording.receivedSpecs().getFirst().options().topology())
                .isEqualTo(space.br1440.platform.tracing.api.span.spec.Topology.ROOT);
    }

    @Test
    void childWithLinks_rejected() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                manual.transport().database()
                        .operation("SELECT")
                        .system("postgresql")
                        .collection("orders")
                        .child()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    @Test
    void strictMode_missingPlatformTypeInjectedBySemanticSpecs() {
        manual.transport().database()
                .operation("SELECT")
                .system("postgresql")
                .collection("orders")
                .run(() -> {
                });
        assertThat(recording.receivedSpecs()).hasSize(1);
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/HttpSpanBuilderTest.java

**Status:** ADDED  
**Main type:** `HttpSpanBuilderTest`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** HTTP builder tests  
**Lines:** 96

```java
package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.manual.HttpTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.core.impl.RecordingTracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.SemconvMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3A hard gate: {@code manual().transport().http()} builder foundation.
 */
class HttpSpanBuilderTest {

    private RecordingTracingImplementation recording;
    private ManualTracing manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingImplementation();
        AttributePolicy strictPolicy = new AttributePolicy(ValidationMode.STRICT, false, SemconvMetrics.NOOP);
        manual = new DefaultManualTracing(recording, strictPolicy);
    }

    @Test
    void transportHttp_returnsNonNullEntryPoint() {
        HttpTracing http = manual.transport().http();
        assertThat(http).isNotNull();
        assertThat(http.server()).isNotNull();
        assertThat(http.client()).isNotNull();
    }

    @Test
    void httpServerStart_routesSpanSpecWithoutDirectOtelUse() {
        manual.transport().http().server()
                .method("GET")
                .route("/api/items")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.HTTP_SERVER);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
        assertThat(spec.name()).isEqualTo("GET /api/items");
        assertThat(spec.attributes()).containsKey("http.request.method");
        assertThat(spec.attributes()).containsKey("http.route");
    }

    @Test
    void httpClientStart_routesSpanSpecWithoutDirectOtelUse() {
        manual.transport().http().client()
                .method("POST")
                .url("https://example.com/api")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.HTTP_CLIENT);
        assertThat(spec.name()).isEqualTo("POST");
        assertThat(spec.attributes()).containsKey("http.request.method");
        assertThat(spec.attributes()).containsKey("url.full");
    }

    @Test
    void httpServerWithoutMethod_rejectedInStrictMode() {
        assertThatThrownBy(() ->
                manual.transport().http().server().start())
                .isInstanceOf(SemconvViolationException.class);
    }

    @Test
    void httpClientWithoutMethod_rejectedInStrictMode() {
        assertThatThrownBy(() ->
                manual.transport().http().client().url("https://example.com").start())
                .isInstanceOf(SemconvViolationException.class);
    }

    @Test
    void httpClientBlankUrl_rejected() {
        assertThatThrownBy(() ->
                manual.transport().http().client().method("GET").url("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/KafkaConsumerBatchLinksTest.java

**Status:** ADDED  
**Main type:** `KafkaConsumerBatchLinksTest`  
**Slice / area:** Slice 5B Links/Kafka batch  
**Why it matters for review:** Kafka batch ROOT+links  
**Lines:** 151

```java
package space.br1440.platform.tracing.core.manual;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.RemoteContext;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 5B hard gate: Kafka consumer batch ROOT+links and links-before-start semantics.
 */
class KafkaConsumerBatchLinksTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";
    private static final String TRACEPARENT_A =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";
    private static final String TRACEPARENT_B =
            "00-020406080a0c0e10121416181a1c1e20-020406080a0c0e10-01";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultPlatformTracing(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();
    }

    @Test
    void kafkaBatchRootWithLinkedTo_createsRootSpanWithRemoteLinks() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        tracing.manual().transport().kafka().consumer()
                .batch("orders")
                .root()
                .linkedTo(link)
                .start()
                .close();

        SpanData span = findSpan("orders process");
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks().getFirst().getSpanContext().isRemote()).isTrue();
    }

    @Test
    void kafkaBatchRootWithFromRemoteContext_parsesTraceparentIntoLinks() {
        tracing.manual().transport().kafka().consumer()
                .batch("orders")
                .root()
                .fromRemoteContext(TRACEPARENT_A, TRACEPARENT_B)
                .start()
                .close();

        SpanData span = findSpan("orders process");
        assertThat(span.getLinks()).hasSize(2);
        assertThat(span.getLinks())
                .extracting(LinkData::getSpanContext)
                .extracting(ctx -> ctx.getTraceId() + "/" + ctx.getSpanId())
                .containsExactly(
                        "0102030405060708090a0b0c0d0e0f10/0102030405060708",
                        "020406080a0c0e10121416181a1c1e20/020406080a0c0e10");
    }

    @Test
    void kafkaBatchRootInsideActiveParent_isNotChildSpan() {
        try (var parent = tracing.manual().operation("parent").start()) {
            tracing.manual().transport().kafka().consumer()
                    .batch("orders")
                    .root()
                    .fromRemoteContext(TRACEPARENT_A)
                    .start()
                    .close();
        }

        SpanData parentSpan = findSpan("parent");
        SpanData batchSpan = findSpan("orders process");
        assertThat(batchSpan.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(batchSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
        assertThat(batchSpan.getLinks()).hasSize(1);
    }

    @Test
    void kafkaBatchChildWithLinks_rejectedBeforeStart() {
        SpanLinkContext link = RemoteContext.requireTraceparent(TRACEPARENT_A);
        assertThatThrownBy(() ->
                tracing.manual().transport().kafka().consumer()
                        .batch("orders")
                        .child()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void kafkaBatchDetachedWithLinks_rejectedBeforeStart() {
        SpanLinkContext link = RemoteContext.requireTraceparent(TRACEPARENT_A);
        assertThatThrownBy(() ->
                tracing.manual().transport().kafka().consumer()
                        .batch("orders")
                        .detached()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void spanHandle_hasNoPostStartAddLinkApi() {
        assertThat(Arrays.stream(SpanHandle.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .noneMatch("addLink"::equals)).isTrue();
    }

    private SpanData findSpan(String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/KafkaSpanBuilderTest.java

**Status:** ADDED  
**Main type:** `KafkaSpanBuilderTest`  
**Slice / area:** Slice 3C Kafka  
**Why it matters for review:** Kafka builder tests  
**Lines:** 149

```java
package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.manual.KafkaTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.semconv.KafkaSemconvVersion;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.impl.RecordingTracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.SemconvMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3C-Kafka hard gate: {@code manual().transport().kafka()} builder foundation.
 */
class KafkaSpanBuilderTest {

    private RecordingTracingImplementation recording;
    private ManualTracing manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingImplementation();
        AttributePolicy strictPolicy = new AttributePolicy(ValidationMode.STRICT, false, SemconvMetrics.NOOP);
        manual = new DefaultManualTracing(recording, strictPolicy);
    }

    @Test
    void transportKafka_returnsNonNullEntryPoint() {
        KafkaTracing kafka = manual.transport().kafka();
        assertThat(kafka).isNotNull();
        assertThat(kafka.producer()).isNotNull();
        assertThat(kafka.consumer()).isNotNull();
    }

    @Test
    void kafkaTracing_hasSemconvVersionMarker() {
        assertThat(KafkaTracing.class.isAnnotationPresent(KafkaSemconvVersion.class)).isTrue();
        assertThat(KafkaTracing.class.getAnnotation(KafkaSemconvVersion.class).value()).isEqualTo("1.28.0");
    }

    @Test
    void kafkaProducerStart_routesSpanSpecThroughTracingImplementation() {
        manual.transport().kafka().producer()
                .destination("orders")
                .operation("publish")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_PRODUCER);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
        assertThat(spec.name()).isEqualTo("orders publish");
        assertThat(spec.attributes()).containsKey("messaging.system");
        assertThat(spec.attributes()).containsKey("messaging.destination.name");
        assertThat(spec.attributes()).containsKey("messaging.operation");
    }

    @Test
    void kafkaConsumerStart_routesSpanSpecThroughTracingImplementation() {
        manual.transport().kafka().consumer()
                .destination("orders")
                .operation("receive")
                .start()
                .close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_CONSUMER);
        assertThat(spec.name()).isEqualTo("orders receive");
    }

    @Test
    void kafkaBatchStart_routesSpanSpecWithPreconfiguredDestination() {
        manual.transport().kafka().consumer()
                .batch("orders")
                .start()
                .close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.KAFKA_CONSUMER);
        assertThat(spec.name()).isEqualTo("orders process");
    }

    @Test
    void kafkaBatchRootWithLinks_preservedForSlice5B() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        manual.transport().kafka().consumer()
                .batch("orders")
                .root()
                .linkedTo(link)
                .run(() -> {
                });

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.options().topology()).isEqualTo(Topology.ROOT);
        assertThat(spec.options().links()).containsExactly(link);
    }

    @Test
    void missingDestination_rejected() {
        assertThatThrownBy(() ->
                manual.transport().kafka().producer()
                        .operation("publish")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination");
    }

    @Test
    void missingOperation_rejected() {
        assertThatThrownBy(() ->
                manual.transport().kafka().consumer()
                        .destination("orders")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void batchBlankDestination_rejected() {
        assertThatThrownBy(() ->
                manual.transport().kafka().consumer().batch("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rootTopology_works() {
        manual.transport().kafka().producer()
                .destination("orders")
                .operation("publish")
                .root()
                .start()
                .close();

        assertThat(recording.receivedSpecs().getFirst().options().topology()).isEqualTo(Topology.ROOT);
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/OperationSpanBuilderTest.java

**Status:** ADDED  
**Main type:** `OperationSpanBuilderTest`  
**Slice / area:** Slice 3A Operation/HTTP  
**Why it matters for review:** Operation builder tests  
**Lines:** 148

```java
package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.impl.RecordingTracingImplementation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3A hard gate: {@code manual().operation(...)} builder behavior.
 */
class OperationSpanBuilderTest {

    private RecordingTracingImplementation recording;
    private DefaultPlatformTracing tracing;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingImplementation();
        tracing = new DefaultPlatformTracing(recording);
    }

    @Test
    void start_routesThroughTracingImplementation() {
        tracing.manual().operation("checkout").start().close();
        assertSingleInternalSpec("checkout", Topology.CHILD);
    }

    @Test
    void run_startsAndClosesSpan() {
        tracing.manual().operation("run-op").run(() -> {
        });
        assertSingleInternalSpec("run-op", Topology.CHILD);
    }

    @Test
    void call_returnsValueAndClosesSpan() {
        String result = tracing.manual().operation("call-op").call(() -> "ok");
        assertThat(result).isEqualTo("ok");
        assertSingleInternalSpec("call-op", Topology.CHILD);
    }

    @Test
    void callChecked_propagatesCheckedExceptionAndClosesSpan() throws Exception {
        assertThatThrownBy(() ->
                tracing.manual().operation("checked-op").callChecked(() -> {
                    throw new Exception("checked");
                }))
                .isInstanceOf(Exception.class)
                .hasMessage("checked");
        assertSingleInternalSpec("checked-op", Topology.CHILD);
    }

    @Test
    void nullName_rejected() {
        assertThatThrownBy(() -> tracing.manual().operation(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankName_rejected() {
        assertThatThrownBy(() -> tracing.manual().operation("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void operationName_appearsInSpanSpec() {
        tracing.manual().operation("my-operation").start().close();
        assertThat(recording.receivedSpecs().getFirst().name()).isEqualTo("my-operation");
    }

    @Test
    void category_isInternalWithReason() {
        tracing.manual().operation("internal-op").start().close();
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
    }

    @Test
    void rootTopology_preservedInSpanSpecOptions() {
        tracing.manual().operation("root-op").root().start().close();
        assertSingleInternalSpec("root-op", Topology.ROOT);
    }

    @Test
    void childTopology_preservedInSpanSpecOptions() {
        tracing.manual().operation("child-op").child().start().close();
        assertSingleInternalSpec("child-op", Topology.CHILD);
    }

    @Test
    void detachedTopology_preservedInSpanSpecOptions() {
        tracing.manual().operation("detached-op").detached().start().close();
        assertSingleInternalSpec("detached-op", Topology.DETACHED);
    }

    @Test
    void rootWithLinks_preserved() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        tracing.manual().operation("linked-root").root().linkedTo(link).start().close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.options().topology()).isEqualTo(Topology.ROOT);
        assertThat(spec.options().links()).containsExactly(link);
    }

    @Test
    void detachedWithLinks_rejected() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                tracing.manual().operation("bad-detached").detached().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void childWithLinks_rejected() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                tracing.manual().operation("bad-child").child().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    private void assertSingleInternalSpec(String name, Topology topology) {
        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo(name);
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
        assertThat(spec.options().topology()).isEqualTo(topology);
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/RpcSpanBuilderTest.java

**Status:** ADDED  
**Main type:** `RpcSpanBuilderTest`  
**Slice / area:** Slice 3C RPC  
**Why it matters for review:** RPC builder tests  
**Lines:** 146

```java
package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.RpcTracing;
import space.br1440.platform.tracing.api.semconv.RpcSemconvVersion;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.impl.RecordingTracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.SemconvMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3C-RPC hard gate: {@code manual().transport().rpc()} builder behavior.
 */
class RpcSpanBuilderTest {

    private RecordingTracingImplementation recording;
    private ManualTracing manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingImplementation();
        AttributePolicy strictPolicy = new AttributePolicy(ValidationMode.STRICT, false, SemconvMetrics.NOOP);
        manual = new DefaultManualTracing(recording, strictPolicy);
    }

    @Test
    void transportRpc_returnsNonNullEntryPoint() {
        RpcTracing rpc = manual.transport().rpc();
        assertThat(rpc).isNotNull();
        assertThat(rpc.server()).isNotNull();
        assertThat(rpc.client()).isNotNull();
    }

    @Test
    void rpcTracing_hasSemconvVersionMarker() {
        assertThat(RpcTracing.class.isAnnotationPresent(RpcSemconvVersion.class)).isTrue();
        assertThat(RpcTracing.class.getAnnotation(RpcSemconvVersion.class).value()).isEqualTo("1.28.0");
    }

    @Test
    void rpcServerStart_routesSpanSpecThroughTracingImplementation() {
        manual.transport().rpc().server()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.RPC_SERVER);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
        assertThat(spec.name()).isEqualTo("OrderService/CreateOrder");
        assertThat(spec.attributes()).containsKey("rpc.system");
        assertThat(spec.attributes()).containsKey("rpc.service");
        assertThat(spec.attributes()).containsKey("rpc.method");
    }

    @Test
    void rpcClientStart_routesSpanSpecThroughTracingImplementation() {
        manual.transport().rpc().client()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .serverAddress("order-service")
                .start()
                .close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.RPC_CLIENT);
        assertThat(spec.attributes()).containsKey("server.address");
    }

    @Test
    void missingSystem_rejected() {
        assertThatThrownBy(() ->
                manual.transport().rpc().server()
                        .service("OrderService")
                        .method("CreateOrder")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system");
    }

    @Test
    void missingService_rejected() {
        assertThatThrownBy(() ->
                manual.transport().rpc().client()
                        .system("grpc")
                        .method("CreateOrder")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service");
    }

    @Test
    void missingMethod_rejectedInStrictMode() {
        assertThatThrownBy(() ->
                manual.transport().rpc().server()
                        .system("grpc")
                        .service("OrderService")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("method");
    }

    @Test
    void rootTopology_works() {
        manual.transport().rpc().server()
                .system("grpc")
                .service("OrderService")
                .method("CreateOrder")
                .root()
                .start()
                .close();

        assertThat(recording.receivedSpecs().getFirst().options().topology()).isEqualTo(Topology.ROOT);
    }

    @Test
    void childWithLinks_rejected() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                manual.transport().rpc().server()
                        .system("grpc")
                        .service("OrderService")
                        .method("CreateOrder")
                        .child()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/ScopedExecutionTest.java

**Status:** ADDED  
**Main type:** `ScopedExecutionTest`  
**Slice / area:** Slice 4 Scoped execution  
**Why it matters for review:** Exception policy tests  
**Lines:** 180

```java
package space.br1440.platform.tracing.core.manual;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.impl.RecordingTracingImplementation;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 4 hard gate: scoped terminal methods and exactly-once exception recording.
 */
class ScopedExecutionTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing tracing;
    private RecordingTracingImplementation recording;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultPlatformTracing(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        recording = new RecordingTracingImplementation();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void run_closesSpanOnSuccess() {
        tracing.manual().operation("success").run(() -> {
        });
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().getFirst().getName()).isEqualTo("success");
    }

    @Test
    void run_recordsExceptionExactlyOnceAndRethrows() {
        IllegalStateException error = new IllegalStateException("boom");
        assertThatThrownBy(() ->
                tracing.manual().operation("failed").run(() -> {
                    throw error;
                }))
                .isSameAs(error);

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().getFirst().getName()).isEqualTo("exception");
    }

    @Test
    void call_returnsValueAndClosesSpan() {
        String value = tracing.manual().operation("call-op").call(() -> "ok");
        assertThat(value).isEqualTo("ok");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void call_recordsRuntimeExceptionExactlyOnceAndRethrows() {
        RuntimeException error = new RuntimeException("call-fail");
        assertThatThrownBy(() ->
                tracing.manual().operation("call-fail").call(() -> {
                    throw error;
                }))
                .isSameAs(error);

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void callChecked_propagatesCheckedExceptionAndRecordsExactlyOnce() throws Exception {
        Exception error = new Exception("checked");
        assertThatThrownBy(() ->
                tracing.manual().operation("checked").callChecked(() -> {
                    throw error;
                }))
                .isSameAs(error);

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void explicitRecordException_isAllowed() {
        IllegalArgumentException error = new IllegalArgumentException("explicit");
        try (SpanHandle handle = tracing.manual().operation("explicit").start()) {
            handle.recordException(error);
        }

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void duplicateSameThrowable_notDoubleRecorded() {
        IllegalStateException error = new IllegalStateException("dup");
        try (SpanHandle handle = tracing.manual().operation("dup").start()) {
            handle.recordException(error);
            handle.recordException(error);
        }

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void scopedPath_duplicateSameThrowable_notDoubleRecorded() {
        IllegalStateException error = new IllegalStateException("scoped-dup");
        SpanHandle[] holder = new SpanHandle[1];
        assertThatThrownBy(() ->
                ScopedExecution.run(() -> {
                    SpanHandle handle = tracing.manual().operation("scoped-dup").start();
                    holder[0] = handle;
                    return handle;
                }, () -> {
                    holder[0].recordException(error);
                    throw error;
                }))
                .isSameAs(error);

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void twoDifferentThrowables_bothRecorded() {
        RuntimeException first = new RuntimeException("first");
        IllegalStateException second = new IllegalStateException("second");
        try (SpanHandle handle = tracing.manual().operation("two-errors").start()) {
            handle.recordException(first);
            handle.recordException(second);
        }

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(2);
    }

    @Test
    void nestedSpans_closeInLifoOrder() {
        tracing.manual().operation("outer").run(() ->
                tracing.manual().operation("inner").run(() -> {
                }));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).getName()).isEqualTo("inner");
        assertThat(spans.get(1).getName()).isEqualTo("outer");
    }

    @Test
    void terminalMethods_routeThroughTracingImplementation() {
        DefaultPlatformTracing recordingTracing = new DefaultPlatformTracing(recording);
        recordingTracing.manual().operation("routed").run(() -> {
        });
        assertThat(recording.receivedSpecs()).hasSize(1);
        assertThat(recording.receivedSpecs().getFirst().name()).isEqualTo("routed");
    }

    @Test
    void platformTracing_hasNoPublicExecuteMethod() {
        assertThat(Arrays.stream(PlatformTracing.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .noneMatch("execute"::equals))
                .isTrue();
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/SpanOptionsTopologyTest.java

**Status:** ADDED  
**Main type:** `SpanOptionsTopologyTest`  
**Slice / area:** Slice 5A Topology  
**Why it matters for review:** Topology matrix tests  
**Lines:** 174

```java
package space.br1440.platform.tracing.core.manual;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 5A hard gate: {@link space.br1440.platform.tracing.api.span.spec.SpanTopologySpec} runtime topology.
 */
class SpanOptionsTopologyTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultPlatformTracing(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
        assertThat(tracing.traceContext().traceId()).isEmpty();
        assertThat(tracing.traceContext().spanId()).isEmpty();
    }

    @Test
    void childInsideActiveParent_inheritsParentSpanIdAndTraceId() {
        try (var parent = tracing.manual().operation("parent").start()) {
            try (var child = tracing.manual().operation("child").child().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        SpanData parentSpan = findSpan("parent");
        SpanData childSpan = findSpan("child");
        assertThat(childSpan.getTraceId()).isEqualTo(parentSpan.getTraceId());
        assertThat(childSpan.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
    }

    @Test
    void rootInsideActiveParent_hasInvalidParentAndIndependentTraceId() {
        try (var parent = tracing.manual().operation("parent").start()) {
            try (var root = tracing.manual().operation("job").root().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        SpanData parentSpan = findSpan("parent");
        SpanData rootSpan = findSpan("job");
        assertThat(rootSpan.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(rootSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
    }

    @Test
    void detachedInsideActiveParent_hasInvalidParentIndependentTraceIdAndNoLinks() {
        try (var parent = tracing.manual().operation("parent").start()) {
            try (var detached = tracing.manual().operation("orphan").detached().start()) {
                assertThat(tracing.traceContext().traceId()).isPresent();
            }
        }

        SpanData parentSpan = findSpan("parent");
        SpanData detachedSpan = findSpan("orphan");
        assertThat(detachedSpan.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(detachedSpan.getTraceId()).isNotEqualTo(parentSpan.getTraceId());
        assertThat(detachedSpan.getLinks()).isEmpty();
    }

    @Test
    void rootWithLinks_producesRootSpanWithRemoteLinks() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0000000000000000000000000000000a", "0000000000000001");
        tracing.manual().operation("linked-root").root().linkedTo(link).start().close();

        SpanData span = findSpan("linked-root");
        assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks().getFirst().getSpanContext().isRemote()).isTrue();
    }

    @Test
    void detachedWithLinks_failsBeforeSpanStart() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() ->
                tracing.manual().operation("bad-detached").detached().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void childWithLinks_failsBeforeSpanStart() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() ->
                tracing.manual().operation("bad-child").child().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void linkedToThenRoot_isValid() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        tracing.manual().operation("order-root").linkedTo(link).root().start().close();

        SpanData span = findSpan("order-root");
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks())
                .extracting(LinkData::getSpanContext)
                .extracting(ctx -> ctx.getTraceId() + "/" + ctx.getSpanId())
                .containsExactly("0102030405060708090a0b0c0d0e0f10/0102030405060708");
    }

    @Test
    void linkedToThenDetached_isInvalid() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() ->
                tracing.manual().operation("bad-order").linkedTo(link).detached().start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void detachedWithLinks_rejectedBeforeSpanStartAtSpecBuild() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() -> SpanSpec.builder("invalid-detached")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .detached()
                .linkedTo(link)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    private SpanData findSpan(String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
```

## platform-tracing-core — platform-tracing-core/src/test/java/space/br1440/platform/tracing/core/manual/SpecifiedSpanTest.java

**Status:** ADDED  
**Main type:** `SpecifiedSpanTest`  
**Slice / area:** Slice 4 Scoped execution  
**Why it matters for review:** SpecifiedSpan tests  
**Lines:** 106

```java
package space.br1440.platform.tracing.core.manual;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.impl.RecordingTracingImplementation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 4 hard gate: {@code manual().spanFromSpec(...)} terminal lifecycle.
 */
class SpecifiedSpanTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing tracing;
    private RecordingTracingImplementation recording;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultPlatformTracing(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        recording = new RecordingTracingImplementation();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void run_closesSpanOnSuccess() {
        SpanSpec spec = governedSpec("spec-run");
        tracing.manual().spanFromSpec(spec).run(() -> {
        });
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().getFirst().getName()).isEqualTo("spec-run");
    }

    @Test
    void run_recordsExceptionExactlyOnceAndRethrows() {
        SpanSpec spec = governedSpec("spec-fail");
        IllegalStateException error = new IllegalStateException("spec-boom");
        assertThatThrownBy(() ->
                tracing.manual().spanFromSpec(spec).run(() -> {
                    throw error;
                }))
                .isSameAs(error);

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getEvents()).hasSize(1);
    }

    @Test
    void call_returnsValueAndClosesSpan() {
        SpanSpec spec = governedSpec("spec-call");
        String value = tracing.manual().spanFromSpec(spec).call(() -> "value");
        assertThat(value).isEqualTo("value");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void callChecked_propagatesCheckedExceptionAndRecordsExactlyOnce() throws Exception {
        SpanSpec spec = governedSpec("spec-checked");
        Exception error = new Exception("checked-spec");
        assertThatThrownBy(() ->
                tracing.manual().spanFromSpec(spec).callChecked(() -> {
                    throw error;
                }))
                .isSameAs(error);

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void spanFromSpec_routesSameSpecThroughTracingImplementation() {
        SpanSpec spec = governedSpec("from-spec");
        DefaultPlatformTracing recordingTracing = new DefaultPlatformTracing(recording);
        recordingTracing.manual().spanFromSpec(spec).run(() -> {
        });
        assertThat(recording.receivedSpecs()).containsExactly(spec);
    }

    private static SpanSpec governedSpec(String name) {
        return SpanSpec.builder(name)
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .build();
    }
}
```

## Sources by module: platform-tracing-spring-boot-autoconfigure


## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/actuator/TracingActuatorEndpoint.java

**Status:** MODIFIED  
**Main type:** `TracingActuatorEndpoint`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** manualTracing diagnostics section  
**Lines:** 341

```java
package space.br1440.platform.tracing.autoconfigure.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxOperationException;
import space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator-ÑÐ½Ð´Ð¿Ð¾Ð¸Ð½Ñ‚ {@code /actuator/tracing}.
 * <p>
 * ÐŸÑ€ÐµÐ´Ð¾ÑÑ‚Ð°Ð²Ð»ÑÐµÑ‚ Ð´Ð¸Ð°Ð³Ð½Ð¾ÑÑ‚Ð¸Ñ‡ÐµÑÐºÐ¸Ð¹ ÑÑ€ÐµÐ· Ñ‚ÐµÐºÑƒÑ‰ÐµÐ³Ð¾ ÑÐ¾ÑÑ‚Ð¾ÑÐ½Ð¸Ñ Ð¿Ð»Ð°Ñ‚Ñ„Ð¾Ñ€Ð¼ÐµÐ½Ð½Ð¾Ð¹ Ñ‚Ñ€Ð°ÑÑÐ¸Ñ€Ð¾Ð²ÐºÐ¸
 * ({@link #tracing()}, {@code GET /actuator/tracing}) Ð¸ Ð´Ð¸Ð½Ð°Ð¼Ð¸Ñ‡ÐµÑÐºÐ¾Ðµ ÑƒÐ¿Ñ€Ð°Ð²Ð»ÐµÐ½Ð¸Ðµ Ð¿Ð°Ñ€Ð°Ð¼ÐµÑ‚Ñ€Ð°Ð¼Ð¸
 * ({@code POST /actuator/tracing/{property}/{value}}).
 */
@Endpoint(id = "tracing")
public class TracingActuatorEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TracingActuatorEndpoint.class);

    private final PlatformTracing platformTracing;
    private final TracingProperties properties;
    private final PlatformTracingJmxClient jmxClient;
    private final ManualTracingDiagnostics manualTracingDiagnostics;
    private final OtelEffectiveConfigSnapshot otelEffectiveSnapshot;
    private final ResourceEffectiveSnapshot resourceEffectiveSnapshot;

    private SdkModeDiagnostics sdkModeDiagnostics;

    public TracingActuatorEndpoint(PlatformTracing platformTracing,
                                   TracingProperties properties,
                                   PlatformTracingJmxClient jmxClient,
                                   ManualTracingDiagnostics manualTracingDiagnostics) {
        this(platformTracing, properties, jmxClient, null, manualTracingDiagnostics,
                new OtelEffectiveConfigSnapshot(), new ResourceEffectiveSnapshot());
    }

    public TracingActuatorEndpoint(PlatformTracing platformTracing,
                                   TracingProperties properties,
                                   PlatformTracingJmxClient jmxClient,
                                   SdkModeDiagnostics sdkModeDiagnostics,
                                   ManualTracingDiagnostics manualTracingDiagnostics) {
        this(platformTracing, properties, jmxClient, sdkModeDiagnostics, manualTracingDiagnostics,
                new OtelEffectiveConfigSnapshot(), new ResourceEffectiveSnapshot());
    }

    TracingActuatorEndpoint(PlatformTracing platformTracing,
                            TracingProperties properties,
                            PlatformTracingJmxClient jmxClient,
                            OtelEffectiveConfigSnapshot otelEffectiveSnapshot,
                            ResourceEffectiveSnapshot resourceEffectiveSnapshot,
                            ManualTracingDiagnostics manualTracingDiagnostics) {
        this(platformTracing, properties, jmxClient, null, manualTracingDiagnostics,
                otelEffectiveSnapshot, resourceEffectiveSnapshot);
    }

    TracingActuatorEndpoint(PlatformTracing platformTracing,
                            TracingProperties properties,
                            PlatformTracingJmxClient jmxClient,
                            SdkModeDiagnostics sdkModeDiagnostics,
                            ManualTracingDiagnostics manualTracingDiagnostics,
                            OtelEffectiveConfigSnapshot otelEffectiveSnapshot,
                            ResourceEffectiveSnapshot resourceEffectiveSnapshot) {
        this.platformTracing = platformTracing;
        this.properties = properties;
        this.jmxClient = jmxClient;
        this.manualTracingDiagnostics = manualTracingDiagnostics;
        this.sdkModeDiagnostics = sdkModeDiagnostics;
        this.otelEffectiveSnapshot = otelEffectiveSnapshot;
        this.resourceEffectiveSnapshot = resourceEffectiveSnapshot;
    }

    @ReadOperation
    public Map<String, Object> tracing() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("enabled", properties.isEnabled());
        info.put("implementation", platformTracing.getClass().getName());
        info.put("manualTracing", manualTracingDiagnostics.toActuatorMap());

        Map<String, Object> sdkInfo = new LinkedHashMap<>();
        sdkInfo.put("mode", sdkModeDiagnostics != null
                ? sdkModeDiagnostics.mode().name()
                : properties.getSdk().getMode().name());
        sdkInfo.put("configuredMode", properties.getSdk().getMode().name());
        sdkInfo.put("agentDetected", sdkModeDiagnostics != null
                ? sdkModeDiagnostics.agentDetected()
                : OtelAgentDetector.isAgentPresent());
        info.put("sdk", sdkInfo);
        info.put("currentTraceId", platformTracing.traceContext().traceId().orElse(null));
        info.put("currentSpanId", platformTracing.traceContext().spanId().orElse(null));
        info.put("service", Map.of(
                "name", nullSafe(properties.getService().getName()),
                "version", nullSafe(properties.getService().getVersion()),
                "environment", nullSafe(properties.getService().getEnvironment()),
                "cGroup", nullSafe(properties.getService().getCGroup())
        ));

        Map<String, Object> samplingInfo = new LinkedHashMap<>();
        samplingInfo.put("enabled", properties.getSampling().isEnabled());
        samplingInfo.put("ratio", properties.getSampling().getRatio());
        samplingInfo.put("routeRatios", properties.getSampling().getRouteRatios());
        samplingInfo.put("liveRatio", jmxClient.getCurrentRatio().orElse(null));
        samplingInfo.put("samplerEnabled", jmxClient.isSamplerEnabled().orElse(null));
        samplingInfo.put("liveSamplerEnabled", jmxClient.isSamplerEnabled().orElse(null));
        samplingInfo.put("liveDropPaths", jmxClient.getLiveDropPathPrefixes().orElse(null));
        samplingInfo.put("liveForceRecordHeaderValues", jmxClient.getLiveForceRecordValues().orElse(null));
        samplingInfo.put("liveRouteRatios", jmxClient.getLiveRouteRatios().orElse(null));
        samplingInfo.put("controlAvailable", jmxClient.isAvailable());
        samplingInfo.put("forceRecordHeader", properties.getSampling().getForceRecordHeader());
        samplingInfo.put("forceRecordHeaderValues", properties.getSampling().getForceRecordHeaderValues());
        samplingInfo.put("qaForceHeader", properties.getSampling().getQaForceHeader());
        samplingInfo.put("dropPaths", properties.getSampling().getDropPaths());
        samplingInfo.put("configVersion", jmxClient.getSamplingConfigVersion().orElse(null));
        samplingInfo.put("configSource", jmxClient.getSamplingConfigLastUpdatedSource().orElse(null));
        samplingInfo.put("invalidConfigCount", jmxClient.getInvalidConfigCount().orElse(null));

        Map<String, Object> decisions = new LinkedHashMap<>(jmxClient.getSamplerDecisionCounts());
        samplingInfo.put("decisions", decisions);
        info.put("sampling", samplingInfo);

        info.put("limits", Map.of(
                "maxAttributes", properties.getLimits().getMaxAttributes(),
                "maxAttributeValueLength", properties.getLimits().getMaxAttributeValueLength(),
                "maxEvents", properties.getLimits().getMaxEvents(),
                "spanTimeout", properties.getLimits().getSpanTimeout().toString(),
                "traceTimeout", properties.getLimits().getTraceTimeout().toString()
        ));
        info.put("queue", Map.of(
                "maxSize", properties.getQueue().getMaxSize(),
                "policy", properties.getQueue().getPolicy().name(),
                "exportBatchSize", properties.getQueue().getExportBatchSize(),
                "exportTimeout", properties.getQueue().getExportTimeout().toString()
        ));
        info.put("exporter", Map.of(
                "otlpEndpoint", properties.getExporter().getOtlp().getEndpoint(),
                "retryEnabled", properties.getExporter().getOtlp().getRetry().isEnabled()
        ));
        info.put("export", jmxClient.getExportMetrics()
                .orElseGet(() -> Map.of("status", "not_ready")));
        info.put("response", Map.of(
                "exposeRequestIdHeader", properties.getResponse().isExposeRequestIdHeader(),
                "headerName", properties.getResponse().getHeaderName()
        ));
        info.put("enriching", Map.of(
                "enabled", properties.getEnriching().isEnabled(),
                "remoteServicePriority", properties.getEnriching().getRemoteServicePriority()
        ));

        Map<String, Object> scrubbingInfo = new LinkedHashMap<>();
        scrubbingInfo.put("enabled", properties.getScrubbing().isEnabled());
        scrubbingInfo.put("builtInRules", properties.getScrubbing().getBuiltInRules());
        scrubbingInfo.put("liveEnabled", jmxClient.isScrubbingEnabled().orElse(null));
        scrubbingInfo.put("configVersion", jmxClient.getScrubbingConfigVersion().orElse(null));
        scrubbingInfo.put("configSource", jmxClient.getScrubbingConfigLastUpdatedSource().orElse(null));
        Map<String, Long> scrubbingMetrics = jmxClient.getScrubbingMetrics();
        scrubbingInfo.put("liveRuleCount", scrubbingMetrics.isEmpty() ? null : scrubbingMetrics.get("rules.loaded"));
        scrubbingInfo.put("customRulesSource", "otel-agent-spi");
        scrubbingInfo.put("customRulesVisible", false);
        scrubbingInfo.put("note", "SPI-Ñ€ÐµÐ°Ð»Ð¸Ð·Ð°Ñ†Ð¸Ð¸ SensitiveDataRule Ð³Ñ€ÑƒÐ·ÑÑ‚ÑÑ classloader'Ð¾Ð¼ OTel Agent Ð¸ Ð² actuator Ð½Ðµ Ð²Ð¸Ð´Ð½Ñ‹");
        scrubbingInfo.put("rulesConfig", nullSafe(properties.getScrubbing().getRulesConfig()));
        info.put("scrubbing", scrubbingInfo);

        Map<String, Object> validationInfo = new LinkedHashMap<>();
        validationInfo.put("enabled", properties.getValidation().isEnabled());
        validationInfo.put("strict", properties.getValidation().isStrict());
        validationInfo.put("strictRuntimeAllowed", properties.getValidation().isStrictRuntimeAllowed());
        validationInfo.put("liveEnabled", jmxClient.isValidationEnabled().orElse(null));
        validationInfo.put("liveStrict", jmxClient.isValidationStrict().orElse(null));
        validationInfo.put("liveStrictRuntimeAllowed", jmxClient.isValidationStrictRuntimeAllowed().orElse(null));
        validationInfo.put("configVersion", jmxClient.getValidationConfigVersion().orElse(null));
        validationInfo.put("configSource", jmxClient.getValidationConfigLastUpdatedSource().orElse(null));
        validationInfo.put("strictRuntimeAllowedNote",
                "configured strictRuntimeAllowed is Spring input; liveStrictRuntimeAllowed is agent startup "
                        + "enforcement from platform.tracing.validation.strict-runtime-allowed");
        info.put("validation", validationInfo);

        info.put("watchdog", Map.of(
                "enabled", properties.getWatchdog().isEnabled(),
                "scanInterval", properties.getWatchdog().getScanInterval().toString(),
                "spanTimeout", properties.getLimits().getSpanTimeout().toString(),
                "traceTimeout", properties.getLimits().getTraceTimeout().toString()
        ));
        info.put("otelEffective", otelEffectiveSnapshot.build());
        info.put("otelEnvHints", OtelEnvHintsBuilder.from(properties));
        info.put("resourceEffective", resourceEffectiveSnapshot.build());

        Map<String, Object> resourceSpringConfig = new LinkedHashMap<>();
        resourceSpringConfig.put("serviceName", nullSafe(properties.getService().getName()));
        resourceSpringConfig.put("serviceVersion", nullSafe(properties.getService().getVersion()));
        resourceSpringConfig.put("environment", nullSafe(properties.getService().getEnvironment()));
        resourceSpringConfig.put("cGroup", nullSafe(properties.getService().getCGroup()));
        resourceSpringConfig.put("policyVersion", nullSafe(properties.getResource().getPolicyVersion()));
        resourceSpringConfig.put("normalizeEnvironment", properties.getResource().isNormalizeEnvironment());
        resourceSpringConfig.put("validationMode", properties.getResource().getValidationMode());
        resourceSpringConfig.put("detectContainerId", properties.getResource().isDetectContainerId());
        info.put("resourceSpringConfig", resourceSpringConfig);

        Map<String, Object> processorInfo = new LinkedHashMap<>();
        processorInfo.put("errorsTotal", jmxClient.getProcessorErrorsTotal().orElse(null));
        processorInfo.put("errorsByName", jmxClient.getProcessorErrorsByName());
        info.put("processors", processorInfo);

        Map<String, Object> configInfo = new LinkedHashMap<>();
        Map<String, Long> reload = jmxClient.getConfigReloadMetrics();
        configInfo.put("updatesApplied", reload.get("updates.applied"));
        configInfo.put("updatesRejected", reload.get("updates.rejected"));
        configInfo.put("lastUpdateEpochMs", reload.get("last_update.epoch_ms"));
        configInfo.put("lastUpdatedSource", jmxClient.getSamplingConfigLastUpdatedSource().orElse(null));
        configInfo.put("auditTrail", jmxClient.getConfigAuditTrail());
        info.put("config", configInfo);

        info.put("actuator", Map.of(
                "mutationEnabled", properties.getActuator().isMutationEnabled()
        ));
        return info;
    }

    @WriteOperation
    public Map<String, Object> updateTracing(@Selector String property, @Selector String value) {
        assertMutationAllowed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("property", property);
        result.put("requestedValue", value);

        switch (property) {
            case "enabled" -> {
                boolean enabled = Boolean.parseBoolean(value);
                boolean previous = properties.isEnabled();
                properties.setEnabled(enabled);
                log.info("ÐŸÐ»Ð°Ñ‚Ñ„Ð¾Ñ€Ð¼ÐµÐ½Ð½Ð°Ñ Ñ‚Ñ€Ð°ÑÑÐ¸Ñ€Ð¾Ð²ÐºÐ° {} -> {} Ñ‡ÐµÑ€ÐµÐ· actuator endpoint", previous, enabled);
                result.put("previousValue", previous);
                result.put("appliedValue", enabled);
            }
            case "samplerEnabled" -> {
                boolean enabled = Boolean.parseBoolean(value);
                try {
                    jmxClient.setSamplerEnabled(enabled);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                result.put("status", "updated");
                result.put("effectiveSamplerEnabled", enabled);
            }
            case "samplingRatio" -> {
                double ratio;
                try {
                    ratio = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "samplingRatio must be a number, got: " + value,
                            e);
                }
                if (ratio < 0.0 || ratio > 1.0) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "samplingRatio must be in [0.0, 1.0], got: " + ratio);
                }
                Double previousLive = jmxClient.getCurrentRatio().orElse(null);
                try {
                    jmxClient.setRatio(ratio);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                double previousProperties = properties.getSampling().getRatio();
                properties.getSampling().setRatio(ratio);
                log.info("samplingRatio {} -> {} Ñ‡ÐµÑ€ÐµÐ· actuator endpoint (JMX)",
                        previousLive != null ? previousLive : previousProperties, ratio);
                result.put("previousLiveValue", previousLive);
                result.put("previousConfiguredValue", previousProperties);
                result.put("appliedValue", ratio);
            }
            case "exportEnabled" -> {
                boolean enabled = Boolean.parseBoolean(value);
                try {
                    jmxClient.setExportEnabled(enabled);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                properties.getExporter().setEnabled(enabled);
                log.info("Export-gate -> {} Ñ‡ÐµÑ€ÐµÐ· actuator endpoint (JMX)", enabled);
                result.put("status", "updated");
                result.put("appliedValue", enabled);
            }
            case "propagationEnabled" -> {
                boolean enabled = Boolean.parseBoolean(value);
                try {
                    jmxClient.setPropagationEnabled(enabled);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                properties.getPropagation().setEnabled(enabled);
                log.info("Platform propagation -> {} Ñ‡ÐµÑ€ÐµÐ· actuator endpoint (JMX)", enabled);
                result.put("status", "updated");
                result.put("appliedValue", enabled);
            }
            case "logLevel" -> {
                try {
                    jmxClient.setPlatformLogLevel(value);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                properties.getDiagnostics().setLogLevel(value);
                log.info("Platform log level -> {} Ñ‡ÐµÑ€ÐµÐ· actuator endpoint (JMX)", value);
                result.put("status", "updated");
                result.put("appliedValue", value);
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported tracing property: " + property
                            + ". Supported: enabled, samplerEnabled, samplingRatio, exportEnabled, propagationEnabled, logLevel");
        }
        return result;
    }

    private void assertMutationAllowed() {
        if (!properties.getActuator().isMutationEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Actuator tracing mutation is disabled. Set platform.tracing.actuator.mutation-enabled=true "
                            + "for local/dev/debug/test/pre-prod only. Direct JMX access is not affected.");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/diagnostics/ManualTracingDiagnostics.java

**Status:** ADDED  
**Main type:** `ManualTracingDiagnostics`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** Actuator diagnostics service  
**Lines:** 32

```java
package space.br1440.platform.tracing.autoconfigure.diagnostics;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.core.impl.TracingImplementation;

import java.util.Map;
import java.util.Objects;

/**
 * Actuator-facing diagnostics for manual platform tracing (Slice 7).
 * <p>
 * Distinct from {@code platform-tracing-otel-extension} safety counters named
 * {@code TracingDiagnostics}.
 */
public final class ManualTracingDiagnostics {

    private final TracingImplementation tracingImplementation;

    public ManualTracingDiagnostics(@Nonnull TracingImplementation tracingImplementation) {
        this.tracingImplementation = Objects.requireNonNull(tracingImplementation, "tracingImplementation");
    }

    @Nonnull
    public TracingDiagnosticsView view() {
        return TracingDiagnosticsMapper.fromState(tracingImplementation.state());
    }

    @Nonnull
    public Map<String, Object> toActuatorMap() {
        return view().toActuatorMap();
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/diagnostics/TracingDiagnosticsMapper.java

**Status:** ADDED  
**Main type:** `TracingDiagnosticsMapper`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** State to DTO mapper  
**Lines:** 36

```java
package space.br1440.platform.tracing.autoconfigure.diagnostics;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.core.impl.TracingMode;
import space.br1440.platform.tracing.core.impl.TracingState;

import java.util.Objects;

/**
 * Maps internal {@link TracingState} to stable {@link TracingDiagnosticsView} (Slice 7).
 */
public final class TracingDiagnosticsMapper {

    private TracingDiagnosticsMapper() {
    }

    @Nonnull
    public static TracingDiagnosticsView fromState(@Nonnull TracingState state) {
        Objects.requireNonNull(state, "state");
        String mode = toPublicMode(state.mode());
        String reason = state.reason().orElse(null);
        return new TracingDiagnosticsView(mode, reason, state.details());
    }

    @Nonnull
    public static String toPublicMode(@Nonnull TracingMode internalMode) {
        Objects.requireNonNull(internalMode, "internalMode");
        return switch (internalMode) {
            case ENABLED -> "ENABLED";
            case DISABLED_BY_CONFIGURATION -> "DISABLED_BY_CONFIGURATION";
            case UNAVAILABLE -> "UNAVAILABLE";
            case NOOP -> "NOOP";
            case TEST -> "UNKNOWN";
        };
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/diagnostics/TracingDiagnosticsView.java

**Status:** ADDED  
**Main type:** `TracingDiagnosticsView`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** Stable diagnostics DTO  
**Lines:** 39

```java
package space.br1440.platform.tracing.autoconfigure.diagnostics;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable Actuator/support diagnostics contract for manual platform tracing (Slice 7).
 * <p>
 * Must not expose internal {@code TracingState}, {@code TracingMode}, or OpenTelemetry SDK types.
 */
public record TracingDiagnosticsView(
        @Nonnull String mode,
        @Nullable String reason,
        @Nonnull Map<String, String> details) {

    public TracingDiagnosticsView {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(details, "details");
        details = Map.copyOf(details);
    }

    /**
     * Actuator-safe map with stable top-level keys {@code mode}, {@code reason}, {@code details}.
     */
    @Nonnull
    public Map<String, Object> toActuatorMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", mode);
        if (reason != null) {
            map.put("reason", reason);
        }
        map.put("details", details);
        return map;
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredSpanHandle.java

**Status:** ADDED  
**Main type:** `MeteredSpanHandle`  
**Slice / area:** Slice 6 Metering  
**Why it matters for review:** Metered span handle wrap  
**Lines:** 31

```java
package space.br1440.platform.tracing.autoconfigure.metrics;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

import java.util.Objects;

final class MeteredSpanHandle implements SpanHandle {

    private final SpanHandle delegate;
    private final PlatformTracingMetrics metrics;

    MeteredSpanHandle(@Nonnull SpanHandle delegate, @Nonnull PlatformTracingMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void recordException(@Nullable Throwable throwable) {
        if (throwable != null) {
            metrics.incrementExceptionsRecorded();
        }
        delegate.recordException(throwable);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredTracingImplementation.java

**Status:** ADDED  
**Main type:** `MeteredTracingImplementation`  
**Slice / area:** Slice 6 Metering  
**Why it matters for review:** SPI metering decorator  
**Lines:** 63

```java
package space.br1440.platform.tracing.autoconfigure.metrics;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.impl.DelegatingTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingState;

import java.util.Objects;

/**
 * Slice 6: Micrometer decorator for {@link TracingImplementation}.
 * <p>
 * Delegates all span creation to the wrapped implementation and increments self-metrics only.
 * Must not create spans directly or decorate the public {@code PlatformTracing} facade.
 */
public final class MeteredTracingImplementation implements DelegatingTracingImplementation {

    private final TracingImplementation delegate;
    private final PlatformTracingMetrics metrics;

    public MeteredTracingImplementation(@Nonnull TracingImplementation delegate,
                                        @Nonnull PlatformTracingMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        metrics.incrementSpansStarted(spec.category());
        return new MeteredSpanHandle(delegate.startSpan(spec), metrics);
    }

    @Override
    @Nonnull
    public TraceContextView currentTraceContext() {
        return delegate.currentTraceContext();
    }

    @Override
    public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        if (throwable != null) {
            metrics.incrementExceptionsRecorded();
        }
        delegate.recordException(span, throwable);
    }

    @Override
    @Nonnull
    public TracingState state() {
        return delegate.state();
    }

    @Override
    @Nonnull
    public TracingImplementation delegate() {
        return delegate;
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingActuatorAutoConfiguration.java

**Status:** MODIFIED  
**Main type:** `TracingActuatorAutoConfiguration`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** Actuator wiring  
**Lines:** 51

```java
package space.br1440.platform.tracing.autoconfigure;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.health.TracingHealthIndicator;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;

/**
 * ÐÐ²Ñ‚Ð¾-ÐºÐ¾Ð½Ñ„Ð¸Ð³ÑƒÑ€Ð°Ñ†Ð¸Ñ Actuator-Ð¸Ð½Ñ‚ÐµÐ³Ñ€Ð°Ñ†Ð¸Ð¹ Ð¿Ð»Ð°Ñ‚Ñ„Ð¾Ñ€Ð¼ÐµÐ½Ð½Ð¾Ð³Ð¾ Ð¼Ð¾Ð´ÑƒÐ»Ñ Ñ‚Ñ€Ð°ÑÑÐ¸Ñ€Ð¾Ð²ÐºÐ¸.
 * <p>
 * Ð ÐµÐ³Ð¸ÑÑ‚Ñ€Ð¸Ñ€ÑƒÐµÑ‚ health indicator {@code tracing} Ð¸ endpoint {@code /actuator/tracing}.
 * ÐÐºÑ‚Ð¸Ð²Ð½Ð° Ð¿Ñ€Ð¸ Ð½Ð°Ð»Ð¸Ñ‡Ð¸Ð¸ Ð² classpath Ð¸Ð½Ñ„Ñ€Ð°ÑÑ‚Ñ€ÑƒÐºÑ‚ÑƒÑ€Ñ‹ Actuator.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass({Endpoint.class, HealthIndicator.class})
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class TracingActuatorAutoConfiguration {

    @Bean(name = "tracingHealthIndicator")
    @ConditionalOnMissingBean(name = "tracingHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("tracing")
    public TracingHealthIndicator tracingHealthIndicator(PlatformTracing platformTracing) {
        return new TracingHealthIndicator(platformTracing);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    public TracingActuatorEndpoint tracingActuatorEndpoint(PlatformTracing platformTracing,
                                                            TracingProperties properties,
                                                            PlatformTracingJmxClient platformTracingJmxClient,
                                                            ObjectProvider<SdkModeDiagnostics> sdkModeDiagnostics,
                                                            ManualTracingDiagnostics manualTracingDiagnostics) {
        return new TracingActuatorEndpoint(platformTracing, properties, platformTracingJmxClient,
                sdkModeDiagnostics.getIfAvailable(), manualTracingDiagnostics);
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingCoreAutoConfiguration.java

**Status:** MODIFIED  
**Main type:** `TracingCoreAutoConfiguration`  
**Slice / area:** Slice 6 Metering  
**Why it matters for review:** SPI wiring + metered wrap  
**Lines:** 179

```java
package space.br1440.platform.tracing.autoconfigure;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingImplementation;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeResolver;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.NoOpPlatformContextPropagation;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.OtelPlatformContextPropagation;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingMode;

/**
 * Ð‘Ð°Ð·Ð¾Ð²Ð°Ñ Ð°Ð²Ñ‚Ð¾-ÐºÐ¾Ð½Ñ„Ð¸Ð³ÑƒÑ€Ð°Ñ†Ð¸Ñ Ð¿Ð»Ð°Ñ‚Ñ„Ð¾Ñ€Ð¼ÐµÐ½Ð½Ð¾Ð³Ð¾ Ð¼Ð¾Ð´ÑƒÐ»Ñ Ñ‚Ñ€Ð°ÑÑÐ¸Ñ€Ð¾Ð²ÐºÐ¸.
 * <p>
 * Slice 2: registers {@link TracingImplementation} as the internal span-creation boundary and
 * {@link PlatformTracing} as a thin facade over it.
 */
@AutoConfiguration
@ConditionalOnClass(OpenTelemetry.class)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingCoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingCoreAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SdkModeDiagnostics platformSdkModeDiagnostics(
            org.springframework.beans.factory.ObjectProvider<OpenTelemetry> openTelemetryProvider,
            TracingProperties properties) {
        boolean agentPresent = OtelAgentDetector.isAgentPresent();
        boolean userBeanPresent = openTelemetryProvider.getIfAvailable() != null;
        boolean globalFunctional = !userBeanPresent && isGlobalFunctional();

        SdkMode resolved = SdkModeResolver.resolve(
                properties.getSdk().getMode(),
                new SdkModeResolver.Inputs(agentPresent, globalFunctional, userBeanPresent));

        log.info("ÐŸÐ»Ð°Ñ‚Ñ„Ð¾Ñ€Ð¼ÐµÐ½Ð½Ð°Ñ Ñ‚Ñ€Ð°ÑÑÐ¸Ñ€Ð¾Ð²ÐºÐ°: SDK mode={} (agentDetected={}, globalFunctional={}, userOpenTelemetryBean={})",
                resolved, agentPresent, globalFunctional, userBeanPresent);
        return new SdkModeDiagnostics(resolved, agentPresent);
    }

    @Bean
    @ConditionalOnMissingBean
    public TracingImplementation tracingImplementation(
            org.springframework.beans.factory.ObjectProvider<OpenTelemetry> openTelemetryProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.semconv.AttributePolicy> policyProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.exception.ExceptionRecorder> exceptionRecorderProvider,
            org.springframework.beans.factory.ObjectProvider<PlatformTracingMetrics> metricsProvider,
            SdkModeDiagnostics sdkModeDiagnostics) {
        TracingImplementation base = resolveTracingImplementation(
                openTelemetryProvider,
                policyProvider,
                exceptionRecorderProvider,
                sdkModeDiagnostics);
        PlatformTracingMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics != null && base.state().mode() == TracingMode.ENABLED) {
            return new MeteredTracingImplementation(base, metrics);
        }
        return base;
    }

    private TracingImplementation resolveTracingImplementation(
            org.springframework.beans.factory.ObjectProvider<OpenTelemetry> openTelemetryProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.semconv.AttributePolicy> policyProvider,
            org.springframework.beans.factory.ObjectProvider<
                    space.br1440.platform.tracing.core.exception.ExceptionRecorder> exceptionRecorderProvider,
            SdkModeDiagnostics sdkModeDiagnostics) {
        if (sdkModeDiagnostics.mode() == SdkMode.DISABLED) {
            log.info("platform.tracing.sdk.mode=DISABLED â€” TracingImplementation DISABLED_BY_CONFIGURATION");
            return NoOpTracingImplementation.disabledByConfiguration("platform.tracing.sdk.mode=DISABLED");
        }

        space.br1440.platform.tracing.core.semconv.AttributePolicy policy =
                policyProvider.getIfAvailable(space.br1440.platform.tracing.core.semconv.AttributePolicy::new);
        space.br1440.platform.tracing.core.exception.ExceptionRecorder exceptionRecorder =
                exceptionRecorderProvider.getIfAvailable(
                        space.br1440.platform.tracing.core.exception.ExceptionRecorder::secureDefault);

        OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable();
        if (openTelemetry != null) {
            log.debug("TracingImplementation: OpenTelemetry bean from application context");
            return new DefaultTracingImplementation(openTelemetry, policy, exceptionRecorder);
        }
        OpenTelemetry global;
        try {
            global = GlobalOpenTelemetry.get();
        } catch (RuntimeException e) {
            log.warn("TracingImplementation: GlobalOpenTelemetry unavailable ({}); UNAVAILABLE mode",
                    e.getMessage());
            return NoOpTracingImplementation.unavailable("GlobalOpenTelemetry unavailable: " + e.getMessage());
        }
        if (!isFunctional(global)) {
            log.info("TracingImplementation: GlobalOpenTelemetry no-op; UNAVAILABLE mode");
            return NoOpTracingImplementation.unavailable("GlobalOpenTelemetry not functional");
        }
        log.debug("TracingImplementation: GlobalOpenTelemetry (agent)");
        return new DefaultTracingImplementation(global, policy, exceptionRecorder);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracing platformTracing(TracingImplementation tracingImplementation) {
        TracingMode mode = tracingImplementation.state().mode();
        if (mode != TracingMode.ENABLED) {
            log.info("PlatformTracing facade: {} â€” NoOpPlatformTracing", mode);
            return NoOpPlatformTracing.backedBy(tracingImplementation);
        }
        log.debug("PlatformTracing facade: ENABLED â€” DefaultPlatformTracing");
        return new DefaultPlatformTracing(tracingImplementation);
    }

    private static boolean isGlobalFunctional() {
        try {
            return isFunctional(GlobalOpenTelemetry.get());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isFunctional(OpenTelemetry openTelemetry) {
        Span probe = openTelemetry.getTracer("space.br1440.platform.tracing.probe")
                .spanBuilder("__probe")
                .startSpan();
        try {
            return probe.getSpanContext().isValid();
        } finally {
            probe.end();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ManualTracingDiagnostics manualTracingDiagnostics(TracingImplementation tracingImplementation) {
        return new ManualTracingDiagnostics(tracingImplementation);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingJmxClient platformTracingJmxClient() {
        return new PlatformTracingJmxClient();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformContextPropagation platformContextPropagation(PlatformTracing platformTracing) {
        if (platformTracing instanceof NoOpPlatformTracing) {
            log.debug("PlatformContextPropagation: Ð¸ÑÐ¿Ð¾Ð»ÑŒÐ·ÑƒÐµÑ‚ÑÑ NoOp (OpenTelemetry Ð² degraded mode)");
            return NoOpPlatformContextPropagation.INSTANCE;
        }
        log.debug("PlatformContextPropagation: Ð¸ÑÐ¿Ð¾Ð»ÑŒÐ·ÑƒÐµÑ‚ÑÑ OtelPlatformContextPropagation");
        return new OtelPlatformContextPropagation();
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingMetricsAutoConfiguration.java

**Status:** MODIFIED  
**Main type:** `TracingMetricsAutoConfiguration`  
**Slice / area:** Slice 6 Metering  
**Why it matters for review:** Metrics beans; no facade decorator  
**Lines:** 77

```java
package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingImplementation;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSamplerMetricsBinder;

/**
 * ÐÐ²Ñ‚Ð¾-ÐºÐ¾Ð½Ñ„Ð¸Ð³ÑƒÑ€Ð°Ñ†Ð¸Ñ ÑÐ°Ð¼Ð¾Ð½Ð°Ð±Ð»ÑŽÐ´Ð°Ñ‚ÐµÐ»ÑŒÐ½Ñ‹Ñ… Ð¼ÐµÑ‚Ñ€Ð¸Ðº Ð¿Ð»Ð°Ñ‚Ñ„Ð¾Ñ€Ð¼ÐµÐ½Ð½Ð¾Ð³Ð¾ Ð¼Ð¾Ð´ÑƒÐ»Ñ Ñ‚Ñ€Ð°ÑÑÐ¸Ñ€Ð¾Ð²ÐºÐ¸.
 * <p>
 * ÐÐºÑ‚Ð¸Ð²Ð½Ð° Ñ‚Ð¾Ð»ÑŒÐºÐ¾ Ð¿Ñ€Ð¸ Ð½Ð°Ð»Ð¸Ñ‡Ð¸Ð¸ Ð² ÐºÐ¾Ð½Ñ‚ÐµÐºÑÑ‚Ðµ Ð±Ð¸Ð½Ð° {@link MeterRegistry}, ÐºÐ¾Ñ‚Ð¾Ñ€Ñ‹Ð¹ Ð¿Ð¾ÑÑ‚Ð°Ð²Ð»ÑÐµÑ‚ÑÑ Ð¼Ð¾Ð´ÑƒÐ»ÐµÐ¼
 * {@code spring-boot-starter-platform-metrics}. Ð ÐµÐ³Ð¸ÑÑ‚Ñ€Ð¸Ñ€ÑƒÐµÑ‚ {@link PlatformTracingMetrics} Ð¸
 * Ð²ÑÐ¿Ð¾Ð¼Ð¾Ð³Ð°Ñ‚ÐµÐ»ÑŒÐ½Ñ‹Ðµ MeterBinder'Ñ‹.
 * <p>
 * Slice 6: when {@link MeterRegistry} is present, {@link TracingCoreAutoConfiguration} wraps the
 * active {@link space.br1440.platform.tracing.core.impl.TracingImplementation} with
 * {@link MeteredTracingImplementation}.
 */
@AutoConfiguration
@AutoConfigureBefore(value = TracingAopAutoConfiguration.class,
        name = {
                "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration",
                "space.br1440.platform.tracing.autoconfigure.reactive.ReactiveTracingAutoConfiguration"
        })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class TracingMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingMetrics platformTracingMetrics(MeterRegistry meterRegistry) {
        return new PlatformTracingMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(space.br1440.platform.tracing.core.semconv.SemconvMetrics.class)
    public space.br1440.platform.tracing.core.semconv.SemconvMetrics platformSemconvMetrics(MeterRegistry meterRegistry) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.MicrometerSemconvMetrics(meterRegistry);
    }

    @Bean
    public io.micrometer.core.instrument.binder.MeterBinder platformSemanticValidationDisabledBinder(
            TracingProperties properties) {
        return registry -> {
            boolean disabled = properties.getSemantic().getValidationMode()
                    == space.br1440.platform.tracing.api.semconv.ValidationMode.DISABLED;
            registry.gauge("platform.tracing.semantic.validation.disabled", disabled ? 1 : 0);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingSamplerMetricsBinder platformTracingSamplerMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSamplerMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSafeWrapperMetricsBinder platformTracingSafeWrapperMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingSafeWrapperMetricsBinder(platformTracingJmxClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingConfigMetricsBinder platformTracingConfigMetricsBinder(PlatformTracingJmxClient platformTracingJmxClient) {
        return new space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingConfigMetricsBinder(platformTracingJmxClient);
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/actuator/TracingActuatorEndpointProcessorErrorsTest.java

**Status:** MODIFIED  
**Main type:** `TracingActuatorEndpointProcessorErrorsTest`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** Processor errors tests  
**Lines:** 90

```java
package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Ð¢ÐµÑÑ‚ ÑÐµÐºÑ†Ð¸Ð¸ {@code processors} Ð² Ð²Ñ‹Ð²Ð¾Ð´Ðµ {@link TracingActuatorEndpoint#tracing()}.
 */
class TracingActuatorEndpointProcessorErrorsTest {

    private TracingProperties properties;
    private PlatformTracingJmxClient jmxClient;
    private TracingActuatorEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new TracingProperties();
        jmxClient = Mockito.mock(PlatformTracingJmxClient.class);
        when(jmxClient.isAvailable()).thenReturn(true);
        when(jmxClient.getCurrentRatio()).thenReturn(Optional.of(0.1d));
        endpoint = new TracingActuatorEndpoint(
                NoOpPlatformTracing.INSTANCE,
                properties,
                jmxClient,
                new ManualTracingDiagnostics(NoOpTracingImplementation.noop()));
    }

    @Test
    void processorsSectionPresentEvenWhenAgentMissing() {
        when(jmxClient.isAvailable()).thenReturn(false);
        when(jmxClient.getProcessorErrorsTotal()).thenReturn(Optional.empty());
        when(jmxClient.getProcessorErrorsByName()).thenReturn(java.util.Collections.emptyMap());

        Map<String, Object> info = endpoint.tracing();
        assertThat(info).containsKey("processors");

        @SuppressWarnings("unchecked")
        Map<String, Object> processors = (Map<String, Object>) info.get("processors");
        assertThat(processors).containsEntry("errorsTotal", null);
        assertThat(processors).containsEntry("errorsByName", java.util.Collections.emptyMap());
    }

    @Test
    void processorsSectionAggregatesErrorsByNameAndTotalFromJmx() {
        Map<String, Long> byName = new LinkedHashMap<>();
        byName.put("EnrichingSpanProcessor", 2L);
        byName.put("ScrubbingSpanProcessor", 5L);
        byName.put("ValidatingSpanProcessor", 0L);
        byName.put("SpanWatchdogProcessor", 1L);

        when(jmxClient.getProcessorErrorsTotal()).thenReturn(Optional.of(8L));
        when(jmxClient.getProcessorErrorsByName()).thenReturn(byName);

        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> processors = (Map<String, Object>) info.get("processors");

        assertThat(processors).containsEntry("errorsTotal", 8L);

        @SuppressWarnings("unchecked")
        Map<String, Long> actualByName = (Map<String, Long>) processors.get("errorsByName");
        assertThat(actualByName).containsAllEntriesOf(byName);
    }

    @Test
    void processorsErrorsByNameNeverNull() {
        when(jmxClient.getProcessorErrorsTotal()).thenReturn(Optional.empty());
        when(jmxClient.getProcessorErrorsByName()).thenReturn(java.util.Collections.emptyMap());

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> processors = (Map<String, Object>) info.get("processors");

        assertThat(processors.get("errorsByName")).isInstanceOf(Map.class);
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/actuator/TracingActuatorEndpointTest.java

**Status:** MODIFIED  
**Main type:** `TracingActuatorEndpointTest`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** Actuator endpoint tests  
**Lines:** 400

```java
package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxOperationException;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracingActuatorEndpointTest {

    private TracingProperties properties;
    private PlatformTracingJmxClient jmxClient;
    private TracingActuatorEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new TracingProperties();
        properties.getActuator().setMutationEnabled(true);
        jmxClient = Mockito.mock(PlatformTracingJmxClient.class);
        when(jmxClient.isAvailable()).thenReturn(true);
        when(jmxClient.getCurrentRatio()).thenReturn(Optional.of(0.1d));
        endpoint = new TracingActuatorEndpoint(
                NoOpPlatformTracing.INSTANCE,
                properties,
                jmxClient,
                new ManualTracingDiagnostics(NoOpTracingImplementation.noop()));
    }

    @Test
    void writeOperation_Ð¿Ð¾_ÑƒÐ¼Ð¾Ð»Ñ‡Ð°Ð½Ð¸ÑŽ_mutation_disabled_Ð¾Ñ‚ÐºÐ»Ð¾Ð½ÑÐµÑ‚_Ð¸_Ð½Ðµ_Ð²Ñ‹Ð·Ñ‹Ð²Ð°ÐµÑ‚_JMX() {
        properties.getActuator().setMutationEnabled(false);

        assertThatThrownBy(() -> endpoint.updateTracing("samplingRatio", "0.5"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("mutation is disabled")
                .hasMessageContaining("platform.tracing.actuator.mutation-enabled=true");

        verify(jmxClient, never()).setRatio(anyDouble());
        assertThat(properties.getSampling().getRatio()).isEqualTo(0.1d);
    }

    @Test
    void writeOperation_mutation_disabled_Ð¾Ñ‚ÐºÐ»Ð¾Ð½ÑÐµÑ‚_enabled_Ð±ÐµÐ·_Ð¸Ð·Ð¼ÐµÐ½ÐµÐ½Ð¸Ñ_properties() {
        properties.getActuator().setMutationEnabled(false);

        assertThatThrownBy(() -> endpoint.updateTracing("enabled", "false"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(properties.isEnabled()).isTrue();
    }

    @Test
    void readOperation_ÑÐ¾Ð´ÐµÑ€Ð¶Ð¸Ñ‚_actuator_mutationEnabled() {
        properties.getActuator().setMutationEnabled(false);

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> actuator = (Map<String, Object>) info.get("actuator");

        assertThat(actuator).containsEntry("mutationEnabled", false);
    }

    @Test
    void readOperation_Ñ€Ð°Ð±Ð¾Ñ‚Ð°ÐµÑ‚_ÐºÐ¾Ð³Ð´Ð°_mutation_disabled() {
        properties.getActuator().setMutationEnabled(false);

        Map<String, Object> info = endpoint.tracing();
        assertThat(info).containsKeys("enabled", "sampling", "validation", "actuator");
    }

    @Test
    void readOperation_validation_contains_strictRuntimeAllowed_drift_note() {
        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");

        assertThat(validation).containsKey("strictRuntimeAllowedNote");
        assertThat((String) validation.get("strictRuntimeAllowedNote"))
                .contains("liveStrictRuntimeAllowed");
    }

    @Test
    void writeOperation_enabled_Ð¼ÐµÐ½ÑÐµÑ‚_ÑÐ²Ð¾Ð¹ÑÑ‚Ð²Ð¾() {
        Map<String, Object> result = endpoint.updateTracing("enabled", "false");
        assertThat(result.get("appliedValue")).isEqualTo(false);
        assertThat(result.get("previousValue")).isEqualTo(true);
        assertThat(properties.isEnabled()).isFalse();
        verify(jmxClient, never()).setRatio(anyDouble());
    }

    @Test
    void writeOperation_samplingRatio_Ð¿Ñ€Ð¾ÐºÑÐ¸Ñ€ÑƒÐµÑ‚_Ð²_JMX_Ð¸_ÑÐ¸Ð½Ñ…Ñ€Ð¾Ð½Ð¸Ð·Ð¸Ñ€ÑƒÐµÑ‚_properties() {
        Map<String, Object> result = endpoint.updateTracing("samplingRatio", "0.5");

        verify(jmxClient, times(1)).setRatio(0.5d);
        assertThat(result.get("appliedValue")).isEqualTo(0.5);
        assertThat(result.get("previousLiveValue")).isEqualTo(0.1d);
        assertThat(result.get("previousConfiguredValue")).isEqualTo(0.1d);
        assertThat(properties.getSampling().getRatio()).isEqualTo(0.5);
    }

    @Test
    void writeOperation_samplingRatio_ÐºÐ¾Ð³Ð´Ð°_Ñ€Ð°ÑÑˆÐ¸Ñ€ÐµÐ½Ð¸Ðµ_Ð½ÐµÐ´Ð¾ÑÑ‚ÑƒÐ¿Ð½Ð¾_Ð¾Ñ‚ÐºÐ°Ð·Ñ‹Ð²Ð°ÐµÑ‚_Ð´Ð¾_Ð¸Ð·Ð¼ÐµÐ½ÐµÐ½Ð¸Ñ_properties() {
        doThrow(new PlatformTracingJmxOperationException("sampling domain not available"))
                .when(jmxClient).setRatio(anyDouble());

        assertThatThrownBy(() -> endpoint.updateTracing("samplingRatio", "0.5"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(properties.getSampling().getRatio()).isEqualTo(0.1d);
    }

    @Test
    void writeOperation_samplingRatio_Ð·Ð°_Ð¿Ñ€ÐµÐ´ÐµÐ»Ð°Ð¼Ð¸_Ð´Ð¸Ð°Ð¿Ð°Ð·Ð¾Ð½Ð°_Ð±Ñ€Ð¾ÑÐ°ÐµÑ‚_ResponseStatusException() {
        assertThatThrownBy(() -> endpoint.updateTracing("samplingRatio", "1.5"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("samplingRatio")
                .hasMessageContaining("[0.0, 1.0]");
        assertThat(properties.getSampling().getRatio()).isEqualTo(0.1);
        verify(jmxClient, never()).setRatio(anyDouble());
    }

    @Test
    void writeOperation_samplingRatio_Ð½Ðµ_Ñ‡Ð¸ÑÐ»Ð¾_Ð±Ñ€Ð¾ÑÐ°ÐµÑ‚_ResponseStatusException() {
        assertThatThrownBy(() -> endpoint.updateTracing("samplingRatio", "not-a-number"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("samplingRatio must be a number")
                .hasCauseInstanceOf(NumberFormatException.class);
        assertThat(properties.getSampling().getRatio()).isEqualTo(0.1);
        verify(jmxClient, never()).setRatio(anyDouble());
    }

    @Test
    void writeOperation_exportEnabled_Ð¿Ñ€Ð¾ÐºÑÐ¸Ñ€ÑƒÐµÑ‚_Ð²_JMX_Ð¸_ÑÐ¸Ð½Ñ…Ñ€Ð¾Ð½Ð¸Ð·Ð¸Ñ€ÑƒÐµÑ‚_properties() {
        Map<String, Object> result = endpoint.updateTracing("exportEnabled", "false");

        verify(jmxClient, times(1)).setExportEnabled(false);
        assertThat(result.get("appliedValue")).isEqualTo(false);
        assertThat(properties.getExporter().isEnabled()).isFalse();
    }

    @Test
    void writeOperation_propagationEnabled_Ð¿Ñ€Ð¾ÐºÑÐ¸Ñ€ÑƒÐµÑ‚_Ð²_JMX_Ð¸_ÑÐ¸Ð½Ñ…Ñ€Ð¾Ð½Ð¸Ð·Ð¸Ñ€ÑƒÐµÑ‚_properties() {
        Map<String, Object> result = endpoint.updateTracing("propagationEnabled", "false");

        verify(jmxClient, times(1)).setPropagationEnabled(false);
        assertThat(result.get("appliedValue")).isEqualTo(false);
        assertThat(properties.getPropagation().isEnabled()).isFalse();
    }

    @Test
    void writeOperation_logLevel_Ð¿Ñ€Ð¾ÐºÑÐ¸Ñ€ÑƒÐµÑ‚_Ð²_JMX_Ð¸_ÑÐ¸Ð½Ñ…Ñ€Ð¾Ð½Ð¸Ð·Ð¸Ñ€ÑƒÐµÑ‚_properties() {
        Map<String, Object> result = endpoint.updateTracing("logLevel", "DEBUG");

        verify(jmxClient, times(1)).setPlatformLogLevel("DEBUG");
        assertThat(result.get("appliedValue")).isEqualTo("DEBUG");
        assertThat(properties.getDiagnostics().getLogLevel()).isEqualTo("DEBUG");
    }

    @Test
    void writeOperation_Ð½ÐµÐ¸Ð·Ð²ÐµÑÑ‚Ð½Ñ‹Ð¹_Ð¿Ð°Ñ€Ð°Ð¼ÐµÑ‚Ñ€_Ð±Ñ€Ð¾ÑÐ°ÐµÑ‚_ResponseStatusException() {
        assertThatThrownBy(() -> endpoint.updateTracing("foobar", "any"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Unsupported tracing property: foobar");
        verify(jmxClient, never()).setRatio(anyDouble());
    }

    @Test
    void readOperation_Ð²Ð¾Ð·Ð²Ñ€Ð°Ñ‰Ð°ÐµÑ‚_Ñ‚ÐµÐºÑƒÑ‰ÐµÐµ_ÑÐ¾ÑÑ‚Ð¾ÑÐ½Ð¸Ðµ_Ð²ÐºÐ»ÑŽÑ‡Ð°Ñ_live_ratio() {
        Map<String, Object> info = endpoint.tracing();
        assertThat(info).containsKeys("enabled", "implementation", "sampling", "limits", "queue", "exporter",
                "enriching", "validation", "watchdog");

        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        assertThat(sampling).containsEntry("ratio", 0.1d);
        assertThat(sampling).containsEntry("liveRatio", 0.1d);
        assertThat(sampling).containsEntry("controlAvailable", true);
        assertThat(sampling).containsKey("dropPaths");
    }

    @Test
    void readOperation_ÑÐ¾Ð´ÐµÑ€Ð¶Ð¸Ñ‚_ÑÐµÐºÑ†Ð¸Ð¸_enriching_validation_watchdog_Ð¸_scrubbing() {
        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> enriching = (Map<String, Object>) info.get("enriching");
        assertThat(enriching).containsEntry("enabled", true);
        assertThat(enriching).containsKey("remoteServicePriority");

        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");
        assertThat(validation).containsEntry("enabled", true);
        assertThat(validation).containsEntry("strict", false);
        assertThat(validation).containsKeys("liveEnabled", "liveStrict", "configVersion", "configSource");

        @SuppressWarnings("unchecked")
        Map<String, Object> watchdog = (Map<String, Object>) info.get("watchdog");
        assertThat(watchdog).containsEntry("enabled", true);
        assertThat(watchdog).containsKeys("scanInterval", "spanTimeout", "traceTimeout");

        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbing = (Map<String, Object>) info.get("scrubbing");
        assertThat(scrubbing).containsEntry("enabled", true);
        assertThat(scrubbing).containsKeys("liveEnabled", "configVersion", "configSource", "liveRuleCount");
        assertThat(scrubbing).containsEntry("customRulesSource", "otel-agent-spi");
        assertThat(scrubbing).containsEntry("customRulesVisible", false);
        assertThat(scrubbing).containsKey("builtInRules");
        assertThat(scrubbing).containsKey("note");
    }

    @Test
    void readOperation_validation_reflects_live_agent_state() {
        when(jmxClient.isValidationEnabled()).thenReturn(Optional.of(true));
        when(jmxClient.isValidationStrict()).thenReturn(Optional.of(true));
        when(jmxClient.getValidationConfigVersion()).thenReturn(Optional.of(5L));
        when(jmxClient.getValidationConfigLastUpdatedSource()).thenReturn(Optional.of("spring-runtime-config"));
        properties.getValidation().setStrict(false);

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");

        assertThat(validation).containsEntry("strict", false);
        assertThat(validation).containsEntry("liveStrict", true);
        assertThat(validation).containsEntry("configVersion", 5L);
        assertThat(validation).containsEntry("configSource", "spring-runtime-config");
    }

    @Test
    void readOperation_sampling_scrubbing_live_fields_present() {
        when(jmxClient.getLiveDropPathPrefixes()).thenReturn(Optional.of(java.util.List.of("/health")));
        when(jmxClient.getLiveForceRecordValues()).thenReturn(Optional.of(java.util.List.of("on")));
        when(jmxClient.getLiveRouteRatios()).thenReturn(Optional.of(Map.of("/api", 0.5d)));
        when(jmxClient.getSamplingConfigLastUpdatedSource()).thenReturn(Optional.of("JMX"));
        when(jmxClient.isScrubbingEnabled()).thenReturn(Optional.of(false));
        when(jmxClient.getScrubbingConfigVersion()).thenReturn(Optional.of(3L));
        when(jmxClient.getScrubbingConfigLastUpdatedSource()).thenReturn(Optional.of("spring-runtime-config"));
        when(jmxClient.getScrubbingMetrics()).thenReturn(Map.of("rules.loaded", 2L));

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        assertThat(sampling).containsKeys("enabled", "routeRatios", "liveDropPaths", "liveForceRecordHeaderValues",
                "liveRouteRatios", "configSource");

        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbing = (Map<String, Object>) info.get("scrubbing");
        assertThat(scrubbing).containsEntry("liveEnabled", false);
        assertThat(scrubbing).containsEntry("configVersion", 3L);
        assertThat(scrubbing).containsEntry("liveRuleCount", 2L);
    }

    @Test
    void readOperation_export_ÑÐµÐºÑ†Ð¸Ñ_not_ready_ÐºÐ¾Ð³Ð´Ð°_Ð¼ÐµÑ‚Ñ€Ð¸ÐºÐ¸_Ð½ÐµÐ´Ð¾ÑÑ‚ÑƒÐ¿Ð½Ñ‹() {
        when(jmxClient.getExportMetrics()).thenReturn(Optional.empty());

        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> export = (Map<String, Object>) info.get("export");
        assertThat(export).containsEntry("status", "not_ready");
    }

    @Test
    void readOperation_export_ÑÐµÐºÑ†Ð¸Ñ_ÑÐ¾Ð´ÐµÑ€Ð¶Ð¸Ñ‚_Ð¼ÐµÑ‚Ñ€Ð¸ÐºÐ¸_ÐºÐ¾Ð³Ð´Ð°_Ð´Ð¾ÑÑ‚ÑƒÐ¿Ð½Ñ‹() {
        Map<String, Object> metrics = Map.of(
                "queueCapacity", 2048,
                "queueSize", 10,
                "failures", 3L);
        when(jmxClient.getExportMetrics()).thenReturn(Optional.of(metrics));

        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> export = (Map<String, Object>) info.get("export");
        assertThat(export)
                .containsEntry("queueCapacity", 2048)
                .containsEntry("queueSize", 10)
                .containsEntry("failures", 3L);
    }

    @Test
    void readOperation_ÐºÐ¾Ð³Ð´Ð°_Ñ€Ð°ÑÑˆÐ¸Ñ€ÐµÐ½Ð¸Ðµ_Ð½ÐµÐ´Ð¾ÑÑ‚ÑƒÐ¿Ð½Ð¾_Ð¾Ñ‚Ð´Ð°Ñ‘Ñ‚_null_live_ratio() {
        when(jmxClient.isAvailable()).thenReturn(false);
        when(jmxClient.getCurrentRatio()).thenReturn(Optional.empty());

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        assertThat(sampling).containsEntry("liveRatio", null);
        assertThat(sampling).containsEntry("controlAvailable", false);
    }

    @Test
    void readOperation_ÑÐ¾Ð´ÐµÑ€Ð¶Ð¸Ñ‚_ÑÐµÐºÑ†Ð¸ÑŽ_otelEffective_Ñ_Ð¿Ð¾Ð´Ð¼ÐµÐ½Ð¾Ð¹_Ð¸ÑÑ‚Ð¾Ñ‡Ð½Ð¸ÐºÐ¾Ð²() {
        java.util.Map<String, String> sysProps = new java.util.HashMap<>();
        java.util.Map<String, String> envVars = new java.util.HashMap<>();
        sysProps.put("otel.bsp.max.queue.size", "8192");
        envVars.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector-test:4317");
        envVars.put("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer secret");

        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(
                sysProps::get, envVars::get);
        ResourceEffectiveSnapshot resourceSnapshot = new ResourceEffectiveSnapshot(
                sysProps::get, envVars::get);
        TracingActuatorEndpoint custom = new TracingActuatorEndpoint(
                NoOpPlatformTracing.INSTANCE,
                properties,
                jmxClient,
                snapshot,
                resourceSnapshot,
                new ManualTracingDiagnostics(NoOpTracingImplementation.noop()));

        Map<String, Object> info = custom.tracing();
        assertThat(info).containsKey("otelEffective");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> effective =
                (Map<String, Map<String, Object>>) info.get("otelEffective");

        assertThat(effective.get("otel.bsp.max.queue.size"))
                .containsEntry("source", "system-property")
                .containsEntry("value", "8192");

        assertThat(effective.get("otel.exporter.otlp.endpoint"))
                .containsEntry("source", "env-var")
                .containsEntry("value", "http://collector-test:4317");

        assertThat(effective.get("otel.exporter.otlp.headers"))
                .containsEntry("source", "env-var")
                .containsEntry("value", "***");

        assertThat(effective.get("otel.span.attribute.count.limit"))
                .containsEntry("source", "default-platform")
                .containsEntry("value", "50");
    }

    @Test
    void readOperation_config_lastUpdatedSource_legacy_sampling_only() {
        when(jmxClient.getSamplingConfigLastUpdatedSource()).thenReturn(Optional.of("JMX"));
        when(jmxClient.getValidationConfigLastUpdatedSource())
                .thenReturn(Optional.of("spring-runtime-config"));

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) info.get("config");
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");

        assertThat(config.get("lastUpdatedSource")).isEqualTo("JMX");
        assertThat(validation.get("configSource")).isEqualTo("spring-runtime-config");
    }

    @Test
    void readOperation_ÑÐ¾Ð´ÐµÑ€Ð¶Ð¸Ñ‚_otelEnvHints_Ð¸Ð·_TracingProperties() {
        properties.getQueue().setExportTimeout(java.time.Duration.ofMillis(100));

        Map<String, Object> info = endpoint.tracing();

        assertThat(info).containsKey("otelEnvHints");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> hints =
                (Map<String, Map<String, Object>>) info.get("otelEnvHints");

        assertThat(hints.get("OTEL_BSP_EXPORT_TIMEOUT"))
                .containsEntry("suggestedValue", "100")
                .containsEntry("springProperty", "platform.tracing.queue.export-timeout");
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/BeanTopologyTest.java

**Status:** MODIFIED  
**Main type:** `BeanTopologyTest`  
**Slice / area:** Slice 2 TracingImplementation  
**Why it matters for review:** Bean singularity gate  
**Lines:** 180

```java
package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingImplementation;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingMode;
import space.br1440.platform.tracing.core.impl.TracingState;
import space.br1440.platform.tracing.core.manual.NoOpSpanHandle;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BeanTopologyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class));

    @Test
    void exactlyOnePlatformTracingAndTracingImplementation() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(PlatformTracing.class)).hasSize(1);
                    assertThat(context.getBeansOfType(TracingImplementation.class)).hasSize(1);
                    assertThat(context.getBean(PlatformTracing.class))
                            .isInstanceOf(DefaultPlatformTracing.class);
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(DefaultTracingImplementation.class);
                });
    }

    @Test
    void facadeBackedBySingleImplementationChain() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    DefaultPlatformTracing facade = context.getBean(DefaultPlatformTracing.class);
                    TracingImplementation impl = context.getBean(TracingImplementation.class);
                    assertThat(facade.tracingImplementation()).isSameAs(impl);
                });
    }

    @Test
    void withMicrometer_wrapsTracingImplementationWithoutPublicFacadeDecorator() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class, MeterRegistryConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("meteredPlatformTracing");
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(DefaultPlatformTracing.class);
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(MeteredTracingImplementation.class);
                    assertThat(((MeteredTracingImplementation) context.getBean(TracingImplementation.class)).delegate())
                            .isInstanceOf(DefaultTracingImplementation.class);
                });
    }

    @Test
    void disabledSdkMode_exposesNonEnabledTracingState() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    TracingImplementation impl = context.getBean(TracingImplementation.class);
                    assertThat(impl.state().mode()).isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
                });
    }

    @Test
    void unavailableOpenTelemetry_exposesUnavailableState() {
        contextRunner.run(context -> {
            TracingImplementation impl = context.getBean(TracingImplementation.class);
            assertThat(impl.state().mode()).isIn(TracingMode.UNAVAILABLE, TracingMode.NOOP);
            assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
        });
    }

    @Test
    void userPrimaryTracingImplementation_replacesDefaultWithoutHiddenBypass() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class, CustomPrimaryTracingImplementationConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(TracingImplementation.class)).hasSize(1);
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(MarkerTracingImplementation.class);
                    assertThat(context).doesNotHaveBean("meteredPlatformTracing");
                });
    }

    @Configuration
    static class OpenTelemetryConfiguration {
        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder().build();
        }
    }

    @Configuration
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    static final class MarkerTracingImplementation implements TracingImplementation {

        private static final TracingState STATE = new TracingState() {
            @Override
            public TracingMode mode() {
                return TracingMode.TEST;
            }

            @Override
            public Optional<String> reason() {
                return Optional.of("test-primary");
            }

            @Override
            public Map<String, String> details() {
                return Map.of();
            }
        };

        @Override
        @Nonnull
        public SpanHandle startSpan(@Nonnull SpanSpec spec) {
            return NoOpSpanHandle.INSTANCE;
        }

        @Override
        @Nonnull
        public TraceContextView currentTraceContext() {
            return NoOpTracingImplementation.noop().currentTraceContext();
        }

        @Override
        public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
            Objects.requireNonNull(span, "span");
        }

        @Override
        @Nonnull
        public TracingState state() {
            return STATE;
        }
    }

    @Configuration
    static class CustomPrimaryTracingImplementationConfig {
        @Bean
        @Primary
        TracingImplementation customTracingImplementation() {
            return new MarkerTracingImplementation();
        }
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/diagnostics/DiagnosticsBoundaryTest.java

**Status:** ADDED  
**Main type:** `DiagnosticsBoundaryTest`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** Diagnostics DTO boundary  
**Lines:** 151

```java
package space.br1440.platform.tracing.autoconfigure.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.tracing.autoconfigure.TracingActuatorAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingMode;
import space.br1440.platform.tracing.core.impl.TracingState;
import space.br1440.platform.tracing.core.manual.NoOpSpanHandle;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7 hard gate: Actuator exposes stable diagnostics DTO, not internal state types.
 */
class DiagnosticsBoundaryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> APPROVED_MODES = Set.of(
            "ENABLED",
            "DISABLED_BY_CONFIGURATION",
            "UNAVAILABLE",
            "NOOP",
            "UNKNOWN");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingCoreAutoConfiguration.class));

    @Test
    void actuatorEndpoint_exposesStableManualTracingSection() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    TracingActuatorEndpoint endpoint = new TracingActuatorEndpoint(
                            context.getBean(PlatformTracing.class),
                            context.getBean(TracingProperties.class),
                            context.getBean(PlatformTracingJmxClient.class),
                            context.getBean(ManualTracingDiagnostics.class));
                    Map<String, Object> payload = endpoint.tracing();

                    assertThat(payload).containsKey("manualTracing");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> manualTracing = (Map<String, Object>) payload.get("manualTracing");

                    assertThat(manualTracing.keySet()).containsExactlyInAnyOrder("mode", "reason", "details");
                    assertThat(manualTracing.get("mode")).isEqualTo("DISABLED_BY_CONFIGURATION");
                    assertThat(manualTracing.get("reason")).isInstanceOf(String.class);
                    assertThat(manualTracing.get("details")).isInstanceOf(Map.class);

                    String json;
                    try {
                        json = OBJECT_MAPPER.writeValueAsString(manualTracing);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    assertThat(json).doesNotContain("TracingState");
                    assertThat(json).doesNotContain("TracingMode");
                    assertThat(json.toLowerCase()).doesNotContain("opentelemetry");
                    assertThat(APPROVED_MODES).contains((String) manualTracing.get("mode"));
                });
    }

    @Test
    void manualTracingDiagnostics_mapsInternalTestModeToUnknown() {
        TracingImplementation testPrimary = new TracingImplementation() {
            private final TracingState state = new TracingState() {
                @Override
                public TracingMode mode() {
                    return TracingMode.TEST;
                }

                @Override
                public Optional<String> reason() {
                    return Optional.of("custom-primary");
                }

                @Override
                public Map<String, String> details() {
                    return Map.of("marker", "true");
                }
            };

            @Override
            public SpanHandle startSpan(SpanSpec spec) {
                return NoOpSpanHandle.INSTANCE;
            }

            @Override
            public TraceContextView currentTraceContext() {
                return new TraceContextView() {
                    @Override
                    public Optional<String> traceId() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> spanId() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> correlationId() {
                        return Optional.empty();
                    }
                };
            }

            @Override
            public void recordException(SpanHandle span, Throwable throwable) {
            }

            @Override
            public TracingState state() {
                return state;
            }
        };

        ManualTracingDiagnostics diagnostics = new ManualTracingDiagnostics(testPrimary);
        TracingDiagnosticsView view = diagnostics.view();

        assertThat(view.mode()).isEqualTo("UNKNOWN");
        assertThat(view.reason()).isEqualTo("custom-primary");
        assertThat(view.details()).containsEntry("marker", "true");
        assertThat(diagnostics.toActuatorMap().get("mode")).isEqualTo("UNKNOWN");
    }

    @Test
    void unavailableState_exposesReasonAndApprovedMode() {
        contextRunner.run(context -> {
            ManualTracingDiagnostics diagnostics = context.getBean(ManualTracingDiagnostics.class);
            TracingDiagnosticsView view = diagnostics.view();

            assertThat(view.mode()).isIn("UNAVAILABLE", "NOOP");
            assertThat(APPROVED_MODES).contains(view.mode());
        });
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/diagnostics/ObservationCoexistenceTest.java

**Status:** ADDED  
**Main type:** `ObservationCoexistenceTest`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** Observation coexistence gate  
**Lines:** 183

```java
package space.br1440.platform.tracing.autoconfigure.diagnostics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingImplementation;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.impl.TracingImplementation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7 hard gate: Micrometer Observation and platform manual tracing coexist without
 * duplicate unsynchronized roots.
 */
class ObservationCoexistenceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetryAutoConfiguration.class,
                    ObservationAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class))
            .withUserConfiguration(ObservationCoexistenceTestConfiguration.class)
            .withPropertyValues("spring.application.name=observation-coexistence");

    @Test
    void manualOperationInsideObservation_isChildOfObservedRoot_notCompetingRoot() {
        contextRunner.run(context -> {
            ObservationRegistry observationRegistry = context.getBean(ObservationRegistry.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            Observation observation = Observation.createNotStarted("app.request", observationRegistry)
                    .start();
            try (Observation.Scope scope = observation.openScope()) {
                tracing.manual().operation("business-logic").start().close();
            } finally {
                observation.stop();
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2);
            assertThat(distinctTraceIds(spans)).hasSize(1);
            assertThat(rootSpanCount(spans)).isEqualTo(1);

            SpanData manual = findSpan(spans, "business-logic");
            assertThat(manual.getParentSpanContext().isValid()).isTrue();
            assertThat(manual.getTraceId()).isEqualTo(findSpan(spans, "app.request").getTraceId());
        });
    }

    @Test
    void intentionalManualRoot_insideObservation_createsSeparateTrace() {
        contextRunner.run(context -> {
            ObservationRegistry observationRegistry = context.getBean(ObservationRegistry.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            Observation observation = Observation.createNotStarted("app.request", observationRegistry)
                    .start();
            try (Observation.Scope scope = observation.openScope()) {
                tracing.manual().operation("intentional-root").root().start().close();
            } finally {
                observation.stop();
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2);
            assertThat(rootSpanCount(spans)).isEqualTo(2);
            assertThat(distinctTraceIds(spans)).hasSize(2);
        });
    }

    @Test
    void disabledPlatformManualTracing_doesNotDisableSpringObservation() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    ObservationRegistry observationRegistry = context.getBean(ObservationRegistry.class);
                    PlatformTracing tracing = context.getBean(PlatformTracing.class);
                    InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

                    assertThat(tracing).isInstanceOf(NoOpPlatformTracing.class);
                    assertThat(context.getBean(ManualTracingDiagnostics.class).view().mode())
                            .isEqualTo("DISABLED_BY_CONFIGURATION");

                    Observation observation = Observation.createNotStarted("observation-only", observationRegistry)
                            .start();
                    observation.stop();

                    assertThat(exporter.getFinishedSpanItems()).hasSize(1);
                });
    }

    @Test
    void meteredImplementation_doesNotCreateSpansDirectly() {
        contextRunner.run(context -> {
            TracingImplementation tracingImplementation = context.getBean(TracingImplementation.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            assertThat(tracingImplementation).isInstanceOf(MeteredTracingImplementation.class);
            assertThat(tracing).isInstanceOf(DefaultPlatformTracing.class);

            tracing.manual().operation("metered-delegate").start().close();

            assertThat(exporter.getFinishedSpanItems())
                    .extracting(SpanData::getName)
                    .containsExactly("metered-delegate");
        });
    }

    private static Set<String> distinctTraceIds(List<SpanData> spans) {
        return spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet());
    }

    private static long rootSpanCount(List<SpanData> spans) {
        return spans.stream().filter(ObservationCoexistenceTest::isRoot).count();
    }

    private static boolean isRoot(SpanData span) {
        return !span.getParentSpanContext().isValid()
                || span.getParentSpanId().isEmpty()
                || "0000000000000000".equals(span.getParentSpanId());
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name
                        + "; exported=" + spans.stream().map(SpanData::getName).toList()));
    }

    @Configuration
    static class ObservationCoexistenceTestConfiguration {

        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        OpenTelemetry openTelemetry(InMemorySpanExporter spanExporter) {
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();
            return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/diagnostics/SpringBootContextMatrixTest.java

**Status:** ADDED  
**Main type:** `SpringBootContextMatrixTest`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** Extended context matrix  
**Lines:** 128

```java
package space.br1440.platform.tracing.autoconfigure.diagnostics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingImplementation;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingMode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7: extended Spring Boot autoconfiguration matrix beyond Slice 2 {@code BeanTopologyTest}.
 */
class SpringBootContextMatrixTest {

    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class));

    @Test
    void enabledWithOpenTelemetry_exposesEnabledManualTracingDiagnostics() {
        baseRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(DefaultPlatformTracing.class);
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(DefaultTracingImplementation.class);
                    assertThat(context.getBean(ManualTracingDiagnostics.class).view().mode()).isEqualTo("ENABLED");
                });
    }

    @Test
    void disabledSdkMode_exposesDisabledDiagnosticsAndNoOpFacade() {
        baseRunner
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
                    assertThat(context.getBean(TracingImplementation.class).state().mode())
                            .isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
                    assertThat(context.getBean(ManualTracingDiagnostics.class).view().mode())
                            .isEqualTo("DISABLED_BY_CONFIGURATION");
                });
    }

    @Test
    void unavailableOpenTelemetry_exposesUnavailableOrNoopDiagnostics() {
        baseRunner.run(context -> {
            assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
            assertThat(context.getBean(ManualTracingDiagnostics.class).view().mode())
                    .isIn("UNAVAILABLE", "NOOP");
        });
    }

    @Test
    void micrometerPresent_wrapsTracingImplementationWithMeteredDecorator() {
        baseRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class, MeterRegistryConfiguration.class)
                .run(context -> {
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(MeteredTracingImplementation.class);
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(DefaultPlatformTracing.class);
                    assertThat(context.getBean(ManualTracingDiagnostics.class).view().mode()).isEqualTo("ENABLED");
                });
    }

    @Test
    void micrometerAbsent_keepsDefaultTracingImplementationWithoutMeteredWrap() {
        baseRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(DefaultTracingImplementation.class);
                    assertThat(context.getBean(ManualTracingDiagnostics.class).view().mode()).isEqualTo("ENABLED");
                });
    }

    @Test
    void actuatorEndpoint_includesManualTracingSectionInAllMatrixPaths() {
        baseRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    TracingActuatorEndpoint endpoint = new TracingActuatorEndpoint(
                            context.getBean(PlatformTracing.class),
                            context.getBean(TracingProperties.class),
                            context.getBean(PlatformTracingJmxClient.class),
                            context.getBean(ManualTracingDiagnostics.class));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> manualTracing = (Map<String, Object>) endpoint.tracing()
                            .get("manualTracing");
                    assertThat(manualTracing).containsKeys("mode", "details");
                });
    }

    @Configuration
    static class OpenTelemetryConfiguration {
        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder().build();
        }
    }

    @Configuration
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/diagnostics/TracingDiagnosticsViewJsonContractTest.java

**Status:** ADDED  
**Main type:** `TracingDiagnosticsViewJsonContractTest`  
**Slice / area:** Slice 7 Diagnostics/Observation  
**Why it matters for review:** JSON contract test  
**Lines:** 114

```java
package space.br1440.platform.tracing.autoconfigure.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingMode;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7: stable JSON contract for {@link TracingDiagnosticsView}.
 */
class TracingDiagnosticsViewJsonContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void actuatorMap_serializesStableTopLevelKeysOnly() throws Exception {
        TracingDiagnosticsView view = new TracingDiagnosticsView(
                "ENABLED",
                null,
                Map.of("source", "test"));

        @SuppressWarnings("unchecked")
        Map<String, Object> json = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(view.toActuatorMap()),
                Map.class);

        assertThat(json.keySet()).containsExactlyInAnyOrder("mode", "details");
        assertThat(json.get("mode")).isEqualTo("ENABLED");
        assertThat(json.get("details")).isEqualTo(Map.of("source", "test"));
    }

    @Test
    void actuatorMap_includesReasonWhenPresent() throws Exception {
        TracingDiagnosticsView view = TracingDiagnosticsMapper.fromState(
                NoOpTracingImplementation.disabledByConfiguration("platform.tracing.sdk.mode=DISABLED").state());

        @SuppressWarnings("unchecked")
        Map<String, Object> json = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(view.toActuatorMap()),
                Map.class);

        assertThat(json.keySet()).containsExactlyInAnyOrder("mode", "reason", "details");
        assertThat(json.get("mode")).isEqualTo("DISABLED_BY_CONFIGURATION");
        assertThat(json.get("reason")).isEqualTo("platform.tracing.sdk.mode=DISABLED");
        assertThat(json.get("details")).isEqualTo(Map.of());
    }

    @Test
    void internalTestMode_mapsToUnknownPublicMode() {
        TracingDiagnosticsView view = TracingDiagnosticsMapper.fromState(new TestModeTracingImplementation().state());

        assertThat(view.mode()).isEqualTo("UNKNOWN");
        assertThat(view.reason()).isEqualTo("test-primary");
    }

    @Test
    void approvedPublicModes_areClosedSet() {
        Set<String> approved = Set.of(
                "ENABLED",
                "DISABLED_BY_CONFIGURATION",
                "UNAVAILABLE",
                "NOOP",
                "UNKNOWN");

        for (TracingMode internal : TracingMode.values()) {
            String mapped = TracingDiagnosticsMapper.toPublicMode(internal);
            assertThat(approved).contains(mapped);
        }
    }

    private static final class TestModeTracingImplementation implements space.br1440.platform.tracing.core.impl.TracingImplementation {
        @Override
        public space.br1440.platform.tracing.api.span.spec.SpanHandle startSpan(
                space.br1440.platform.tracing.api.span.spec.SpanSpec spec) {
            return space.br1440.platform.tracing.core.manual.NoOpSpanHandle.INSTANCE;
        }

        @Override
        public space.br1440.platform.tracing.api.manual.TraceContextView currentTraceContext() {
            return NoOpTracingImplementation.noop().currentTraceContext();
        }

        @Override
        public void recordException(
                space.br1440.platform.tracing.api.span.spec.SpanHandle span, Throwable throwable) {
        }

        @Override
        public space.br1440.platform.tracing.core.impl.TracingState state() {
            return new space.br1440.platform.tracing.core.impl.TracingState() {
                @Override
                public TracingMode mode() {
                    return TracingMode.TEST;
                }

                @Override
                public java.util.Optional<String> reason() {
                    return java.util.Optional.of("test-primary");
                }

                @Override
                public Map<String, String> details() {
                    return Map.of();
                }
            };
        }
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredMetricsCountTest.java

**Status:** ADDED  
**Main type:** `MeteredMetricsCountTest`  
**Slice / area:** Slice 6 Metering  
**Why it matters for review:** Metrics count gate  
**Lines:** 126

```java
package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 6 hard gate: metered decorator increments bounded self-metrics without masking topology.
 */
class MeteredMetricsCountTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class))
            .withUserConfiguration(
                    MetricsCountTestConfiguration.class,
                    MeterRegistryConfiguration.class);

    @Test
    void startSpan_incrementsSpansStartedByCategory() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            tracing.manual().operation("op-a").start().close();
            tracing.manual().operation("op-b").root().start().close();
            tracing.manual().transport().kafka().consumer()
                    .batch("orders")
                    .root()
                    .linkedTo(SpanLinkContext.sampled(
                            "0102030405060708090a0b0c0d0e0f10", "0102030405060708"))
                    .start()
                    .close();

            assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.INTERNAL.value())
                    .counter()
                    .count()).isEqualTo(2.0);
            assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.KAFKA_CONSUMER.value())
                    .counter()
                    .count()).isEqualTo(1.0);
            assertThat(exporter.getFinishedSpanItems()).hasSize(3);
        });
    }

    @Test
    void recordException_incrementsExceptionsRecorded() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);

            var handle = tracing.manual().operation("failing-op").start();
            handle.recordException(new IllegalStateException("boom"));
            handle.close();

            assertThat(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED)
                    .counter()
                    .count()).isEqualTo(1.0);
            assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.INTERNAL.value())
                    .counter()
                    .count()).isEqualTo(1.0);
        });
    }

    @Test
    void spansStartedMetric_usesBoundedCategoryTagsOnly() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);

            tracing.manual().operation("dynamic-name-" + System.nanoTime()).start().close();

            var counters = registry.find(PlatformTracingMetrics.SPANS_STARTED).counters();
            assertThat(counters).isNotEmpty();
            for (var counter : counters) {
                assertThat(counter.getId().getTags()).hasSize(1);
                assertThat(counter.getId().getTags().getFirst().getKey()).isEqualTo("category");
            }
        });
    }

    @Configuration
    static class MetricsCountTestConfiguration {

        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        OpenTelemetry openTelemetry(InMemorySpanExporter spanExporter) {
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();
            return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        }
    }

    @Configuration
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
```

## platform-tracing-spring-boot-autoconfigure — platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredTopologyMatrixTest.java

**Status:** ADDED  
**Main type:** `MeteredTopologyMatrixTest`  
**Slice / area:** Slice 6 Metering  
**Why it matters for review:** Metered topology gate  
**Lines:** 214

```java
package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.impl.TracingImplementation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 6 hard gate: metered {@link TracingImplementation} preserves topology and links.
 */
class MeteredTopologyMatrixTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";
    private static final String TRACEPARENT =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class))
            .withUserConfiguration(
                    MeteredTopologyTestConfiguration.class,
                    MeterRegistryConfiguration.class);

    @Test
    void meteredChain_wrapsDefaultImplementation() {
        contextRunner.run(context -> {
            assertThat(context.getBean(TracingImplementation.class))
                    .isInstanceOf(MeteredTracingImplementation.class);
            assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(DefaultPlatformTracing.class);
        });
    }

    @Test
    void operationRootWithLinks_preservesRootTopologyAndRemoteLinks() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            SpanLinkContext link = SpanLinkContext.sampled(
                    "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
            tracing.manual().operation("linked-root").root().linkedTo(link).start().close();

            SpanData span = findSpan(exporter, "linked-root");
            assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(span.getLinks()).hasSize(1);
            assertThat(span.getLinks().getFirst().getSpanContext().isRemote()).isTrue();
        });
    }

    @Test
    void operationDetached_preservesDetachedNoParentAndNoLinks() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            try (var parent = tracing.manual().operation("parent").start()) {
                tracing.manual().operation("orphan").detached().start().close();
            }

            SpanData detached = findSpan(exporter, "orphan");
            SpanData parent = findSpan(exporter, "parent");
            assertThat(detached.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(detached.getTraceId()).isNotEqualTo(parent.getTraceId());
            assertThat(detached.getLinks()).isEmpty();
        });
    }

    @Test
    void operationDetachedWithLinks_failsFast() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            SpanLinkContext link = SpanLinkContext.sampled(
                    "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
            assertThatThrownBy(() ->
                    tracing.manual().operation("bad-detached").detached().linkedTo(link).start())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DETACHED");
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        });
    }

    @Test
    void kafkaBatchRootWithLinks_preservesRootTopologyAndLinks() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            tracing.manual().transport().kafka().consumer()
                    .batch("orders")
                    .root()
                    .fromRemoteContext(TRACEPARENT)
                    .start()
                    .close();

            SpanData span = findSpan(exporter, "orders process");
            assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(span.getLinks()).hasSize(1);
            assertThat(span.getLinks())
                    .extracting(LinkData::getSpanContext)
                    .extracting(ctx -> ctx.getTraceId() + "/" + ctx.getSpanId())
                    .containsExactly("0102030405060708090a0b0c0d0e0f10/0102030405060708");
        });
    }

    @Test
    void spanFromSpec_rootWithLinks_worksPerPolicy() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            SpanLinkContext link = SpanLinkContext.sampled(
                    "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
            SpanSpec spec = SpanSpec.builder("spec-root")
                    .category(SpanCategory.INTERNAL)
                    .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                    .root()
                    .linkedTo(link)
                    .build();
            tracing.manual().spanFromSpec(spec).start().close();

            SpanData span = findSpan(exporter, "spec-root");
            assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(span.getLinks()).hasSize(1);
        });
    }

    @Test
    void spanFromSpec_detachedWithoutLinks_worksPerPolicy() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            SpanSpec spec = SpanSpec.builder("spec-detached")
                    .category(SpanCategory.INTERNAL)
                    .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                    .detached()
                    .build();
            tracing.manual().spanFromSpec(spec).start().close();

            SpanData span = findSpan(exporter, "spec-detached");
            assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(span.getLinks()).isEmpty();
        });
    }

    @Test
    void spanFromSpec_detachedWithLinks_failsFastAtBuild() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() -> SpanSpec.builder("bad-spec")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .detached()
                .linkedTo(link)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    private static SpanData findSpan(InMemorySpanExporter exporter, String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name
                        + "; exported=" + spans.stream().map(SpanData::getName).toList()));
    }

    @Configuration
    static class MeteredTopologyTestConfiguration {

        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        OpenTelemetry openTelemetry(InMemorySpanExporter spanExporter) {
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();
            return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        }
    }

    @Configuration
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
```

