<script setup lang="ts">
import { ref } from "vue"
import { Bell, Check, Trash2, AlertTriangle, AlertOctagon, CheckCircle2, Info } from "lucide-vue-next"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "~/components/ui/popover"
import { Eyebrow } from "~/components/ui/eyebrow"
import type { NotificationTone } from "~/stores/notifications"
import { timeAgo } from "~/lib/utils"

const { t } = useI18n()
const store = useNotificationsStore()
const open = ref(false)

const ICON: Record<NotificationTone, typeof Bell> = {
  destructive: AlertOctagon,
  warning:     AlertTriangle,
  success:     CheckCircle2,
  primary:     Info,
  muted:       Info,
}

const ICON_CLASS: Record<NotificationTone, string> = {
  destructive: "text-destructive",
  warning:     "text-warning",
  success:     "text-success",
  primary:     "text-primary",
  muted:       "text-muted-foreground",
}

function onItemClick(id: string, route?: string) {
  store.markRead(id)
  if (route) {
    open.value = false
    navigateTo(route)
  }
}
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <button
        type="button"
        :aria-label="t('components.notifications.label')"
        class="relative inline-flex size-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-glass-hover hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
      >
        <Bell class="size-4" />
        <span
          v-if="store.unreadCount > 0"
          class="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-none text-destructive-foreground tabular"
          :aria-label="t('components.notifications.unreadCount')"
        >
          {{ store.unreadCount > 99 ? '99+' : store.unreadCount }}
        </span>
      </button>
    </PopoverTrigger>

    <PopoverContent align="end" class="w-96 p-0">
      <header class="flex items-center justify-between border-b border-glass-border px-4 py-3">
        <Eyebrow>{{ t('components.notifications.label') }}</Eyebrow>
        <div class="flex items-center gap-1">
          <button
            v-if="store.unreadCount > 0"
            type="button"
            class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-glass-hover hover:text-foreground"
            @click="store.markAllRead()"
          >
            <Check class="size-3" /> {{ t('components.notifications.markAllRead') }}
          </button>
          <button
            v-if="store.items.length > 0"
            type="button"
            :aria-label="t('components.notifications.clearAll')"
            class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-glass-hover hover:text-destructive"
            @click="store.clear()"
          >
            <Trash2 class="size-3" /> {{ t('components.notifications.clear') }}
          </button>
        </div>
      </header>

      <div class="max-h-96 overflow-y-auto styled-scrollbar">
        <div v-if="store.items.length === 0" class="px-4 py-12 text-center">
          <Bell class="mx-auto mb-2 size-8 text-muted-foreground/30" />
          <p class="text-sm text-muted-foreground">{{ t('components.notifications.empty') }}</p>
          <p class="mt-1 text-xs text-muted-foreground/60">{{ t('components.notifications.emptyHint') }}</p>
        </div>
        <button
          v-for="n in store.items"
          :key="n.id"
          type="button"
          class="flex w-full items-start gap-3 border-b border-glass-border/50 px-4 py-3 text-left transition-colors last:border-0 hover:bg-glass-hover"
          :class="!n.read ? 'bg-glass/40' : ''"
          @click="onItemClick(n.id, n.route)"
        >
          <component :is="ICON[n.tone]" :class="['mt-0.5 size-4 shrink-0', ICON_CLASS[n.tone]]" />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ n.title }}</p>
            <p v-if="n.description" class="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{{ n.description }}</p>
            <p class="mt-1 text-xs text-muted-foreground/70 tabular">{{ timeAgo(n.createdAt) }}</p>
          </div>
          <span v-if="!n.read" class="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" aria-hidden="true" />
        </button>
      </div>
    </PopoverContent>
  </Popover>
</template>
