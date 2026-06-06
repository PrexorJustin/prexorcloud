<script setup lang="ts">
import {ChevronRight, LogOut, Settings, User} from "lucide-vue-next"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarSeparator,
} from "~/components/ui/sidebar"
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "~/components/ui/collapsible"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu"
import {Avatar, AvatarFallback} from "~/components/ui/avatar"
import {navigation} from "~/lib/navigation"
import type {NavItem} from "~/types/navigation"
import {resolveIcon} from "~/lib/icons"

const route = useRoute()
const auth = useAuthStore()
const router = useRouter()
const moduleStore = useModuleStore()
const { t } = useI18n()

const DEFAULT_MODULE_SORT_ORDER = 80

/** Resolve an i18n key, falling back to the literal label (module-injected entries). */
function label(key: string | undefined, fallback: string): string {
  return key ? t(key) : fallback
}

const navGroups = computed(() => {
  // Clone static groups, filter by permission
  const groups = navigation.map(group => ({
    ...group,
    items: group.items.filter(item => !item.permission || auth.can(item.permission)),
  }))

  // Inject nav items for all runtime-loaded modules
  for (const mod of moduleStore.modulesWithFrontend) {
    for (const modRoute of mod.frontend.routes.filter(r => r.nav)) {
      const requiredPerm = modRoute.permission
      if (requiredPerm && !auth.can(requiredPerm)) continue
      if (!requiredPerm && modRoute.adminOnly && auth.user?.role !== 'ADMIN') continue
      addNavItem(groups, {
        title: modRoute.title,
        url: `/modules/${mod.name}${modRoute.path === '/' ? '' : modRoute.path}`,
        icon: resolveIcon(modRoute.icon) ?? resolveIcon(mod.frontend.icon),
      }, modRoute.navGroup, modRoute.navGroupOrder)
    }
  }

  return groups
    .filter(group => group.items.length > 0)
    .sort((a, b) => a.sortOrder - b.sortOrder)
})

function addNavItem(
  groups: { id: number; label: string; labelKey?: string; sortOrder: number; items: NavItem[] }[],
  item: NavItem,
  navGroup?: string | null,
  navGroupOrder?: number | null,
) {
  if (navGroup) {
    let target = groups.find(g => g.label === navGroup)
    if (!target) {
      target = { id: 200 + groups.length, label: navGroup, sortOrder: navGroupOrder ?? DEFAULT_MODULE_SORT_ORDER, items: [] }
      groups.push(target)
    }
    target.items.push(item)
  } else {
    let modGroup = groups.find(g => g.label === "Modules")
    if (!modGroup) {
      modGroup = { id: 100, label: "Modules", labelKey: "nav.groups.modules", sortOrder: DEFAULT_MODULE_SORT_ORDER, items: [] }
      groups.push(modGroup)
    }
    modGroup.items.push(item)
  }
}

function isActive(url: string): boolean {
  if (url === "/") return route.path === "/"
  // Exact match for /modules (admin page) to avoid conflicting with /modules/{moduleName}
  if (url === "/modules") return route.path === "/modules" || route.path === "/modules/"
  // For module routes: check that the match is exact or followed by a '/' to prevent
  // /modules/player-management from matching /modules/player-management/bans
  if (route.path === url) return true
  if (route.path.startsWith(url + '/')) {
    // Only match if no sibling nav item has a more specific match
    const siblings = navGroups.value.flatMap(g => g.items)
    const hasBetterMatch = siblings.some(s => s.url !== url && s.url.length > url.length && route.path.startsWith(s.url))
    return !hasBetterMatch
  }
  return false
}

async function handleLogout() {
  auth.logout()
  await router.push("/login")
}
</script>

<template>
  <Sidebar collapsible="icon">
    <SidebarHeader class="h-14 items-center justify-center">
      <SidebarMenu>
        <SidebarMenuItem>
          <SidebarMenuButton size="lg" as-child>
            <NuxtLink to="/">
              <img
                src="/logomark.svg"
                alt=""
                aria-hidden="true"
                width="32"
                height="32"
                class="size-8 shrink-0"
              >
              <div class="flex flex-col gap-0.5 leading-none">
                <span class="font-display font-semibold tracking-tight text-base">PrexorCloud</span>
              </div>
            </NuxtLink>
          </SidebarMenuButton>
        </SidebarMenuItem>
      </SidebarMenu>
    </SidebarHeader>

    <SidebarSeparator class="mx-0 -mt-px group-data-[collapsible=icon]:-mt-px"/>

    <SidebarContent>
      <Collapsible
        v-for="group in navGroups"
        :key="group.id"
        v-slot="{ open }"
        :default-open="true"
        class="group/collapsible"
      >
        <SidebarGroup>
          <SidebarGroupLabel as-child>
            <CollapsibleTrigger class="group/trigger flex w-full items-center rounded-md px-2 py-1 hover:bg-sidebar-accent/50 transition-colors duration-150">
              <ChevronRight
                class="size-3 mr-1.5 text-sidebar-foreground/40 transition-transform duration-200 group-data-[collapsible=icon]:hidden"
                :class="open ? 'rotate-90' : ''"
              />
              <span class="flex-1 text-left uppercase text-[10.5px] font-semibold tracking-[0.16em] text-sidebar-foreground group-hover/trigger:text-foreground transition-colors duration-150">{{ label(group.labelKey, group.label) }}</span>
            </CollapsibleTrigger>
          </SidebarGroupLabel>
          <CollapsibleContent>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem v-for="item in group.items" :key="item.title">
                  <SidebarMenuButton as-child :tooltip="label(item.titleKey, item.title)" :is-active="isActive(item.url)">
                    <NuxtLink :to="item.url">
                      <component :is="item.icon"/>
                      <span>{{ label(item.titleKey, item.title) }}</span>
                    </NuxtLink>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroupContent>
          </CollapsibleContent>
        </SidebarGroup>
      </Collapsible>
    </SidebarContent>

    <SidebarFooter>
      <SidebarMenu>
        <SidebarMenuItem>
          <DropdownMenu>
            <DropdownMenuTrigger as-child>
              <SidebarMenuButton size="default" class="justify-center cursor-pointer">
                <Avatar class="size-8 rounded-full bg-sidebar-primary text-sidebar-primary-foreground">
                  <AvatarFallback class="rounded-full text-sidebar-primary-foreground text-base font-bold">
                    {{ (auth.user?.username ?? "U").charAt(0).toUpperCase() }}
                  </AvatarFallback>
                </Avatar>
                <div class="grid text-left leading-tight group-data-[collapsible=icon]:hidden">
                  <span class="truncate text-base font-semibold">{{ auth.user?.username ?? t('sidebar.user') }}</span>
                  <span class="truncate text-sm">{{ auth.user?.role ?? "" }}</span>
                </div>
              </SidebarMenuButton>
            </DropdownMenuTrigger>
            <DropdownMenuContent side="top" class="w-(--reka-popper-anchor-width) min-w-56">
              <DropdownMenuItem as-child>
                <NuxtLink to="/profile" class="cursor-pointer">
                  <User class="size-4"/>
                  {{ t('sidebar.profile') }}
                </NuxtLink>
              </DropdownMenuItem>
              <DropdownMenuItem as-child>
                <NuxtLink to="/settings" class="cursor-pointer">
                  <Settings class="size-4"/>
                  {{ t('sidebar.settings') }}
                </NuxtLink>
              </DropdownMenuItem>
              <DropdownMenuSeparator/>
              <DropdownMenuItem class="cursor-pointer text-destructive focus:text-destructive" @click="handleLogout">
                <LogOut class="size-4"/>
                {{ t('sidebar.logout') }}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </SidebarMenuItem>
      </SidebarMenu>
    </SidebarFooter>
  </Sidebar>
</template>
