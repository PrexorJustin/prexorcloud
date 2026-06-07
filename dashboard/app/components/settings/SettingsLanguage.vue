<script setup lang="ts">
import { Languages } from "lucide-vue-next"

const { t, locale, locales, setLocale } = useI18n()

const available = computed(() =>
  (locales.value as { code: string; name?: string }[]).map(l => ({
    code: l.code,
    name: l.name ?? l.code,
  })),
)
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
    <h3 class="text-base font-semibold text-foreground mb-1">{{ t('settings.language.title') }}</h3>
    <p class="text-sm text-muted-foreground mb-4">{{ t('settings.language.description') }}</p>
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-3">
      <button
        v-for="lang in available"
        :key="lang.code"
        :class="[
          'flex flex-col items-center gap-2 p-4 rounded-xl border transition-all cursor-pointer',
          locale === lang.code
            ? 'border-primary bg-primary/10'
            : 'border-glass-border hover:border-primary/40 hover:bg-glass-hover',
        ]"
        @click="setLocale(lang.code as 'en' | 'de')"
      >
        <Languages
          class="size-6"
          :class="locale === lang.code ? 'text-primary' : 'text-muted-foreground'"
        />
        <span class="text-sm font-medium text-foreground">{{ lang.name }}</span>
        <span class="text-xs text-muted-foreground uppercase">{{ lang.code }}</span>
      </button>
    </div>
  </div>
</template>
