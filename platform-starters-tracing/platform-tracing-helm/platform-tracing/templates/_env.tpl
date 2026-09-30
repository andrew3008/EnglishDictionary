{{- define "platform-tracing.v1.containerEnv" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}

  {{- if not (kindIs "map" .) -}}
    {{- fail (printf "%s argument must be a map; got %s" $prefix (kindOf .)) -}}
  {{- end -}}
  {{- $allowedKeys := list "values" "identity" "env" -}}
  {{- range $key, $_ := . -}}
    {{- if not (has $key $allowedKeys) -}}
      {{- fail (printf "%s argument.%s is not a supported key; supported keys: %s" $prefix $key (join ", " $allowedKeys)) -}}
    {{- end -}}
  {{- end -}}

  {{- $values := default (dict) .values -}}
  {{- if not (kindIs "map" $values) -}}
    {{- fail (printf "%s platformTracing must be a map; got %s" $prefix (kindOf $values)) -}}
  {{- end -}}

  {{- $enabled := dig "enabled" false $values -}}
  {{- if not (kindIs "bool" $enabled) -}}
    {{- fail (printf "%s platformTracing.enabled must be a boolean; got %s" $prefix (kindOf $enabled)) -}}
  {{- end -}}

  {{- $env := default (list) .env -}}
  {{- if not (kindIs "slice" $env) -}}
    {{- fail (printf "%s env must be a list of Kubernetes EnvVar; got %s" $prefix (kindOf $env)) -}}
  {{- end -}}

  {{- if not $enabled -}}
    {{- toYaml $env -}}
  {{- else -}}
    {{- include "platform-tracing.v1._validateValues" $values -}}
    {{- include "platform-tracing.v1._validateIdentity" .identity -}}
    {{- include "platform-tracing.v1._validateEnv" $env -}}

    {{- $attributePairs := list -}}
    {{- with .identity.serviceVersion -}}
      {{- $attributePairs = append $attributePairs (printf "service.version=%s" .) -}}
    {{- end -}}
    {{- with .identity.deploymentEnvironmentName -}}
      {{- $attributePairs = append $attributePairs (printf "deployment.environment.name=%s" .) -}}
    {{- end -}}

    {{- $managed := list
          (dict "name" "OTEL_SERVICE_NAME" "value" .identity.serviceName)
          (dict "name" "OTEL_TRACES_EXPORTER" "value" "otlp")
          (dict "name" "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL" "value" "grpc")
          (dict "name" "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT" "value" (include "platform-tracing.v1._endpoint" $values)) -}}
    {{- if $attributePairs -}}
      {{- $managed = append $managed (dict "name" "OTEL_RESOURCE_ATTRIBUTES" "value" (join "," $attributePairs)) -}}
    {{- end -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_CONTROL_CHANNEL" "value" $values.controlChannel) -}}
    {{- if hasKey $values "spanAttributeValidationLevel" -}}
      {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL" "value" $values.spanAttributeValidationLevel) -}}
    {{- end -}}

    {{- $managed = concat $managed (list
          (dict "name" "OTEL_PROPAGATORS" "value" "tracecontext,baggage,b3")
          (dict "name" "OTEL_METRICS_EXPORTER" "value" "none")
          (dict "name" "OTEL_LOGS_EXPORTER" "value" "none")) -}}

    {{- toYaml (concat $managed $env) -}}
  {{- end -}}
{{- end -}}
