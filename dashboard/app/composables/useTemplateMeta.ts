import { toast } from "vue-sonner"
import type { Template, TemplateSearchResult, TemplateVariable } from "~/types/api"
import { t } from "~/lib/translate"

/**
 * Loads inheritance chain, variables, and provides debounced file search
 * for a single template. Reactively re-runs when `template` changes.
 */
export function useTemplateMeta(templateName: string, template: Ref<Template | null>) {
  const store = useTemplatesStore()

  // ── Inheritance chain ──
  const inheritanceChain = ref<{ name: string; active: boolean; link: string }[]>([])

  // ── Variables ──
  const templateVariables = ref<TemplateVariable[]>([])
  const variablesSaving = ref(false)
  const scanningVariables = ref(false)

  watch(template, async (t) => {
    if (!t) return
    try {
      const chain = await store.fetchInheritance(templateName)
      inheritanceChain.value = chain.map(n => ({
        name: n.name,
        active: n.name === templateName,
        link: `/templates/${n.name}`,
      }))
    } catch { /* ignore */ }
    try {
      templateVariables.value = await store.fetchVariables(templateName)
    } catch { /* ignore */ }
  }, { immediate: true })

  async function saveTemplateVariables() {
    variablesSaving.value = true
    try {
      await store.saveVariables(templateName, templateVariables.value)
    } catch { toast.error(t("store.templateMeta.saveVariablesFailed")) }
    finally { variablesSaving.value = false }
  }

  async function scanForVariables() {
    scanningVariables.value = true
    try {
      const discovered = await store.scanVariables(templateName)
      const existing = new Set(templateVariables.value.map(v => v.key))
      const newVars = discovered.filter(k => !existing.has(k)).map(k => ({ key: k, value: '', description: '' }))
      if (newVars.length > 0) {
        templateVariables.value = [...templateVariables.value, ...newVars]
        toast.success(t("store.templateMeta.newVariablesFound", { count: newVars.length }, newVars.length))
      } else {
        toast.info(t("store.templateMeta.noNewVariables"))
      }
    } catch { toast.error(t("store.templateMeta.scanFilesFailed")) }
    finally { scanningVariables.value = false }
  }

  // ── File search ──
  const showSearch = ref(false)
  const searchQuery = ref('')
  const searchResults = ref<TemplateSearchResult[]>([])
  const searchLoading = ref(false)
  let searchDebounce: ReturnType<typeof setTimeout> | null = null

  async function onSearch(query: string) {
    searchQuery.value = query
    if (searchDebounce) clearTimeout(searchDebounce)
    if (!query.trim()) { searchResults.value = []; return }
    searchDebounce = setTimeout(async () => {
      searchLoading.value = true
      try {
        searchResults.value = await store.searchFiles(templateName, query)
      } catch { searchResults.value = [] }
      finally { searchLoading.value = false }
    }, 300)
  }

  return {
    inheritanceChain,
    templateVariables, variablesSaving, scanningVariables,
    saveTemplateVariables, scanForVariables,
    showSearch, searchQuery, searchResults, searchLoading,
    onSearch,
  }
}
