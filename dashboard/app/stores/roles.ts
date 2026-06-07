import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface RoleDefinition {
  name: string
  permissions: string[]
  builtIn: boolean
  userCount?: number
}

/** All known permission keys, grouped for the role-edit UI. */
export const PERMISSION_GROUPS: { label: string; permissions: string[] }[] = [
  { label: "Instances",  permissions: ["instances.view", "instances.start", "instances.stop", "instances.delete"] },
  { label: "Groups",     permissions: ["groups.view", "groups.edit", "groups.delete"] },
  { label: "Nodes",      permissions: ["nodes.view", "nodes.edit", "nodes.delete"] },
  { label: "Networks",   permissions: ["networks.view", "networks.create", "networks.edit", "networks.delete"] },
  { label: "Templates",  permissions: ["templates.view", "templates.edit"] },
  { label: "Modules",    permissions: ["modules.view", "modules.install", "modules.uninstall"] },
  { label: "Crashes",    permissions: ["crashes.view"] },
  { label: "Audit",      permissions: ["audit.view"] },
  { label: "Backups",    permissions: ["backups.view", "backups.create", "backups.delete", "backups.restore"] },
  { label: "Maintenance",permissions: ["maintenance.view", "maintenance.edit"] },
  { label: "Tokens",     permissions: ["tokens.view", "tokens.create", "tokens.delete"] },
  { label: "Credentials",permissions: ["credentials.view", "credentials.delete"] },
  { label: "Users",      permissions: ["users.view", "users.create", "users.edit", "users.delete"] },
  { label: "Roles",      permissions: ["roles.view", "roles.create", "roles.edit", "roles.delete"] },
]

export const useRolesStore = defineStore("roles", () => {
  const roles = ref<RoleDefinition[]>([])
  const loading = ref(false)

  async function fetchRoles() {
    loading.value = true
    try {
      const { data } = await useApiClient().GET('/api/v1/roles')
      roles.value = ((data as { data?: RoleDefinition[] })?.data ?? []) as RoleDefinition[]
    } catch {
      toast.error(t("store.roles.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function createRole(body: { name: string; permissions: string[] }) {
    try {
      await useApiClient().POST('/api/v1/roles', { body: body as never })
      toast.success(t("store.roles.created", { name: body.name }))
      await fetchRoles()
    } catch {
      toast.error(t("store.roles.createFailed"), { description: t("store.roles.createFailedDesc") })
      throw new Error("create-role")
    }
  }

  async function updateRole(name: string, body: { permissions: string[] }) {
    try {
      await useApiClient().PATCH('/api/v1/roles/{name}', { params: { path: { name } }, body: body as never })
      toast.success(t("store.roles.updated"))
      await fetchRoles()
    } catch {
      toast.error(t("store.roles.updateFailed"), { description: t("store.roles.updateFailedDesc") })
      throw new Error("update-role")
    }
  }

  async function deleteRole(name: string) {
    try {
      await useApiClient().DELETE('/api/v1/roles/{name}', { params: { path: { name } } })
      toast.success(t("store.roles.deleted", { name }))
      roles.value = roles.value.filter(r => r.name !== name)
    } catch {
      toast.error(t("store.roles.deleteFailed"), { description: t("store.roles.deleteFailedDesc") })
      throw new Error("delete-role")
    }
  }

  return { roles, loading, fetchRoles, createRole, updateRole, deleteRole }
})
