{{- define "platform-tracing-consumer.env" -}}
{{- include "platform-tracing.v1.containerEnv" (dict
      "values" .Values.platformTracing
      "env" .Values.containerEnv) -}}
{{- end -}}
