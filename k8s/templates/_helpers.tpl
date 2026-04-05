{{/*
Expand the name of the chart.
*/}}
{{- define "spring-ai-rag.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "spring-ai-rag.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create the name of the service account to use.
*/}}
{{- define "spring-ai-rag.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "spring-ai-rag.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "spring-ai-rag.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "spring-ai-rag.labels" -}}
helm.sh/chart: {{ include "spring-ai-rag.chart" . }}
{{ include "spring-ai-rag.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels (shared by Deployment, Service, etc.).
*/}}
{{- define "spring-ai-rag.selectorLabels" -}}
app.kubernetes.io/name: {{ include "spring-ai-rag.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
JVM max heap in MB. Converts container memory limit (e.g. "2Gi") to heap MB
at jvm.heapPercent (default 75%).
Returns e.g. "1536m".
*/}}
{{- define "spring-ai-rag.jvm-heap" -}}
{{- $raw := include "spring-ai-rag.jvm-raw" . }}
{{- $mb  := div (mul (int $raw) (int (default 75 .Values.jvm.heapPercent))) 100 }}
{{- printf "%dm" $mb }}
{{- end }}

{{- define "spring-ai-rag.jvm-raw" -}}
{{- $v := toString .Values.resources.limits.memory }}
{{- if hasSuffix "Gi" $v }}{{ trimSuffix "Gi" $v | int | mul 1024 }}{{- end }}
{{- if hasSuffix "gi" $v }}{{ trimSuffix "gi" $v | int | mul 1024 }}{{- end }}
{{- if hasSuffix "Mi" $v }}{{ trimSuffix "Mi" $v | int }}{{- end }}
{{- if hasSuffix "mi" $v }}{{ trimSuffix "mi" $v | int }}{{- end }}
{{- end }}

{{/*
Build the Spring profile argument, if any.
*/}}
{{- define "spring-ai-rag.spring-profile" -}}
{{- if .Values.springProfile }}
{{- printf "--spring.profiles.active=%s" .Values.springProfile }}
{{- else }}
{{- "" }}
{{- end }}
{{- end }}
