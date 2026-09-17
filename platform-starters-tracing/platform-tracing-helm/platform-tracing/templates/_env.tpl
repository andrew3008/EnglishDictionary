{{- define "platform-tracing.v1.env" -}}
{{- $tracing := required "platform-tracing.v1.env: \"tracing\" is required" .tracing -}}
{{- if hasKey $tracing "configurationAuthority" -}}
  {{- fail "platform-tracing.v1.env: configurationAuthority is obsolete; use controlChannel (STATIC|CONFIG_SERVER|ACTUATOR)" -}}
{{- end -}}
{{- $serviceName := required "platform-tracing.v1.env: platformTracing.serviceName is required" $tracing.serviceName -}}
{{- $export := default dict $tracing.export -}}
{{- $endpoint := default "http://otel-collector.ops-system.svc.cluster.local:4317" $export.endpoint -}}
{{- $resourceAttributes := default dict $tracing.resourceAttributes -}}
{{- $attributePairs := list -}}
{{- if $resourceAttributes.serviceVersion -}}
  {{- $attributePairs = append $attributePairs (printf "service.version=%s" $resourceAttributes.serviceVersion) -}}
{{- end -}}
{{- if $resourceAttributes.deploymentEnvironmentName -}}
  {{- $attributePairs = append $attributePairs (printf "deployment.environment.name=%s" $resourceAttributes.deploymentEnvironmentName) -}}
{{- end -}}
{{- $env := list -}}
{{- $env = append $env (dict "name" "OTEL_SERVICE_NAME" "value" $serviceName) -}}
{{- $env = append $env (dict "name" "OTEL_TRACES_EXPORTER" "value" "otlp") -}}
{{- $env = append $env (dict "name" "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL" "value" "grpc") -}}
{{- $env = append $env (dict "name" "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT" "value" $endpoint) -}}
{{- if $attributePairs -}}
  {{- $env = append $env (dict "name" "OTEL_RESOURCE_ATTRIBUTES" "value" (join "," $attributePairs)) -}}
{{- end -}}
{{- if $tracing.controlChannel -}}
  {{- $env = append $env (dict "name" "PLATFORM_TRACING_CONTROL_CHANNEL" "value" $tracing.controlChannel) -}}
{{- end -}}
{{- if $tracing.spanAttributeValidationLevel -}}
  {{- $env = append $env (dict "name" "PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL" "value" $tracing.spanAttributeValidationLevel) -}}
{{- end -}}
{{/* Пять Instrumentation CR в Infra задают ровно этот список; менять его — отдельное решение. */}}
{{- $env = append $env (dict "name" "OTEL_PROPAGATORS" "value" "tracecontext,baggage,b3") -}}
{{/* Этот chart отвечает только за trace export. */}}
{{- $env = append $env (dict "name" "OTEL_METRICS_EXPORTER" "value" "none") -}}
{{- $env = append $env (dict "name" "OTEL_LOGS_EXPORTER" "value" "none") -}}
{{- toYaml $env -}}
{{- end -}}
