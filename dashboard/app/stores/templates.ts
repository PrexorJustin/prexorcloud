import { defineStore } from "pinia"
import type { Schema } from "@prexorcloud/api-sdk"
import type { TemplateFile, TemplateInheritanceNode, TemplateSearchResult, TemplateVersion, VariableDef } from "~/types/api"
import { toast } from "vue-sonner"
import { getAuthToken } from "~/lib/auth-storage"
import { t } from "~/lib/translate"

type Template = Schema<'TemplateDto'>

export const useTemplatesStore = defineStore("templates", () => {
  const templates = ref<Template[]>([])
  const loading = ref(false)

  async function fetchTemplates() {
    loading.value = true
    try {
      const { data } = await useApiClient().GET('/api/v1/templates')
      templates.value = data?.data ?? []
    }
    catch { toast.error(t("store.templates.loadFailed")) }
    finally {
      loading.value = false
    }
  }

  async function createTemplate(body: { name: string; description: string; platform: string }) {
    const { data: created } = await useApiClient().POST('/api/v1/templates', { body: body as Schema<'TemplateConfig'> })
    toast.success(t("store.templates.created"), { description: t("store.templates.createdDesc", { name: body.name }) })
    await fetchTemplates()
    return created
  }

  async function updateTemplate(name: string, body: { description?: string; platform?: string }) {
    const { data: updated } = await useApiClient().PATCH('/api/v1/templates/{name}', {
      params: { path: { name } },
      body,
    })
    toast.success(t("store.templates.updated"), { description: t("store.templates.updatedDesc", { name }) })
    await fetchTemplates()
    return updated
  }

  async function deleteTemplate(name: string) {
    await useApiClient().DELETE('/api/v1/templates/{name}', { params: { path: { name } } })
    toast.success(t("store.templates.deleted"), { description: t("store.templates.deletedDesc", { name }) })
    await fetchTemplates()
  }

  async function fetchVersions(name: string): Promise<TemplateVersion[]> {
    const { data } = await useApiClient().GET('/api/v1/templates/{name}/versions', { params: { path: { name } } })
    return (data ?? []) as TemplateVersion[]
  }

  async function fetchSnapshotFiles(name: string, hash: string, path: string): Promise<TemplateFile[]> {
    const { data } = await useApiClient().GET('/api/v1/templates/{name}/files', {
      params: { path: { name }, query: { path: path || undefined, version: hash } },
    })
    return (data ?? []) as TemplateFile[]
  }

  async function fetchSnapshotFileContent(name: string, hash: string, filePath: string): Promise<string> {
    return readTextContent(name, filePath, hash)
  }

  async function deleteVersion(name: string, hash: string) {
    await useApiClient().DELETE('/api/v1/templates/{name}/versions/{hash}', {
      params: { path: { name, hash } },
    })
    toast.success(t("store.templates.versionDeleted"))
  }

  async function rollbackToVersion(name: string, hash: string) {
    await useApiClient().POST('/api/v1/templates/{name}/rollback', {
      params: { path: { name } },
      body: { hash },
    })
    toast.success(t("store.templates.restored"), { description: t("store.templates.restoredDesc", { name }) })
    await fetchTemplates()
  }

  async function fetchFileContent(name: string, filePath: string): Promise<string> {
    return readTextContent(name, filePath)
  }

  // The /files/content endpoint returns text/plain; openapi-fetch types `data`
  // generically so we narrow once here instead of double-casting at every site.
  async function readTextContent(name: string, filePath: string, version?: string): Promise<string> {
    const { data } = await useApiClient().GET('/api/v1/templates/{name}/files/content', {
      params: { path: { name }, query: { path: filePath, ...(version ? { version } : {}) } },
    })
    return (data ?? '') as string
  }

  async function uploadFile(name: string, filePath: string, content: string) {
    await useApiClient().PUT('/api/v1/templates/{name}/files/content', {
      params: { path: { name }, query: { path: filePath } },
      body: { content },
    })
  }

  async function uploadFiles(name: string, targetPath: string, fileList: FileList) {
    const config = useRuntimeConfig()
    const apiBase = config.public.apiBase as string
    const token = getAuthToken()

    const formData = new FormData()
    for (const file of fileList) {
      formData.append("file", file)
    }

    const url = `${apiBase}/api/v1/templates/${name}/files/upload?path=${encodeURIComponent(targetPath)}&rehash=true`
    const res = await fetch(url, {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    })
    if (!res.ok) throw new Error(`Upload failed: ${res.status}`)
    toast.success(t("store.templates.filesUploaded"), { description: t("store.templates.filesUploadedDesc", { count: fileList.length }, fileList.length) })
    await fetchTemplates()
  }

  async function extractZip(name: string, filePath: string) {
    const { data: result } = await useApiClient().POST('/api/v1/templates/{name}/files/extract', {
      params: { path: { name }, query: { path: filePath } },
    })
    const files = (result as any)?.files ?? 0
    toast.success(t("store.templates.zipExtracted"), { description: t("store.templates.zipExtractedDesc", { count: files }, files) })
    await fetchTemplates()
    return result
  }

  async function renameFile(name: string, from: string, to: string) {
    await useApiClient().POST('/api/v1/templates/{name}/files/rename', {
      params: { path: { name } },
      body: { from, to },
    })
  }

  async function rehash(name: string) {
    await useApiClient().POST('/api/v1/templates/{name}/rehash', { params: { path: { name } } })
    toast.success(t("store.templates.rehashed"), { description: t("store.templates.rehashedDesc", { name }) })
    await fetchTemplates()
  }

  function downloadFileUrl(name: string, filePath: string): string {
    const config = useRuntimeConfig()
    const apiBase = config.public.apiBase as string
    const token = getAuthToken()
    return `${apiBase}/api/v1/templates/${name}/files/download?path=${encodeURIComponent(filePath)}${token ? `&token=${token}` : ""}`
  }

  async function fetchVariables(name: string): Promise<VariableDef[]> {
    const { data } = await useApiClient().GET('/api/v1/templates/{name}/variables', { params: { path: { name } } })
    return (data ?? []) as VariableDef[]
  }

  // PUT replaces the whole typed definition set. The backend answers 422 with a
  // useful message (ENUM without enumValues, a default that fails its type, a
  // duplicate key, …). The shared throwing client discards that body, so we use
  // a raw fetch here — mirroring uploadFiles/importTemplate above — to read the
  // controller's message and re-throw it for the caller to surface.
  async function saveVariables(name: string, variables: VariableDef[]): Promise<void> {
    const config = useRuntimeConfig()
    const apiBase = config.public.apiBase as string
    const token = getAuthToken()
    const res = await fetch(`${apiBase}/api/v1/templates/${encodeURIComponent(name)}/variables`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(variables),
    })
    if (!res.ok) {
      let message = t("store.templates.variablesSaveFailed")
      try {
        const body = await res.json() as { error?: { message?: string } }
        if (body?.error?.message) message = body.error.message
      } catch { /* keep the generic message */ }
      throw new Error(message)
    }
    toast.success(t("store.templates.variablesSaved"))
  }

  async function scanVariables(name: string): Promise<string[]> {
    const { data } = await useApiClient().GET('/api/v1/templates/{name}/variables/scan', { params: { path: { name } } })
    return (data ?? []) as string[]
  }

  async function fetchInheritance(name: string): Promise<TemplateInheritanceNode[]> {
    const { data } = await useApiClient().GET('/api/v1/templates/{name}/inheritance', { params: { path: { name } } })
    return (data ?? []) as TemplateInheritanceNode[]
  }

  async function searchFiles(name: string, query: string, maxResults = 50): Promise<TemplateSearchResult[]> {
    const { data } = await useApiClient().GET('/api/v1/templates/{name}/search', {
      params: { path: { name }, query: { q: query, maxResults } },
    })
    return (data ?? []) as TemplateSearchResult[]
  }

  function exportUrl(name: string): string {
    const token = getAuthToken()
    return `/api/v1/templates/${name}/export${token ? '?token=' + token : ''}`
  }

  async function importTemplate(formData: FormData): Promise<Template> {
    const config = useRuntimeConfig()
    const apiBase = config.public.apiBase as string
    const token = getAuthToken()
    const res = await fetch(`${apiBase}/api/v1/templates/import`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    })
    if (!res.ok) throw new Error("Import failed")
    const result = await res.json() as Template
    toast.success(t("store.templates.imported"), { description: result.name })
    await fetchTemplates()
    return result
  }

  // SSE via centralized event bus
  let sseConnected = false

  function handleTemplateEvent() {
    fetchTemplates()
  }

  function connectSse() {
    if (sseConnected) return
    sseConnected = true
    const bus = useSseEventBus()
    bus.on("TEMPLATE_UPDATED", handleTemplateEvent)
    bus.connect()
  }

  function disconnectSse() {
    if (!sseConnected) return
    sseConnected = false
    const bus = useSseEventBus()
    bus.off("TEMPLATE_UPDATED", handleTemplateEvent)
  }

  return {
    templates,
    loading,
    fetchTemplates,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    fetchVersions,
    fetchSnapshotFiles,
    fetchSnapshotFileContent,
    deleteVersion,
    rollbackToVersion,
    fetchFileContent,
    uploadFile,
    uploadFiles,
    extractZip,
    renameFile,
    rehash,
    downloadFileUrl,
    fetchVariables,
    saveVariables,
    scanVariables,
    fetchInheritance,
    searchFiles,
    exportUrl,
    importTemplate,
    connectSse,
    disconnectSse,
  }
})
