<script setup lang="ts">
import { computed } from 'vue';
import { useWizardStore } from '@/stores/wizard';
import { yamlFor, type YamlFilename } from '@/lib/yaml';
import { highlightYaml } from '@/lib/highlight';

const wiz = useWizardStore();

const files = computed<YamlFilename[]>(() => wiz.filesForMode);
const active = computed<YamlFilename>(() => {
  const f = files.value;
  return (f.includes(wiz.activePreviewTab as YamlFilename) ? wiz.activePreviewTab : f[0]) as YamlFilename;
});
const yaml = computed(() => yamlFor(wiz.$state, active.value));
const lines = computed(() => yaml.value.split('\n').length);
const highlighted = computed(() => highlightYaml(yaml.value));

function selectTab(f: YamlFilename) {
  wiz.activePreviewTab = f;
}
</script>

<template>
  <div class="preview-pane">
    <div class="preview-head">
      <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:12px;">
        <div>
          <span class="preview-eyebrow"><span class="dot"></span>Live preview</span>
          <div class="preview-name">{{ active }}</div>
        </div>
        <div v-if="files.length > 1" class="preview-tabs">
          <button
            v-for="f in files"
            :key="f"
            class="preview-tab"
            :class="{ active: active === f }"
            @click="selectTab(f)"
          >{{ f }}</button>
        </div>
        <span v-else class="mono" style="color:var(--muted-foreground);font-size:11.5px">
          {{ lines }} lines
        </span>
      </div>
    </div>
    <pre class="preview-body mono" v-html="highlighted" />
    <div class="preview-foot">
      <span><span class="dot"></span>Updates as you type</span>
      <span class="mono">schema v0.18</span>
    </div>
  </div>
</template>
