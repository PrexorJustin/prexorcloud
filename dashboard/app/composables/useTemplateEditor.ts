import { toast } from "vue-sonner"
import type { TemplateFile } from "~/types/api"
import { t } from "~/lib/translate"
import type { TreeNode } from "~/components/templates/FileTreeNode.vue"

export type ChangeType = "modified" | "created" | "deleted" | "renamed"

export interface StagedChange {
  type: ChangeType
  path: string
  content?: string
  newPath?: string
  isDir?: boolean
}

export interface OpenFile {
  path: string
  name: string
  originalContent: string
  content: string
  loading: boolean
}

const TEXT_EXTENSIONS = new Set([
  "yml", "yaml", "json", "toml", "properties", "conf", "cfg", "ini",
  "txt", "md", "log", "xml", "html", "css", "js", "ts", "sh", "bat",
  "sk", "java", "kt", "gradle", "groovy", "secret", "env", "key", "pem",
])

const EXT_TO_LANG: Record<string, string> = {
  yml: "yaml", yaml: "yaml", json: "json", toml: "toml",
  properties: "ini", conf: "ini", cfg: "ini", ini: "ini",
  xml: "xml", html: "html", css: "css", js: "javascript", ts: "typescript",
  sh: "shell", bat: "bat", java: "java", kt: "kotlin",
  gradle: "groovy", groovy: "groovy", md: "markdown",
  txt: "plaintext", log: "plaintext", sk: "plaintext",
}

/**
 * Owns the file-tree + open-file + staged-changes state for one template,
 * and exposes the operations the page binds to.
 */
export function useTemplateEditor(templateName: string) {
  const store = useTemplatesStore()

  // ── File tree ──
  const tree = ref<TreeNode[]>([])
  const treeLoading = ref(false)

  // ── Staged changes ──
  const stagedChanges = reactive<Map<string, StagedChange>>(new Map())
  const hasChanges = computed(() => stagedChanges.size > 0)
  const stagedChangesList = computed(() => [...stagedChanges.values()])

  // ── Open file ──
  const openFile = ref<OpenFile | null>(null)
  const binaryFile = ref<{ path: string; name: string; size: number } | null>(null)

  const editorLanguage = computed(() => {
    if (!openFile.value) return "plaintext"
    const ext = openFile.value.name.split(".").pop()?.toLowerCase() ?? ""
    return EXT_TO_LANG[ext] ?? "plaintext"
  })

  const fileIsModified = computed(() => {
    if (!openFile.value) return false
    return openFile.value.content !== openFile.value.originalContent
  })

  // ── New item ──
  const showNewInput = ref<"file" | "dir" | null>(null)
  const newItemName = ref("")
  const newFileTargetPath = ref("")

  // ── Drag/drop ──
  const draggedNode = ref<TreeNode | null>(null)
  const dropTargetPath = ref<string | null>(null)

  // ── Rename ──
  const renamingPath = ref<string | null>(null)
  const renameValue = ref("")

  // ── Delete ──
  const confirmDeletePath = ref<string | null>(null)

  // ── Saving ──
  const saving = ref(false)
  const extracting = ref(false)

  // Auto-stage debounce
  let stageTimer: ReturnType<typeof setTimeout> | null = null

  function isTextFile(name: string): boolean {
    const ext = name.split(".").pop()?.toLowerCase() ?? ""
    return TEXT_EXTENSIONS.has(ext)
  }

  function getFileIcon(name: string) {
    const ext = name.split(".").pop()?.toLowerCase()
    if (["yml", "yaml"].includes(ext ?? "")) return "text-yellow-500"
    if (["json"].includes(ext ?? "")) return "text-green-500"
    if (["properties", "conf", "cfg", "ini", "toml"].includes(ext ?? "")) return "text-blue-400"
    if (["jar"].includes(ext ?? "")) return "text-orange-500"
    if (["sh", "bat"].includes(ext ?? "")) return "text-emerald-400"
    return "text-muted-foreground"
  }

  function findNode(path: string, nodes: TreeNode[] = tree.value): TreeNode | null {
    for (const node of nodes) {
      if (node.path === path) return node
      if (node.children) {
        const found = findNode(path, node.children)
        if (found) return found
      }
    }
    return null
  }

  function removeFromTree(path: string, nodes: TreeNode[] = tree.value): boolean {
    const idx = nodes.findIndex(n => n.path === path)
    if (idx !== -1) { nodes.splice(idx, 1); return true }
    for (const node of nodes) {
      if (node.children && removeFromTree(path, node.children)) return true
    }
    return false
  }

  function addToTree(path: string, isDir: boolean) {
    const parts = path.split("/")
    const name = parts.pop()!
    let nodes = tree.value

    for (const part of parts) {
      const parent = nodes.find(n => n.name === part && n.isDirectory)
      if (parent) {
        if (!parent.children) parent.children = []
        parent.expanded = true
        nodes = parent.children
      } else {
        return
      }
    }

    if (nodes.find(n => n.name === name && n.isDirectory === isDir)) return

    nodes.push({
      name,
      path,
      isDirectory: isDir,
      size: 0,
      children: isDir ? [] : undefined,
      expanded: false,
      loaded: isDir,
    })

    nodes.sort((a, b) => {
      if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1
      return a.name.localeCompare(b.name)
    })
  }

  async function loadDirectory(path: string): Promise<TreeNode[]> {
    const { data: filesData } = await useApiClient().GET('/api/v1/templates/{name}/files', {
      params: { path: { name: templateName }, query: path ? { path } : undefined },
    })
    const files = (filesData ?? []) as TemplateFile[]
    return files
      .sort((a, b) => {
        if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1
        return a.name.localeCompare(b.name)
      })
      .map(f => ({
        name: f.name,
        path: path ? `${path}/${f.name}` : f.name,
        isDirectory: f.isDirectory,
        size: f.size,
        children: f.isDirectory ? [] : undefined,
        expanded: false,
        loaded: false,
      }))
  }

  async function loadRoot() {
    treeLoading.value = true
    try {
      tree.value = await loadDirectory("")
      for (const change of stagedChanges.values()) {
        if (change.type === "created") addToTree(change.path, !!change.isDir)
      }
    } finally {
      treeLoading.value = false
    }
  }

  async function toggleExpand(node: TreeNode) {
    if (!node.isDirectory) return
    if (!node.loaded) {
      node.children = await loadDirectory(node.path)
      node.loaded = true
      for (const change of stagedChanges.values()) {
        if (change.type === "created" && change.path.startsWith(node.path + "/")) {
          const relative = change.path.slice(node.path.length + 1)
          if (!relative.includes("/")) addToTree(change.path, !!change.isDir)
        }
      }
    }
    node.expanded = !node.expanded
  }

  // ── Staging ops ──
  function stageModification(path: string, content: string) {
    stagedChanges.set(path, { type: "modified", path, content })
  }

  function stageCreation(path: string, content: string, isDir = false) {
    if (isDir) {
      stagedChanges.set(path, { type: "created", path, content: "", isDir: true })
    } else {
      stagedChanges.set(path, { type: "created", path, content, isDir: false })
    }
  }

  function stageDeletion(path: string) {
    const existing = stagedChanges.get(path)
    if (existing?.type === "created") {
      stagedChanges.delete(path)
      return
    }
    stagedChanges.set(path, { type: "deleted", path })
  }

  function unstageChange(path: string) {
    stagedChanges.delete(path)
    if (openFile.value?.path === path) loadFileContent(path)
  }

  function getChangeType(path: string): ChangeType | null {
    return stagedChanges.get(path)?.type ?? null
  }

  // ── File loading ──
  async function loadFileContent(path: string) {
    const name = path.split("/").pop() ?? path
    if (!isTextFile(name)) {
      openFile.value = null
      const node = findNode(path)
      binaryFile.value = { path, name, size: node?.size ?? 0 }
      return
    }
    binaryFile.value = null

    openFile.value = { path, name, originalContent: "", content: "", loading: true }

    const staged = stagedChanges.get(path)
    if (staged?.content !== undefined) {
      openFile.value.loading = false
      openFile.value.originalContent = staged.content
      openFile.value.content = staged.content
      try {
        openFile.value.originalContent = await store.fetchFileContent(templateName, path)
      } catch { /* new file, no original */ }
      return
    }

    try {
      const content = await store.fetchFileContent(templateName, path)
      if (openFile.value?.path === path) {
        openFile.value.originalContent = content
        openFile.value.content = content
        openFile.value.loading = false
      }
    } catch {
      if (openFile.value?.path === path) {
        openFile.value.content = ""
        openFile.value.originalContent = ""
        openFile.value.loading = false
      }
    }
  }

  function onFileClick(node: TreeNode) {
    if (node.isDirectory) {
      toggleExpand(node)
    } else {
      loadFileContent(node.path)
    }
  }

  function flushStage() {
    if (stageTimer) { clearTimeout(stageTimer); stageTimer = null }
    if (!openFile.value) return
    if (openFile.value.content !== openFile.value.originalContent) {
      stageModification(openFile.value.path, openFile.value.content)
    } else {
      stagedChanges.delete(openFile.value.path)
    }
  }

  function cancelStageTimer() {
    if (stageTimer) { clearTimeout(stageTimer); stageTimer = null }
  }

  function scheduleAutoStage() {
    if (stageTimer) clearTimeout(stageTimer)
    stageTimer = setTimeout(flushStage, 3000)
  }

  function revertCurrentFile() {
    cancelStageTimer()
    if (!openFile.value) return
    openFile.value.content = openFile.value.originalContent
    stagedChanges.delete(openFile.value.path)
  }

  // ── New item ──
  async function expandParent(parentPath: string) {
    if (!parentPath) return
    const node = findNode(parentPath)
    if (node?.isDirectory) {
      if (!node.loaded) {
        node.children = await loadDirectory(node.path)
        node.loaded = true
      }
      node.expanded = true
    }
  }

  async function startNewFile(parentPath: string) {
    await expandParent(parentPath)
    newFileTargetPath.value = parentPath
    newItemName.value = ""
    showNewInput.value = "file"
  }

  async function startNewDir(parentPath: string) {
    await expandParent(parentPath)
    newFileTargetPath.value = parentPath
    newItemName.value = ""
    showNewInput.value = "dir"
  }

  function confirmNewItem() {
    if (!newItemName.value.trim()) return
    const path = newFileTargetPath.value
      ? `${newFileTargetPath.value}/${newItemName.value.trim()}`
      : newItemName.value.trim()

    if (findNode(path)) {
      toast.error(t("store.templateEditor.alreadyExists"), { description: newItemName.value.trim() })
      return
    }

    if (showNewInput.value === "dir") {
      stageCreation(path, "", true)
      addToTree(path, true)
    } else {
      stageCreation(path, "")
      addToTree(path, false)
      openFile.value = { path, name: newItemName.value.trim(), originalContent: "", content: "", loading: false }
    }
    showNewInput.value = null
    newItemName.value = ""
  }

  function cancelNewItem() {
    showNewInput.value = null
  }

  // ── Drag/drop ──
  function handleDragAction(payload: { action: string; e?: DragEvent; node?: TreeNode; path?: string }) {
    switch (payload.action) {
      case "start":
        draggedNode.value = payload.node!
        payload.e!.dataTransfer!.effectAllowed = "move"
        payload.e!.dataTransfer!.setData("text/plain", payload.node!.path)
        break
      case "end":
        draggedNode.value = null
        dropTargetPath.value = null
        break
      case "over":
        if (!draggedNode.value) return
        if (draggedNode.value.path === payload.path) return
        if (payload.path!.startsWith(draggedNode.value.path + "/")) return
        payload.e!.preventDefault()
        payload.e!.dataTransfer!.dropEffect = "move"
        dropTargetPath.value = payload.path!
        break
      case "leave":
        if (dropTargetPath.value === payload.path) dropTargetPath.value = null
        break
      case "drop":
        payload.e!.preventDefault()
        if (!draggedNode.value) return
        onDropNode(payload.path!)
        break
    }
  }

  function onDropNode(targetPath: string) {
    if (!draggedNode.value) return
    const source = draggedNode.value
    if (source.path === targetPath) return
    if (targetPath.startsWith(source.path + "/")) return

    const newPath = targetPath ? `${targetPath}/${source.name}` : source.name
    if (newPath === source.path) return

    stagedChanges.set(source.path, { type: "renamed", path: source.path, newPath })
    removeFromTree(source.path)
    addToTree(newPath, source.isDirectory)

    if (openFile.value?.path === source.path) openFile.value.path = newPath

    toast.success(t("store.templateEditor.moveStaged"), { description: t("store.templateEditor.moveStagedDesc", { source: source.name, target: targetPath || "root" }) })
    draggedNode.value = null
    dropTargetPath.value = null
  }

  function onRootDragOver(e: DragEvent) {
    if (!draggedNode.value) return
    if (!draggedNode.value.path.includes("/")) return
    e.preventDefault()
    e.dataTransfer!.dropEffect = "move"
    dropTargetPath.value = "__root__"
  }

  function onRootDrop(e: DragEvent) {
    e.preventDefault()
    if (!draggedNode.value) return
    onDropNode("")
  }

  function onRootDragLeave() {
    if (dropTargetPath.value === "__root__") dropTargetPath.value = null
  }

  // ── Rename ──
  function startRename(node: TreeNode) {
    renamingPath.value = node.path
    renameValue.value = node.name
  }

  function confirmRename(node: TreeNode) {
    if (!renameValue.value.trim() || renameValue.value === node.name) {
      renamingPath.value = null
      return
    }
    const parts = node.path.split("/")
    parts.pop()
    const newPath = [...parts, renameValue.value.trim()].join("/")
    stagedChanges.set(node.path, { type: "renamed", path: node.path, newPath })
    node.name = renameValue.value.trim()
    node.path = newPath
    renamingPath.value = null
  }

  function cancelRename() {
    renamingPath.value = null
  }

  // ── Delete ──
  function requestDelete(path: string) {
    confirmDeletePath.value = path
  }

  function confirmDelete() {
    if (!confirmDeletePath.value) return
    stageDeletion(confirmDeletePath.value)
    if (openFile.value?.path === confirmDeletePath.value) openFile.value = null
    confirmDeletePath.value = null
    toast.success(t("store.templateEditor.deletionStaged"))
  }

  function cancelDelete() {
    confirmDeletePath.value = null
  }

  // ── Download ──
  function downloadFile(path: string) {
    const url = store.downloadFileUrl(templateName, path)
    window.open(url, "_blank")
  }

  async function extractZipFile(path: string) {
    extracting.value = true
    try {
      await store.extractZip(templateName, path)
      binaryFile.value = null
      openFile.value = null
      await loadRoot()
    } catch {
      toast.error(t("store.templateEditor.extractZipFailed"))
    } finally {
      extracting.value = false
    }
  }

  // ── Save all staged changes ──
  async function saveAllChanges(extraValidationCount: () => number): Promise<boolean> {
    if (!hasChanges.value) return false
    saving.value = true

    try {
      const changes = [...stagedChanges.values()]

      for (const change of changes.filter(c => c.type === "deleted")) {
        await useApiClient().DELETE('/api/v1/templates/{name}/files', { params: { path: { name: templateName }, query: { path: change.path } } })
      }

      for (const change of changes.filter(c => c.type === "renamed")) {
        await store.renameFile(templateName, change.path, change.newPath!)
      }

      for (const change of changes.filter(c => c.type === "created" && c.isDir)) {
        await useApiClient().POST('/api/v1/templates/{name}/files/mkdir', { params: { path: { name: templateName }, query: { path: change.path } } })
      }

      for (const change of changes.filter(c =>
        (c.type === "created" && !c.isDir) || c.type === "modified",
      )) {
        await store.uploadFile(templateName, change.path, change.content ?? "")
      }

      await useApiClient().POST('/api/v1/templates/{name}/rehash', { params: { path: { name: templateName } } })
      stagedChanges.clear()
      await loadRoot()
      if (openFile.value) await loadFileContent(openFile.value.path)

      toast.success(t("store.templateEditor.changesSaved"), {
        description: t("store.templateEditor.changesSavedDesc", { count: changes.length }, changes.length),
      })

      const extra = extraValidationCount()
      if (extra > 0 && openFile.value) {
        toast.warning(t("store.templateEditor.validationIssues"), { description: t("store.templateEditor.validationIssuesDesc", { count: extra }, extra) })
      }
      return true
    } catch (err) {
      toast.error(t("store.templateEditor.saveChangesFailed"), {
        description: err instanceof Error ? err.message : "Unknown error",
      })
      return false
    } finally {
      saving.value = false
    }
  }

  function discardAllChanges() {
    stagedChanges.clear()
    if (openFile.value) openFile.value.content = openFile.value.originalContent
    loadRoot()
    toast.info(t("store.templateEditor.changesDiscarded"))
  }

  return {
    // tree
    tree, treeLoading, loadRoot, loadDirectory, toggleExpand, findNode, addToTree,
    // staged changes
    stagedChanges, hasChanges, stagedChangesList,
    stageModification, stageCreation, stageDeletion, unstageChange, getChangeType,
    // open file
    openFile, binaryFile, editorLanguage, fileIsModified,
    loadFileContent, onFileClick, revertCurrentFile, isTextFile, getFileIcon,
    flushStage, cancelStageTimer, scheduleAutoStage,
    // new item
    showNewInput, newItemName, newFileTargetPath,
    startNewFile, startNewDir, confirmNewItem, cancelNewItem,
    // drag/drop
    draggedNode, dropTargetPath,
    handleDragAction, onRootDragOver, onRootDrop, onRootDragLeave,
    // rename
    renamingPath, renameValue,
    startRename, confirmRename, cancelRename,
    // delete
    confirmDeletePath,
    requestDelete, confirmDelete, cancelDelete,
    // misc
    saving, extracting,
    downloadFile, extractZipFile, saveAllChanges, discardAllChanges,
  }
}
