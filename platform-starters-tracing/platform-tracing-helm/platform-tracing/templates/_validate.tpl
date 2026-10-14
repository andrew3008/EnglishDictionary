{{- define "platform-tracing.v1._validateValues" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}

  {{- $allowedKeys := list "enabled" "policy" -}}
  {{- range $key, $_ := . -}}
    {{- if not (has $key $allowedKeys) -}}
      {{- fail (printf "%s platformTracing.%s is not a supported key; supported keys: %s" $prefix $key (join ", " $allowedKeys)) -}}
    {{- end -}}
  {{- end -}}

  {{- if hasKey . "policy" -}}
    {{- $policy := index . "policy" -}}
    {{- if not (kindIs "map" $policy) -}}
      {{- fail (printf "%s platformTracing.policy must be a map; got %s" $prefix (kindOf $policy)) -}}
    {{- end -}}
    {{- include "platform-tracing.v1._validatePolicy" $policy -}}
  {{- end -}}
{{- end -}}

{{- define "platform-tracing.v1._validateEnv" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}
  {{- $managedOtelNames := list
        "OTEL_EXPORTER_OTLP_PROTOCOL"
        "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL"
        "OTEL_PROPAGATORS"
        "OTEL_METRICS_EXPORTER"
        "OTEL_LOGS_EXPORTER" -}}
  {{- range $position, $entry := . -}}
    {{- if not (kindIs "map" $entry) -}}
      {{- fail (printf "%s env[%d] must be a Kubernetes EnvVar map; got %s" $prefix $position (kindOf $entry)) -}}
    {{- end -}}

    {{- $name := index $entry "name" -}}
    {{- if not (and (kindIs "string" $name) (ne $name "")) -}}
      {{- fail (printf "%s env[%d].name is required and must be a non-empty string" $prefix $position) -}}
    {{- end -}}

    {{- if or (hasPrefix "PLATFORM_TRACING_" $name) (has $name $managedOtelNames) -}}
      {{- fail (printf "%s env variable %s is managed by platform-tracing" $prefix $name) -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
