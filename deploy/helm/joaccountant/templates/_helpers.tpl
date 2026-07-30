{{/*
=====================================================================
JOAccountant Backend — Helper templates
Finding #22 (Helm chart)
=====================================================================
*/}}

{{/* Expand the name of the chart (truncated to 63 chars for K8s naming). */}}
{{- define "joaccountant.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Fullname: release-name + chart-name (or override). */}}
{{- define "joaccountant.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := include "joaccountant.name" . }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/* Chart name + version label (used by Helm best practices). */}}
{{- define "joaccountant.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Common labels — applied to all resources. */}}
{{- define "joaccountant.labels" -}}
helm.sh/chart: {{ include "joaccountant.chart" . }}
{{ include "joaccountant.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: joaccountant
{{- end }}

{{/* Selector labels — must match pod template + service selector. */}}
{{- define "joaccountant.selectorLabels" -}}
app.kubernetes.io/name: {{ include "joaccountant.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
