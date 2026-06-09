import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface BackupRecord {
  id: string
  createdAt: string
  sizeBytes: number
  instanceCount?: number
  templateCount?: number
  verifiedAt?: string | null
  verifyStatus?: "OK" | "FAILED" | "PENDING"
  notes?: string
}

/**
 * Backups store. Wraps the controller's /backups CRUD + verify/prune/restore
 * actions. Mirrors the existing store pattern; toasts every mutation so
 * operators see immediate feedback in this DR-critical surface.
 *
 * The /backups paths aren't fully covered by the openapi-fetch typed bundle
 * yet (controller-side feature flag); go through a loose-typed client cast
 * so this compiles regardless of SDK regen lag. Once `pnpm sdk:generate` has
 * been run against a controller that ships these endpoints, drop the cast.
 */
type LooseClient = {
  GET:    (path: string, init?: unknown) => Promise<{ data: unknown }>
  POST:   (path: string, init?: unknown) => Promise<{ data: unknown }>
  DELETE: (path: string, init?: unknown) => Promise<unknown>
}

export const useBackupsStore = defineStore("backups", () => {
  const backups = ref<BackupRecord[]>([])
  const loading = ref(false)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchBackups() {
    loading.value = true
    try {
      const { data } = await loose().GET('/api/v1/backups')
      backups.value = ((data as { data?: BackupRecord[] })?.data ?? []) as BackupRecord[]
    } catch {
      toast.error(t("store.backups.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function createBackup(notes?: string): Promise<BackupRecord | null> {
    try {
      const { data } = await loose().POST('/api/v1/backups', { body: { notes } })
      toast.success(t("store.backups.created"))
      await fetchBackups()
      return data as BackupRecord
    } catch {
      toast.error(t("store.backups.createFailed"), { description: t("store.backups.createFailedDesc") })
      return null
    }
  }

  async function verifyBackup(id: string) {
    try {
      await loose().POST(`/api/v1/backups/${encodeURIComponent(id)}/verify`)
      toast.success(t("store.backups.verificationStarted"))
      await fetchBackups()
    } catch {
      toast.error(t("store.backups.verifyFailed"), { description: t("store.backups.verifyFailedDesc") })
    }
  }

  async function deleteBackup(id: string) {
    try {
      await loose().DELETE(`/api/v1/backups/${encodeURIComponent(id)}`)
      backups.value = backups.value.filter(b => b.id !== id)
      toast.success(t("store.backups.deleted"))
    } catch {
      toast.error(t("store.backups.deleteFailed"), { description: t("store.backups.deleteFailedDesc") })
      throw new Error("delete-backup")
    }
  }

  async function pruneBackups(keep: number) {
    try {
      await loose().POST('/api/v1/backups/prune', { body: { keep } })
      toast.success(t("store.backups.pruned", { keep }))
      await fetchBackups()
    } catch {
      toast.error(t("store.backups.pruneFailed"), { description: t("store.backups.pruneFailedDesc") })
    }
  }

  async function restoreBackup(id: string) {
    try {
      await loose().POST('/api/v1/restore', { body: { backupId: id } })
      toast.success(t("store.backups.restoreStarted"), { description: t("store.backups.restoreStartedDesc") })
    } catch {
      toast.error(t("store.backups.restoreFailed"), { description: t("store.backups.restoreFailedDesc") })
      throw new Error("restore-backup")
    }
  }

  return { backups, loading, fetchBackups, createBackup, verifyBackup, deleteBackup, pruneBackups, restoreBackup }
})
