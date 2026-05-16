import { defineStore } from "pinia"
import type { CatalogEntry, ConfigFormat } from "~/types/api"
import { withLoading, withMutation } from "~/composables/useStoreCrud"
import { t } from "~/lib/translate"

interface RawCatalogEntry {
  platform: string
  category: string
  configFormat?: ConfigFormat | null
  version: string
  downloadUrl: string
  recommended: boolean
}

function groupByPlatform(raw: RawCatalogEntry[]): CatalogEntry[] {
  const map = new Map<string, CatalogEntry>()
  for (const r of raw) {
    let entry = map.get(r.platform)
    if (!entry) {
      entry = { platform: r.platform, category: r.category as CatalogEntry['category'], configFormat: r.configFormat, versions: [] }
      map.set(r.platform, entry)
    }
    entry.versions.push({ version: r.version, downloadUrl: r.downloadUrl, recommended: r.recommended })
  }
  return [...map.values()]
}

export const useCatalogStore = defineStore("catalog", () => {
  const entries = ref<CatalogEntry[]>([])
  const loading = ref(false)

  async function fetchCatalog() {
    await withLoading(loading, t("store.catalog.loadFailed"), async () => {
      const { data: res } = await useApiClient().GET('/api/v1/catalog')
      entries.value = groupByPlatform((res?.data ?? []) as unknown as RawCatalogEntry[])
    })
  }

  async function addVersion(platform: string, body: { version: string; downloadUrl: string; category?: string; configFormat?: string }) {
    await withMutation(
      () => useApiClient().POST('/api/v1/catalog/{platform}/versions', { params: { path: { platform } }, body }),
      {
        success: { message: t("store.catalog.versionAdded"), description: t("store.catalog.versionAddedDesc", { version: body.version, platform }) },
        errorMsg: t("store.catalog.addVersionFailed"),
        onSuccess: fetchCatalog,
      },
    )
  }

  async function updateVersion(platform: string, version: string, body: { version?: string; downloadUrl?: string }) {
    await withMutation(
      () => useApiClient().PATCH('/api/v1/catalog/{platform}/versions/{version}', {
        params: { path: { platform, version } },
        body,
      }),
      {
        success: { message: t("store.catalog.versionUpdated"), description: t("store.catalog.versionUpdatedDesc", { version, platform }) },
        errorMsg: t("store.catalog.updateVersionFailed"),
        onSuccess: fetchCatalog,
      },
    )
  }

  async function markRecommended(platform: string, version: string) {
    await withMutation(
      () => useApiClient().PUT('/api/v1/catalog/{platform}/versions/{version}/recommended', {
        params: { path: { platform, version } },
      }),
      {
        success: { message: t("store.catalog.recommendedSet"), description: t("store.catalog.recommendedSetDesc", { version, platform }) },
        errorMsg: t("store.catalog.setRecommendedFailed"),
        onSuccess: fetchCatalog,
      },
    )
  }

  async function deleteVersion(platform: string, version: string) {
    await withMutation(
      () => useApiClient().DELETE('/api/v1/catalog/{platform}/versions/{version}', {
        params: { path: { platform, version } },
      }),
      {
        success: { message: t("store.catalog.versionRemoved"), description: t("store.catalog.versionRemovedDesc", { version, platform }) },
        errorMsg: t("store.catalog.removeVersionFailed"),
        onSuccess: fetchCatalog,
      },
    )
  }

  return {
    entries,
    loading,
    fetchCatalog,
    addVersion,
    updateVersion,
    markRecommended,
    deleteVersion,
  }
})
