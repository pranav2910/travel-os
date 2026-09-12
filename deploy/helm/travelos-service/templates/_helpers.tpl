{{- define "svc.name" -}}
{{- required "values.name is required (set by the umbrella chart alias)" .Values.name -}}
{{- end -}}

{{- define "svc.image" -}}
{{- $registry := default .Values.global.image.registry .Values.image.registry -}}
{{- $repo := default (include "svc.name" .) .Values.image.repository -}}
{{- $tag := default .Values.global.image.tag .Values.image.tag -}}
{{- $tag = required "an image tag is required: set global.image.tag to a git sha" $tag -}}
{{- printf "%s/%s:%s" $registry $repo $tag -}}
{{- end -}}

{{- define "svc.tag" -}}
{{- default .Values.global.image.tag .Values.image.tag -}}
{{- end -}}

{{- define "svc.labels" -}}
app: {{ include "svc.name" . }}
app.kubernetes.io/name: {{ include "svc.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: travelos
app.kubernetes.io/version: {{ include "svc.tag" . | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "svc.selectorLabels" -}}
app: {{ include "svc.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
