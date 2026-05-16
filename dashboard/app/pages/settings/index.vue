<script setup lang="ts">
import {SlidersHorizontal, Palette, Sparkles} from "lucide-vue-next"
import SettingsAmbientGlows from "~/components/settings/SettingsAmbientGlows.vue";
import SettingsThemePalette from "~/components/settings/SettingsThemePalette.vue";
import SettingsThemeMode from "~/components/settings/SettingsThemeMode.vue";
import SettingsAccentColor from "~/components/settings/SettingsAccentColor.vue";
import SettingsBorderRadius from "~/components/settings/SettingsBorderRadius.vue";
import SettingsLanguage from "~/components/settings/SettingsLanguage.vue";

const {t} = useI18n()
const {subTab: _navSubTab} = useAppearanceStore().consumeSettingsNav()
const appearanceSubTab = ref(_navSubTab || "preferences")

const appearanceSubTabs = computed(() => [
  {key: "preferences", label: t("settings.tabs.preferences"), icon: SlidersHorizontal},
  {key: "customize", label: t("settings.tabs.customize"), icon: Palette},
])

// Customize sub-groups
const customizeGroup = ref("palette")

const customizeGroups = computed(() => [
  {key: "palette", label: t("settings.groups.palette"), icon: Palette},
  {key: "glows", label: t("settings.groups.glows"), icon: Sparkles},
])
</script>

<template>
  <div class="flex flex-col gap-6 flex-1">
    <PageHeader
      :title="t('settings.title')"
      :description="t('settings.description')"
    />

    <!-- Sub-tabs -->
    <nav class="flex gap-1 border-b border-glass-border -mb-px">
      <button
          v-for="sub in appearanceSubTabs"
          :key="sub.key"
          :class="[
          'inline-flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors -mb-px',
          appearanceSubTab === sub.key
            ? 'border-primary text-foreground'
            : 'border-transparent text-muted-foreground hover:text-foreground hover:border-muted-foreground/30',
        ]"
          @click="appearanceSubTab = sub.key"
      >
        <component :is="sub.icon" class="size-4"/>
        {{ sub.label }}
      </button>
    </nav>

    <!-- Preferences -->
    <div v-show="appearanceSubTab === 'preferences'" class="flex flex-col gap-6">
      <SettingsLanguage/>
      <SettingsThemeMode/>
      <SettingsAccentColor/>
      <SettingsBorderRadius/>
    </div>

    <!-- Customize -->
    <div v-show="appearanceSubTab === 'customize'">
      <!-- Sub-group nav -->
      <div class="flex gap-2 mb-6">
        <button
            v-for="group in customizeGroups"
            :key="group.key"
            :class="[
            'inline-flex items-center gap-2 px-3.5 py-1.5 text-xs font-medium rounded-lg border transition-all',
            customizeGroup === group.key
              ? 'border-primary bg-primary/10 text-foreground'
              : 'border-glass-border text-muted-foreground hover:text-foreground hover:bg-glass-hover',
          ]"
            @click="customizeGroup = group.key"
        >
          <component :is="group.icon" class="size-3.5"/>
          {{ group.label }}
        </button>
      </div>

      <!-- Customize sub-group content -->
      <SettingsThemePalette v-show="customizeGroup === 'palette'"/>
      <SettingsAmbientGlows v-show="customizeGroup === 'glows'"/>
    </div>
  </div>
</template>
