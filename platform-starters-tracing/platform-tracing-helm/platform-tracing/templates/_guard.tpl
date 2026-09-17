{{- define "platform-tracing.v1.assertLaunchEnv" -}}
{{- $jvmVariableNames := list "JAVA_TOOL_OPTIONS" "JDK_JAVA_OPTIONS" "_JAVA_OPTIONS" -}}
{{- $managedOtelKeys := list
      "otel.propagators"
      "otel.exporter.otlp.traces.endpoint"
      "otel.exporter.otlp.traces.protocol"
      "otel.traces.exporter"
      "otel.metrics.exporter"
      "otel.logs.exporter"
      "otel.service.name"
      "otel.resource.attributes"
      "otel.javaagent.configuration-file"
      "otel.experimental.config.file"
      "otel.sdk.disabled"
      "otel.javaagent.enabled" -}}
{{- $seen := dict -}}
{{- range .env -}}
  {{- $name := .name -}}
  {{- if hasKey $seen $name -}}
    {{- fail (printf "platform-tracing.v1.assertLaunchEnv: env contains duplicate variable %s" $name) -}}
  {{- end -}}
  {{- $_ := set $seen $name true -}}
  {{- if or (hasPrefix "OTEL_" $name) (eq $name "PLATFORM_TRACING_CONTROL_CHANNEL") (eq $name "PLATFORM_TRACING_CONFIGURATION_AUTHORITY") (eq $name "PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL") -}}
    {{- fail (printf "platform-tracing.v1.assertLaunchEnv: env variable %s is reserved for platform-tracing.v1.env; remove it from the application env" $name) -}}
  {{- end -}}
  {{- if has $name $jvmVariableNames -}}
    {{- if .valueFrom -}}
      {{- fail (printf "platform-tracing.v1.assertLaunchEnv: %s must be a literal value; valueFrom is opaque at render time and cannot prove the absence of a second -javaagent or a managed -Dotel.* key" $name) -}}
    {{- end -}}
    {{- $value := default "" .value -}}
    {{- if eq $name "_JAVA_OPTIONS" -}}
      {{- if ne (trim $value) "" -}}
        {{- fail "platform-tracing.v1.assertLaunchEnv: _JAVA_OPTIONS must be empty, it overrides -javaagent and every managed -D flag baked into the image" -}}
      {{- end -}}
    {{- else -}}
      {{- if contains "-javaagent" $value -}}
        {{- fail (printf "platform-tracing.v1.assertLaunchEnv: %s must not contain -javaagent, the controlled agent is already wired by the image" $name) -}}
      {{- end -}}
      {{- range $managedOtelKeys -}}
        {{- if contains (printf "-D%s" .) $value -}}
          {{- fail (printf "platform-tracing.v1.assertLaunchEnv: %s must not set -D%s, this key is managed by the platform" $name .) -}}
        {{- end -}}
      {{- end -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- end -}}

{{- define "platform-tracing.v1.assertContainer" -}}
{{- $container := required "platform-tracing.v1.assertContainer: \"container\" is required" .container -}}
{{- if $container.envFrom -}}
  {{- fail "platform-tracing.v1.assertContainer: envFrom is not allowed; convert references to explicit valueFrom during onboarding so render can see reserved names" -}}
{{- end -}}
{{- if $container.command -}}
  {{- fail "platform-tracing.v1.assertContainer: command must stay unset; it replaces the Jib launch model and can drop the controlled -javaagent" -}}
{{- end -}}
{{- if $container.args -}}
  {{- fail "platform-tracing.v1.assertContainer: args must stay unset; they replace the Jib launch model and can drop the controlled -javaagent" -}}
{{- end -}}
{{- end -}}

