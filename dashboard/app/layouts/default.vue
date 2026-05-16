<script setup lang="ts">
import { onMounted, ref } from "vue"
import { LogOut, Settings, User } from "lucide-vue-next"
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "~/components/ui/sidebar"
import { Separator } from "~/components/ui/separator"
import { Avatar, AvatarFallback } from "~/components/ui/avatar"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu"

const auth = useAuthStore()
const notifications = useNotificationsStore()
const router = useRouter()

const shortcutsOpen = ref(false)
const paletteRef = ref<{ open: () => void } | null>(null)

useShortcuts({
  onShowOverlay: () => { shortcutsOpen.value = true },
  onShowPalette: () => paletteRef.value?.open(),
})

onMounted(() => {
  notifications.connectSse()
})

async function logout() {
  auth.logout()
  await router.push("/login")
}
</script>

<template>
  <a
    href="#main-content"
    class="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-foreground focus:shadow-lg"
  >
    Skip to content
  </a>

  <LayoutAmbientGlows />
  <SidebarProvider>
    <LayoutAppSidebar />

    <SidebarInset class="min-w-0">
      <header
        class="sticky top-0 z-30 flex h-14 shrink-0 items-center gap-3 border-b border-glass-border bg-sidebar/80 px-4 backdrop-blur-xl"
        role="banner"
      >
        <SidebarTrigger aria-label="Toggle sidebar" />
        <Separator orientation="vertical" class="h-4" />

        <LayoutCommandPalette ref="paletteRef" />

        <div class="ml-auto flex items-center gap-1">
          <LayoutNotificationsPanel />
          <LayoutThemeSwitcher />

          <DropdownMenu>
            <DropdownMenuTrigger as-child>
              <button
                type="button"
                aria-label="Account menu"
                class="ml-2 inline-flex size-8 items-center justify-center rounded-full transition-colors hover:bg-glass-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              >
                <Avatar class="size-8 bg-primary text-primary-foreground">
                  <AvatarFallback class="text-xs font-bold">
                    {{ (auth.user?.username ?? "U").charAt(0).toUpperCase() }}
                  </AvatarFallback>
                </Avatar>
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" class="w-56">
              <div class="px-2 py-1.5">
                <p class="text-sm font-medium leading-tight">{{ auth.user?.username ?? "User" }}</p>
                <p class="text-xs text-muted-foreground">{{ auth.user?.role ?? "" }}</p>
              </div>
              <DropdownMenuSeparator />
              <DropdownMenuItem as-child>
                <NuxtLink to="/profile" class="cursor-pointer">
                  <User class="mr-2 size-4" />
                  Profile
                </NuxtLink>
              </DropdownMenuItem>
              <DropdownMenuItem as-child>
                <NuxtLink to="/settings" class="cursor-pointer">
                  <Settings class="mr-2 size-4" />
                  Settings
                </NuxtLink>
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem class="text-destructive focus:text-destructive" @click="logout">
                <LogOut class="mr-2 size-4" />
                Log out
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>

      <main id="main-content" class="flex min-w-0 flex-1 flex-col p-6" role="main">
        <slot />
      </main>
    </SidebarInset>
  </SidebarProvider>

  <LayoutShortcutsOverlay v-model:open="shortcutsOpen" />
</template>
