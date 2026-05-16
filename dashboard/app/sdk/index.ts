/**
 * PrexorCloud Module SDK — Dashboard Source
 *
 * This file is the source of truth for everything the module SDK exposes.
 * The bridge file at /sdk/prexorcloud.mjs is AUTO-GENERATED from this file
 * by the sdk-bridge Nuxt module (app/modules/sdk-bridge.ts).
 *
 * Module frontends import from "@prexorcloud/module-sdk" which resolves to
 * the bridge via the browser import map.
 *
 * Exports:
 * - Vue reactivity primitives (wildcard re-export)
 * - Pinia state management
 * - Shared layout/data components
 * - All shadcn-vue UI components
 * - Composables for API, pagination, filtering
 * - Utility functions
 * - Module lifecycle helpers
 */

// ─── Vue (wildcard re-export — includes reactivity, lifecycle, AND compiler runtime) ──
// This is critical: compiled SFCs import internal helpers like createElementVNode,
// openBlock, etc. from 'vue'. Since modules alias 'vue' → '@prexorcloud/module-sdk',
// we must re-export everything.
import { onUnmounted, getCurrentInstance, type Component } from 'vue'
import { useSseEventBus, type SseEvent } from '~/composables/useSseEventBus'

export * from 'vue'

// ─── SDK compatibility ─────────────────────────────────────────────────
export const MODULE_SDK_VERSION = 1
export const SUPPORTED_MODULE_SDK_VERSIONS = [MODULE_SDK_VERSION] as const

export function isSupportedModuleSdkVersion(sdkVersion: number): boolean {
  return SUPPORTED_MODULE_SDK_VERSIONS.includes(sdkVersion as typeof SUPPORTED_MODULE_SDK_VERSIONS[number])
}

// ─── Pinia ─────────────────────────────────────────────────────────────
export { defineStore, storeToRefs } from 'pinia'

// ─── Shared layout & data components ───────────────────────────────────
export { default as PageHeader } from '~/components/PageHeader.vue'
export { default as FilterToolbar } from '~/components/FilterToolbar.vue'
export { default as EmptyState } from '~/components/EmptyState.vue'
export { default as LoadingSkeleton } from '~/components/LoadingSkeleton.vue'
export { default as ConfirmDialog } from '~/components/ConfirmDialog.vue'

// ─── shadcn-vue UI components ──────────────────────────────────────────
// Button
export { Button } from '~/components/ui/button'

// Badge
export { Badge } from '~/components/ui/badge'

// Card
export { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '~/components/ui/card'

// Input & Textarea
export { Input } from '~/components/ui/input'
export { Textarea } from '~/components/ui/textarea'
export { Label } from '~/components/ui/label'

// Select
export { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from '~/components/ui/select'

// Checkbox & Switch
export { Checkbox } from '~/components/ui/checkbox'
export { Switch } from '~/components/ui/switch'

// Dialog
export {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
  DialogFooter, DialogTrigger, DialogClose,
} from '~/components/ui/dialog'

// Alert Dialog
export {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader,
  AlertDialogTitle, AlertDialogTrigger,
} from '~/components/ui/alert-dialog'

// Sheet
export { Sheet, SheetContent, SheetHeader, SheetTitle, SheetTrigger, SheetFooter, SheetClose } from '~/components/ui/sheet'

// Dropdown Menu
export {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator,
  DropdownMenuTrigger, DropdownMenuGroup, DropdownMenuLabel,
  DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger,
} from '~/components/ui/dropdown-menu'

// Table
export { Table, TableBody, TableCell, TableHead, TableHeader, TableRow, TableEmpty } from '~/components/ui/table'

// Tabs
export { Tabs, TabsContent, TabsList, TabsTrigger } from '~/components/ui/tabs'

// Tooltip
export { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '~/components/ui/tooltip'

// Separator
export { Separator } from '~/components/ui/separator'

// Skeleton
export { Skeleton } from '~/components/ui/skeleton'

// Avatar
export { Avatar, AvatarImage } from '~/components/ui/avatar'

// Progress
export { Progress } from '~/components/ui/progress'

// Scroll Area
export { ScrollArea, ScrollBar } from '~/components/ui/scroll-area'

// Alert
export { Alert, AlertTitle, AlertDescription } from '~/components/ui/alert'

// Spinner
export { Spinner } from '~/components/ui/spinner'

// Radio Group
export { RadioGroup, RadioGroupItem } from '~/components/ui/radio-group'

// Toggle & Toggle Group
export { Toggle } from '~/components/ui/toggle'
export { ToggleGroup, ToggleGroupItem } from '~/components/ui/toggle-group'

// Slider
export { Slider } from '~/components/ui/slider'

// Breadcrumb
export { Breadcrumb, BreadcrumbItem, BreadcrumbLink, BreadcrumbList, BreadcrumbPage, BreadcrumbSeparator } from '~/components/ui/breadcrumb'

// Collapsible
export { Collapsible, CollapsibleContent, CollapsibleTrigger } from '~/components/ui/collapsible'

// Popover
export { Popover, PopoverContent, PopoverTrigger } from '~/components/ui/popover'

// Form
export { FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from '~/components/ui/form'

// ─── Composables ───────────────────────────────────────────────────────
export type { PaginatedResponse } from '~/types/api'
export { useApiClient } from '~/composables/useApiClient'
export { useScopedApi } from '~/composables/useScopedApi'
export { useCapability } from '~/composables/useCapability'
export type { CapabilityBinding } from '~/composables/useCapability'
export { usePaginatedData } from '~/composables/usePaginatedData'
export { useFilteredList } from '~/composables/useFilteredList'
export type { FilterOption } from '~/composables/useFilteredList'
export { useModuleGuard } from '~/composables/useModuleGuard'
export { useSseEventBus }

/**
 * Subscribes to SSE events matching the given types.
 * Automatically connects and cleans up on component unmount.
 */
export function useModuleEvents(eventTypes: string[], handler: (event: Record<string, unknown>) => void) {
  const bus = useSseEventBus()
  const typedHandler = (event: SseEvent) => handler(event as unknown as Record<string, unknown>)
  bus.on(eventTypes, typedHandler)
  bus.connect()
  if (getCurrentInstance()) {
    onUnmounted(() => bus.off(eventTypes, typedHandler))
  }
  return { stop: () => bus.off(eventTypes, typedHandler) }
}

// ─── Auth ─────────────────────────────────────────────────────────────
export { useAuthStore } from '~/stores/auth'
/** @deprecated Use {@link useAuthStore} instead. */
export { useAuthStore as useAuth } from '~/stores/auth'

// ─── Utilities ─────────────────────────────────────────────────────────
export { cn, timeAgo, formatUptime, timeUntil, formatBytes, formatMemory, getInitials } from '~/lib/utils'
export { toast } from 'vue-sonner'

// ─── Router (Nuxt) ────────────────────────────────────────────────────
// Re-export navigateTo so modules can do client-side navigation
export { navigateTo, useRoute, useRouter } from '#app/composables/router'

// ─── Module lifecycle ──────────────────────────────────────────────────
export interface ModuleDefinition {
  /** Vue components keyed by export name (used for route → component resolution) */
  components: Record<string, Component>
  /** Optional setup function called once when the module is loaded */
  setup?: () => void | Promise<void>
}

/**
 * Called by module entry files to register their components.
 * The module loader reads the return value to resolve route → component.
 *
 * Usage in a module's entry.ts:
 * ```ts
 * import { defineModule } from '@prexorcloud/module-sdk'
 * import AnnouncementsPage from './pages/AnnouncementsPage.vue'
 * import AnnouncementForm from './pages/AnnouncementForm.vue'
 *
 * export default defineModule({
 *   components: { AnnouncementsPage, AnnouncementForm },
 * })
 * ```
 */
export function defineModule(definition: ModuleDefinition): ModuleDefinition {
  return definition
}
