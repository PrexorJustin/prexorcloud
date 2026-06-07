<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue"
import * as echarts from "echarts/core"
import { CanvasRenderer } from "echarts/renderers"
import { LineChart } from "echarts/charts"
import { GridComponent, TooltipComponent } from "echarts/components"
import { cn } from "@/lib/utils"

echarts.use([CanvasRenderer, LineChart, GridComponent, TooltipComponent])

export type SparklineTone = "primary" | "secondary" | "success" | "warning" | "destructive" | "muted"

const props = withDefaults(defineProps<{
  data: number[]
  tone?: SparklineTone
  smooth?: boolean
  area?: boolean
  height?: number | string
  class?: HTMLAttributes["class"]
}>(), {
  tone: "primary",
  smooth: true,
  area: true,
  height: 36,
})

const colorVar: Record<SparklineTone, string> = {
  primary:     "var(--primary)",
  secondary:   "var(--secondary)",
  success:     "var(--success)",
  warning:     "var(--warning)",
  destructive: "var(--destructive)",
  muted:       "var(--muted-foreground)",
}

const heightStyle = computed(() => (typeof props.height === "number" ? `${props.height}px` : props.height))

const containerRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

function buildOption() {
  const stroke = colorVar[props.tone]
  return {
    grid: { left: 0, right: 0, top: 2, bottom: 2 },
    xAxis: { type: "category" as const, show: false, boundaryGap: false, data: props.data.map((_, i) => i) },
    yAxis: { type: "value" as const, show: false, scale: true },
    tooltip: { show: false },
    animationDuration: 300,
    series: [{
      type: "line" as const,
      data: props.data,
      smooth: props.smooth,
      symbol: "none" as const,
      lineStyle: { width: 1.5, color: stroke },
      areaStyle: props.area
        ? {
            color: {
              type: "linear" as const,
              x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: `color-mix(in srgb, ${stroke} 35%, transparent)` },
                { offset: 1, color: `color-mix(in srgb, ${stroke} 0%, transparent)` },
              ],
            },
          }
        : undefined,
    }],
  }
}

function resizeIfReady() {
  if (!chart || !containerRef.value) return
  const { width, height } = containerRef.value.getBoundingClientRect()
  if (width > 0 && height > 0) chart.resize()
}

onMounted(() => {
  if (!containerRef.value) return
  // Use queueMicrotask so the container has applied its computed height before
  // echarts measures the canvas — avoids the "renders blank because parent is
  // 0×0" failure mode in iframes (Histoire) and conditional-render parents.
  queueMicrotask(() => {
    if (!containerRef.value) return
    chart = echarts.init(containerRef.value, undefined, { renderer: "canvas" })
    chart.setOption(buildOption())

    resizeObserver = new ResizeObserver(resizeIfReady)
    resizeObserver.observe(containerRef.value)
  })
})

watch(() => [props.data, props.tone, props.smooth, props.area], () => {
  if (!chart) return
  chart.setOption(buildOption(), { notMerge: true })
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div
    ref="containerRef"
    :class="cn('relative w-full', props.class)"
    :style="{ height: heightStyle, minHeight: heightStyle }"
  />
</template>
