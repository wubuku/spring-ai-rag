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
Batch 937 删除了三个这里定义、但没有任何模板 `include` 过的 helper：

  spring-ai-rag.jvm-heap      按 jvm.heapPercent 把容器内存换算成堆上限
  spring-ai-rag.jvm-raw       只被 jvm-heap 调用
  spring-ai-rag.spring-profile  读 .Values.springProfile，生成 --spring.profiles.active

它们描述的是一套**与实际生效的机制不同**的策略：真正设置堆上限的是
`deployment.yaml` 里直接读 `.Values.jvm.maxHeap` 的那一行，而 profile 是通过
`secret.yaml` 注入的 `SPRING_PROFILES_ACTIVE` 环境变量设置的——values.yaml 里只有
`secrets.springProfile`，根本没有顶层的 `springProfile`。

留着它们的代价不止于死代码：`jvm.heapPercent` 是一个**没有任何 values 声明、只被死
代码读取**的旋钮，运维可以 `--set jvm.heapPercent=50` 而它什么都不会发生；`values.yaml`
里 `jvm.maxHeap` 的注释"Production profile sets this to 3072m (75% of 4Gi limit)"也
因此读起来像是那套换算逻辑在起作用。

`node scripts/verify-helm-helper-liveness.mjs` 现在强制"每个 define 都必须被 include"。
*/}}
