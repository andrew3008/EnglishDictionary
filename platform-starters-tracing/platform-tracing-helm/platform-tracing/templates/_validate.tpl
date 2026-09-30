{{- define "platform-tracing.v1._validateValues" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}

  {{- $allowedKeys := list "enabled" "controlChannel" "spanAttributeValidationLevel" "export" -}}
  {{- range $key, $_ := . -}}
    {{- if not (has $key $allowedKeys) -}}
      {{- fail (printf "%s platformTracing.%s is not a supported key; supported keys: %s" $prefix $key (join ", " $allowedKeys)) -}}
    {{- end -}}
  {{- end -}}

  {{- $channels := list "STATIC" "CONFIG_SERVER" "ACTUATOR" -}}
  {{- $channel := index . "controlChannel" -}}
  {{- if not (and (kindIs "string" $channel) (has $channel $channels)) -}}
    {{- fail (printf "%s platformTracing.controlChannel is required and must be one of %s; got %v" $prefix (join ", " $channels) $channel) -}}
  {{- end -}}

  {{- if hasKey . "spanAttributeValidationLevel" -}}
    {{- $levels := list "WARNING" "ERROR" "DISABLED" -}}
    {{- $level := index . "spanAttributeValidationLevel" -}}
    {{- if not (and (kindIs "string" $level) (has $level $levels)) -}}
      {{- fail (printf "%s platformTracing.spanAttributeValidationLevel must be one of %s; got %v" $prefix (join ", " $levels) $level) -}}
    {{- end -}}
  {{- end -}}

  {{- if hasKey . "export" -}}
    {{- $export := index . "export" -}}
    {{- if not (kindIs "map" $export) -}}
      {{- fail (printf "%s platformTracing.export must be a map; got %s" $prefix (kindOf $export)) -}}
    {{- end -}}
    {{- range $key, $_ := $export -}}
      {{- if ne $key "endpoint" -}}
        {{- fail (printf "%s platformTracing.export.%s is not a supported key; supported keys: endpoint" $prefix $key) -}}
      {{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}

{{- define "platform-tracing.v1._validateIdentity" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}

  {{- if not (kindIs "map" .) -}}
    {{- fail (printf "%s identity must be a map with serviceName, serviceVersion and deploymentEnvironmentName; got %s" $prefix (kindOf .)) -}}
  {{- end -}}
  {{- $allowedKeys := list "serviceName" "serviceVersion" "deploymentEnvironmentName" -}}
  {{- range $key, $_ := . -}}
    {{- if not (has $key $allowedKeys) -}}
      {{- fail (printf "%s identity.%s is not a supported key; supported keys: %s" $prefix $key (join ", " $allowedKeys)) -}}
    {{- end -}}
  {{- end -}}

  {{- $serviceName := index . "serviceName" -}}
  {{- if not (and (kindIs "string" $serviceName) (regexMatch "^\\S(.*\\S)?$" $serviceName)) -}}
    {{- fail (printf "%s identity.serviceName is required and must be a non-blank string without leading or trailing whitespace" $prefix) -}}
  {{- end -}}

  {{- range $key := list "serviceVersion" "deploymentEnvironmentName" -}}
    {{- $value := index $ $key -}}
    {{- if not (or (kindIs "invalid" $value) (eq (toString $value) "")) -}}
      {{- if not (kindIs "string" $value) -}}
        {{- fail (printf "%s identity.%s must be a string; got %s (quote the value)" $prefix $key (kindOf $value)) -}}
      {{- end -}}

      {{- if or (not (regexMatch "^[\\x21-\\x7e]+$" $value)) (regexMatch "[,%=]" $value) -}}
        {{- fail (printf "%s identity.%s must contain only printable ASCII without ',', '%%', '=' and whitespace; got %q" $prefix $key $value) -}}
      {{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}

{{- define "platform-tracing.v1._validateEnv" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}
  {{- range $position, $entry := . -}}
    {{- if not (kindIs "map" $entry) -}}
      {{- fail (printf "%s env[%d] must be a Kubernetes EnvVar map; got %s" $prefix $position (kindOf $entry)) -}}
    {{- end -}}

    {{- $name := index $entry "name" -}}
    {{- if not (and (kindIs "string" $name) (ne $name "")) -}}
      {{- fail (printf "%s env[%d].name is required and must be a non-empty string" $prefix $position) -}}
    {{- end -}}

    {{- if or (hasPrefix "OTEL_" $name) (hasPrefix "PLATFORM_TRACING_" $name) -}}
      {{- fail (printf "%s env variable %s is reserved: OTEL_ and PLATFORM_TRACING_ variables are managed by platform-tracing" $prefix $name) -}}
    {{- end -}}
  {{- end -}}
{{- end -}}

{{- define "platform-tracing.v1._endpoint" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}
  {{- $path := "platformTracing.export.endpoint" -}}
  {{- $export := index . "export" | default dict -}}

  {{- if not (hasKey $export "endpoint") -}}
    {{- print "http://otel-collector.ops-system.svc.cluster.local:4317" -}}
  {{- else -}}
    {{- $endpoint := index $export "endpoint" -}}
    {{- if not (kindIs "string" $endpoint) -}}
      {{- fail (printf "%s %s must be a string; got %s" $prefix $path (kindOf $endpoint)) -}}
    {{- end -}}
    {{- if not (regexMatch "^\\S+$" $endpoint) -}}
      {{- fail (printf "%s %s must be non-blank and must not contain whitespace; remove the key to use the default Collector" $prefix $path) -}}
    {{- end -}}
    {{- if not (regexMatch "^https?://" $endpoint) -}}
      {{- fail (printf "%s %s must start with http:// or https:// in lower case" $prefix $path) -}}
    {{- end -}}
    {{- if contains "@" $endpoint -}}
      {{- fail (printf "%s %s must not contain credentials" $prefix $path) -}}
    {{- end -}}
    {{- if or (contains "?" $endpoint) (contains "#" $endpoint) -}}
      {{- fail (printf "%s %s must not contain a query or fragment" $prefix $path) -}}
    {{- end -}}

    {{- $hostPort := regexReplaceAll "^https?://" $endpoint "" -}}
    {{- if contains "/" $hostPort -}}
      {{- fail (printf "%s %s must not contain a path; the grpc exporter takes scheme://host:port" $prefix $path) -}}
    {{- end -}}
    {{- if not (regexMatch "^(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9.-]+)(:[0-9]+)?$" $hostPort) -}}
      {{- fail (printf "%s %s must be scheme://host[:port]; IPv6 hosts must be in brackets" $prefix $path) -}}
    {{- end -}}

    {{- $port := trimPrefix ":" (regexFind ":[0-9]+$" $hostPort) -}}
    {{- if and (ne $port "") (not (and (regexMatch "^[1-9][0-9]{0,4}$" $port) (le (atoi $port) 65535))) -}}
      {{- fail (printf "%s %s port must be between 1 and 65535 without leading zeros" $prefix $path) -}}
    {{- end -}}

    {{- print $endpoint -}}
  {{- end -}}
{{- end -}}
