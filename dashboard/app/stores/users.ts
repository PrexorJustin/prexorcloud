import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface DashboardUser {
  username: string
  email?: string | null
  role: string
  permissions?: string[]
  avatarUrl?: string | null
  minecraftUuid?: string | null
  minecraftName?: string | null
  createdAt?: string
  lastLogin?: string | null
}

/**
 * Users store — list + CRUD against /api/v1/users. Avatars and preferences
 * are handled per-user via dedicated endpoints.
 */
export const useUsersStore = defineStore("users", () => {
  const users = ref<DashboardUser[]>([])
  const loading = ref(false)

  async function fetchUsers() {
    loading.value = true
    try {
      const { data } = await useApiClient().GET('/api/v1/users')
      users.value = ((data as { data?: DashboardUser[] })?.data ?? []) as DashboardUser[]
    } catch {
      toast.error(t("store.users.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function createUser(body: { username: string; password: string; role: string; email?: string }) {
    try {
      await useApiClient().POST('/api/v1/users', { body: body as never })
      toast.success(t("store.users.created", { username: body.username }))
      await fetchUsers()
    } catch {
      toast.error(t("store.users.createFailed"), { description: t("store.users.createFailedDesc") })
      throw new Error("create-user")
    }
  }

  async function updateUser(username: string, body: { role?: string; email?: string }) {
    try {
      await useApiClient().PATCH('/api/v1/users/{username}', { params: { path: { username } }, body: body as never })
      toast.success(t("store.users.updated"))
      await fetchUsers()
    } catch {
      toast.error(t("store.users.updateFailed"), { description: t("store.users.updateFailedDesc") })
      throw new Error("update-user")
    }
  }

  async function deleteUser(username: string) {
    try {
      await useApiClient().DELETE('/api/v1/users/{username}', { params: { path: { username } } })
      toast.success(t("store.users.deleted", { username }))
      users.value = users.value.filter(u => u.username !== username)
    } catch {
      toast.error(t("store.users.deleteFailed"), { description: t("store.users.deleteFailedDesc") })
      throw new Error("delete-user")
    }
  }

  return { users, loading, fetchUsers, createUser, updateUser, deleteUser }
})
