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
JVM heap size in MB derived from container memory limit and heapPercent.
Usage: -Xmx{{ include "spring-ai-rag.jvm-heap" . }}
Returns e.g. "1536m" for a 2Gi limit at 75% heapPercent.
*/}}
{{- define "spring-ai-rag.jvm-heap" -}}
{{- $memLimit := default "2Gi" .Values.resources.limits.memory }}
{{- $heapPct  := default 75 .Values.jvm.heapPercent }}
{{- $memMi    := "" }}
{{- if hasSuffix "Gi" $memLimit }}
{{-   $memMi = trimSuffix "Gi" $memLimit | int | mul 1024 | print }}
{{- else if hasSuffix "Mi" $memLimit }}
{{-   $memMi = trimSuffix "Mi" $memLimit | print }}
{{- else }}
{{-   $memMi = "2048" }}
{{- end }}
{{- $heapMi := div (mul (int $memMi) (int $heapPct)) 100 }}
{{- print $heapMi "m" }}
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
