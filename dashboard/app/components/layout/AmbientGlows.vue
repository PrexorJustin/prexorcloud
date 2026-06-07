<script setup lang="ts">
const store = useAppearanceStore()

function blobMixPercent(blob: { opacity: number; intensity?: number }) {
  const raw = blob.opacity * 3.3 * ((blob.intensity ?? 100) / 100) * (store.glowIntensity / 100)
  return Math.min(100, Math.max(0, raw))
}

function blobColorRef(color: string) {
  return color.startsWith("#") ? color : `var(--${color})`
}
</script>

<template>
  <div v-if="store.glowEnabled" class="fixed inset-0 pointer-events-none overflow-hidden z-0">
    <div
      v-for="(blob, i) in store.glowBlobs"
      :key="i"
      class="absolute rounded-full"
      :style="{
        left: `${blob.x}%`,
        top: `${blob.y}%`,
        transform: `translate(-50%, -50%) rotate(${blob.rotate}deg) scaleX(${blob.scaleX / 100}) scaleY(${blob.scaleY / 100})`,
        width: `${blob.size}px`,
        height: `${blob.size}px`,
        filter: `blur(${blob.blur}px)`,
        backgroundColor: `color-mix(in srgb, ${blobColorRef(blob.color)} ${blobMixPercent(blob)}%, transparent)`,
      }"
    />
  </div>
</template>
