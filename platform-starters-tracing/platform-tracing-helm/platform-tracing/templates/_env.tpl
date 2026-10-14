{{- define "platform-tracing.v1.containerEnv" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}

  {{- if not (kindIs "map" .) -}}
    {{- fail (printf "%s argument must be a map; got %s" $prefix (kindOf .)) -}}
  {{- end -}}
  {{- $allowedKeys := list "values" "env" -}}
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

  {{- include "platform-tracing.v1._validateValues" $values -}}
  {{- if not $enabled -}}
    {{- toYaml $env -}}
  {{- else -}}
    {{- include "platform-tracing.v1._validateEnv" $env -}}
    {{- $managed := include "platform-tracing.v1._policyEnv" (default (dict) $values.policy) | fromYamlArray -}}

    {{- $managed = concat $managed (list
          (dict "name" "OTEL_EXPORTER_OTLP_PROTOCOL" "value" "grpc")
          (dict "name" "OTEL_PROPAGATORS" "value" "tracecontext,baggage,b3")
          (dict "name" "OTEL_METRICS_EXPORTER" "value" "none")
          (dict "name" "OTEL_LOGS_EXPORTER" "value" "none")) -}}

    {{- toYaml (concat $managed $env) -}}
  {{- end -}}
{{- end -}}
