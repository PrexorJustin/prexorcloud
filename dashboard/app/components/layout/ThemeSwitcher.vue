<script setup lang="ts">
import { Paintbrush, Sun, Moon, Monitor, Check, Palette } from 'lucide-vue-next'
import { Separator } from '~/components/ui/separator'
import { Button } from '~/components/ui/button'
import { Label } from '~/components/ui/label'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '~/components/ui/popover'
import { accentColors, radii } from '~/lib/theme-data'

const { t } = useI18n()
const colorMode = useColorMode()
const appearance = useAppearanceStore()
const router = useRouter()
const popoverOpen = ref(false)

function goToAdvanced() {
  popoverOpen.value = false
  appearance.navigateToSettings("appearance", "customize")
  router.push("/settings")
}
</script>

<template>
  <Popover v-model:open="popoverOpen">
    <PopoverTrigger as-child>
      <Button variant="ghost" size="icon" class="size-8">
        <Paintbrush class="size-4" />
        <span class="sr-only">{{ t('components.themeSwitcher.customizeTheme') }}</span>
      </Button>
    </PopoverTrigger>
    <PopoverContent align="end" class="w-80">
      <div class="grid gap-5">
        <div class="space-y-1">
          <p class="text-sm font-semibold text-foreground">{{ t('components.themeSwitcher.customize') }}</p>
          <p class="text-xs text-muted-foreground">{{ t('components.themeSwitcher.subtitle') }}</p>
        </div>

        <!-- Color -->
        <div class="space-y-2">
          <Label>{{ t('components.themeSwitcher.color') }}</Label>
          <div class="grid grid-cols-3 gap-2">
            <button
              v-for="color in accentColors"
              :key="color.name"
              class="inline-flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs transition-all"
              :class="appearance.accentColor === color.name
                ? 'border-primary bg-primary/10 text-foreground'
                : 'border-glass-border text-muted-foreground hover:text-foreground hover:border-foreground/20'"
              @click="appearance.setAccentColor(color.name)"
            >
              <span
                class="flex size-4 shrink-0 items-center justify-center rounded-full"
                :style="{ backgroundColor: color.value }"
              >
                <Check v-if="appearance.accentColor === color.name" class="size-2.5 text-primary-foreground" />
              </span>
              {{ color.name }}
            </button>
          </div>
        </div>

        <!-- Radius -->
        <div class="space-y-2">
          <Label>{{ t('components.themeSwitcher.radius') }}</Label>
          <div class="grid grid-cols-5 gap-2">
            <button
              v-for="r in radii"
              :key="r"
              class="inline-flex items-center justify-center rounded-lg border px-3 py-1.5 text-xs transition-all"
              :class="appearance.radius === r
                ? 'border-primary bg-primary/10 text-foreground'
                : 'border-glass-border text-muted-foreground hover:text-foreground hover:border-foreground/20'"
              @click="appearance.setRadius(r)"
            >
              {{ r }}
            </button>
          </div>
        </div>

        <!-- Mode -->
        <div class="space-y-2">
          <Label>{{ t('components.themeSwitcher.mode') }}</Label>
          <div class="grid grid-cols-3 gap-2">
            <button
              class="inline-flex items-center justify-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs transition-all"
              :class="colorMode.preference === 'light'
                ? 'border-primary bg-primary/10 text-foreground'
                : 'border-glass-border text-muted-foreground hover:text-foreground hover:border-foreground/20'"
              @click="colorMode.preference = 'light'"
            >
              <Sun class="size-3.5" />
              {{ t('components.themeSwitcher.light') }}
            </button>
            <button
              class="inline-flex items-center justify-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs transition-all"
              :class="colorMode.preference === 'dark'
                ? 'border-primary bg-primary/10 text-foreground'
                : 'border-glass-border text-muted-foreground hover:text-foreground hover:border-foreground/20'"
              @click="colorMode.preference = 'dark'"
            >
              <Moon class="size-3.5" />
              {{ t('components.themeSwitcher.dark') }}
            </button>
            <button
              class="inline-flex items-center justify-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs transition-all"
              :class="colorMode.preference === 'system'
                ? 'border-primary bg-primary/10 text-foreground'
                : 'border-glass-border text-muted-foreground hover:text-foreground hover:border-foreground/20'"
              @click="colorMode.preference = 'system'"
            >
              <Monitor class="size-3.5" />
              {{ t('components.themeSwitcher.system') }}
            </button>
          </div>
        </div>
        <Separator class="bg-glass-border" />

        <button
          class="inline-flex items-center gap-2 w-full rounded-lg px-3 py-2 text-xs text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
          @click="goToAdvanced()"
        >
          <Palette class="size-3.5" />
          {{ t('components.themeSwitcher.advanced') }}
        </button>
      </div>
    </PopoverContent>
  </Popover>
</template>
