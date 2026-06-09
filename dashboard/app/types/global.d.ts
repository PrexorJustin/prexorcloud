import type { Ref } from 'vue'
import type { SseEvent } from '~/composables/useSseEventBus'

type SseHandler = (event: SseEvent) => void

interface SseEventBusState {
  handlers: Map<string, Set<SseHandler>>
  connected: Ref<boolean>
  lastSequence: Ref<number>
  connect: () => void
  disconnect: () => void
}

declare global {
  interface Window {
    __sseEventBus?: SseEventBusState
  }

   
  var __prexorcloud_sdk__: Record<string, unknown>
}

export {}
