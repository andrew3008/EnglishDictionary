{{- define "platform-tracing.v1._routeLimits" -}}
maxRouteEntries: 100
maxRoutePrefixUtf8Bytes: 255
{{- end -}}

{{- define "platform-tracing.v1._policyEnv" -}}
  {{- $managed := list -}}
  {{- $sampling := default (dict) .sampling -}}
  {{- if hasKey $sampling "enabled" -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_SAMPLING_ENABLED" "value" (toString $sampling.enabled)) -}}
  {{- end -}}
  {{- if hasKey $sampling "ratio" -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_SAMPLING_RATIO" "value" (toString $sampling.ratio)) -}}
  {{- end -}}
  {{- if hasKey $sampling "routeRatios" -}}
    {{- $routeRatioPairs := list -}}
    {{- range $route := keys $sampling.routeRatios | sortAlpha -}}
      {{- $routeRatioPairs = append $routeRatioPairs (printf "%s=%v" $route (index $sampling.routeRatios $route)) -}}
    {{- end -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_SAMPLING_ROUTE_RATIOS" "value" (join "," $routeRatioPairs)) -}}
  {{- end -}}
  {{- if hasKey $sampling "dropPaths" -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_JAVAAGENT_SAMPLING_DROP_PATHS" "value" (join "," $sampling.dropPaths)) -}}
  {{- end -}}
  {{- if hasKey $sampling "forceRecordValues" -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_JAVAAGENT_SAMPLING_FORCE_RECORD_VALUES" "value" (join "," $sampling.forceRecordValues)) -}}
  {{- end -}}

  {{- $validation := default (dict) .validation -}}
  {{- if hasKey $validation "level" -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL" "value" $validation.level) -}}
  {{- end -}}

  {{- $exporter := default (dict) .exporter -}}
  {{- if hasKey $exporter "enabled" -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_EXPORTER_ENABLED" "value" (toString $exporter.enabled)) -}}
  {{- end -}}

  {{- $propagation := default (dict) .propagation -}}
  {{- $traceControlHeaders := default (dict) $propagation.traceControlHeaders -}}
  {{- if hasKey $traceControlHeaders "enabled" -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_PROPAGATION_TRACE_CONTROL_HEADERS_ENABLED" "value" (toString $traceControlHeaders.enabled)) -}}
  {{- end -}}

  {{- $diagnostics := default (dict) .diagnostics -}}
  {{- if hasKey $diagnostics "logLevel" -}}
    {{- $managed = append $managed (dict "name" "PLATFORM_TRACING_DIAGNOSTICS_LOG_LEVEL" "value" $diagnostics.logLevel) -}}
  {{- end -}}

  {{- toYaml $managed -}}
{{- end -}}

{{- define "platform-tracing.v1._validatePolicy" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}
  {{- $allowedKeys := list "sampling" "validation" "exporter" "propagation" "diagnostics" -}}
  {{- range $key, $_ := . -}}
    {{- if not (has $key $allowedKeys) -}}
      {{- fail (printf "%s platformTracing.policy.%s is not a supported key; supported keys: %s" $prefix $key (join ", " $allowedKeys)) -}}
    {{- end -}}
  {{- end -}}

  {{- if hasKey . "sampling" -}}
    {{- $sampling := index . "sampling" -}}
    {{- if not (kindIs "map" $sampling) -}}
      {{- fail (printf "%s platformTracing.policy.sampling must be a map; got %s" $prefix (kindOf $sampling)) -}}
    {{- end -}}
    {{- $samplingKeys := list "enabled" "ratio" "routeRatios" "dropPaths" "forceRecordValues" -}}
    {{- range $key, $_ := $sampling -}}
      {{- if not (has $key $samplingKeys) -}}
        {{- fail (printf "%s platformTracing.policy.sampling.%s is not supported; supported keys: %s" $prefix $key (join ", " $samplingKeys)) -}}
      {{- end -}}
    {{- end -}}
    {{- if and (hasKey $sampling "enabled") (not (kindIs "bool" $sampling.enabled)) -}}
      {{- fail (printf "%s platformTracing.policy.sampling.enabled must be a boolean; got %s" $prefix (kindOf $sampling.enabled)) -}}
    {{- end -}}
    {{- if hasKey $sampling "ratio" -}}
      {{- include "platform-tracing.v1._validateRatio" (dict "path" "platformTracing.policy.sampling.ratio" "value" $sampling.ratio) -}}
    {{- end -}}
    {{- if hasKey $sampling "routeRatios" -}}
      {{- $routeRatios := $sampling.routeRatios -}}
      {{- if not (kindIs "map" $routeRatios) -}}
        {{- fail (printf "%s platformTracing.policy.sampling.routeRatios must be a map; got %s" $prefix (kindOf $routeRatios)) -}}
      {{- end -}}
      {{- $routeLimits := include "platform-tracing.v1._routeLimits" . | fromYaml -}}
      {{- $maxRouteEntries := int (index $routeLimits "maxRouteEntries") -}}
      {{- $maxRoutePrefixUtf8Bytes := int (index $routeLimits "maxRoutePrefixUtf8Bytes") -}}
      {{- if gt (len $routeRatios) $maxRouteEntries -}}
        {{- fail (printf "%s platformTracing.policy.sampling.routeRatios must contain at most %d entries; got %d" $prefix $maxRouteEntries (len $routeRatios)) -}}
      {{- end -}}
      {{- range $route, $ratio := $routeRatios -}}
        {{- if or (eq $route "") (contains "," $route) (contains "=" $route) -}}
          {{- fail (printf "%s platformTracing.policy.sampling.routeRatios key %q must be non-empty and must not contain ',' or '='" $prefix $route) -}}
        {{- end -}}
        {{- if gt (len $route) $maxRoutePrefixUtf8Bytes -}}
          {{- fail (printf "%s platformTracing.policy.sampling.routeRatios key must be at most %d UTF-8 bytes; got %d" $prefix $maxRoutePrefixUtf8Bytes (len $route)) -}}
        {{- end -}}
        {{- include "platform-tracing.v1._validateRatio" (dict "path" (printf "platformTracing.policy.sampling.routeRatios[%q]" $route) "value" $ratio) -}}
      {{- end -}}
    {{- end -}}
    {{- range $key := list "dropPaths" "forceRecordValues" -}}
      {{- if hasKey $sampling $key -}}
        {{- $items := index $sampling $key -}}
        {{- if not (kindIs "slice" $items) -}}
          {{- fail (printf "%s platformTracing.policy.sampling.%s must be a list of strings; got %s" $prefix $key (kindOf $items)) -}}
        {{- end -}}
        {{- range $position, $item := $items -}}
          {{- if not (and (kindIs "string" $item) (ne $item "") (not (contains "," $item))) -}}
            {{- fail (printf "%s platformTracing.policy.sampling.%s[%d] must be a non-empty string without ','" $prefix $key $position) -}}
          {{- end -}}
        {{- end -}}
      {{- end -}}
    {{- end -}}
  {{- end -}}

  {{- include "platform-tracing.v1._validatePolicyControls" . -}}
{{- end -}}

{{- define "platform-tracing.v1._validateRatio" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}
  {{- $numeric := or (kindIs "float64" .value) (kindIs "int" .value) (kindIs "int64" .value) -}}
  {{- if not $numeric -}}
    {{- fail (printf "%s %s must be a number between 0 and 1; got %s" $prefix .path (kindOf .value)) -}}
  {{- end -}}
  {{- $ratio := float64 .value -}}
  {{- if or (lt $ratio 0.0) (gt $ratio 1.0) -}}
    {{- fail (printf "%s %s must be between 0 and 1; got %v" $prefix .path .value) -}}
  {{- end -}}
{{- end -}}

{{- define "platform-tracing.v1._validatePolicyControls" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}
  {{- if hasKey . "validation" -}}
    {{- $validation := index . "validation" -}}
    {{- if not (kindIs "map" $validation) -}}
      {{- fail (printf "%s platformTracing.policy.validation must be a map; got %s" $prefix (kindOf $validation)) -}}
    {{- end -}}
    {{- range $key, $_ := $validation -}}
      {{- if ne $key "level" -}}
        {{- fail (printf "%s platformTracing.policy.validation.%s is not supported; supported keys: level" $prefix $key) -}}
      {{- end -}}
    {{- end -}}
    {{- if hasKey $validation "level" -}}
      {{- $levels := list "WARNING" "ERROR" "DISABLED" -}}
      {{- if not (and (kindIs "string" $validation.level) (has $validation.level $levels)) -}}
        {{- fail (printf "%s platformTracing.policy.validation.level must be one of %s; got %v" $prefix (join ", " $levels) $validation.level) -}}
      {{- end -}}
    {{- end -}}
  {{- end -}}

  {{- if hasKey . "exporter" -}}
    {{- include "platform-tracing.v1._validateEnabledMap" (dict "path" "platformTracing.policy.exporter" "value" .exporter) -}}
  {{- end -}}

  {{- if hasKey . "propagation" -}}
    {{- $propagation := index . "propagation" -}}
    {{- if not (kindIs "map" $propagation) -}}
      {{- fail (printf "%s platformTracing.policy.propagation must be a map; got %s" $prefix (kindOf $propagation)) -}}
    {{- end -}}
    {{- range $key, $_ := $propagation -}}
      {{- if ne $key "traceControlHeaders" -}}
        {{- fail (printf "%s platformTracing.policy.propagation.%s is not supported; supported keys: traceControlHeaders" $prefix $key) -}}
      {{- end -}}
    {{- end -}}
    {{- if hasKey $propagation "traceControlHeaders" -}}
      {{- include "platform-tracing.v1._validateEnabledMap" (dict "path" "platformTracing.policy.propagation.traceControlHeaders" "value" $propagation.traceControlHeaders) -}}
    {{- end -}}
  {{- end -}}

  {{- if hasKey . "diagnostics" -}}
    {{- $diagnostics := index . "diagnostics" -}}
    {{- if not (kindIs "map" $diagnostics) -}}
      {{- fail (printf "%s platformTracing.policy.diagnostics must be a map; got %s" $prefix (kindOf $diagnostics)) -}}
    {{- end -}}
    {{- range $key, $_ := $diagnostics -}}
      {{- if ne $key "logLevel" -}}
        {{- fail (printf "%s platformTracing.policy.diagnostics.%s is not supported; supported keys: logLevel" $prefix $key) -}}
      {{- end -}}
    {{- end -}}
    {{- if hasKey $diagnostics "logLevel" -}}
      {{- $levels := list "OFF" "ERROR" "WARN" "INFO" "DEBUG" "TRACE" -}}
      {{- if not (and (kindIs "string" $diagnostics.logLevel) (has $diagnostics.logLevel $levels)) -}}
        {{- fail (printf "%s platformTracing.policy.diagnostics.logLevel must be one of %s; got %v" $prefix (join ", " $levels) $diagnostics.logLevel) -}}
      {{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}

{{- define "platform-tracing.v1._validateEnabledMap" -}}
  {{- $prefix := "platform-tracing.v1.containerEnv:" -}}
  {{- if not (kindIs "map" .value) -}}
    {{- fail (printf "%s %s must be a map; got %s" $prefix .path (kindOf .value)) -}}
  {{- end -}}
  {{- range $key, $_ := .value -}}
    {{- if ne $key "enabled" -}}
      {{- fail (printf "%s %s.%s is not supported; supported keys: enabled" $prefix $.path $key) -}}
    {{- end -}}
  {{- end -}}
  {{- if and (hasKey .value "enabled") (not (kindIs "bool" .value.enabled)) -}}
    {{- fail (printf "%s %s.enabled must be a boolean; got %s" $prefix .path (kindOf .value.enabled)) -}}
  {{- end -}}
{{- end -}}
