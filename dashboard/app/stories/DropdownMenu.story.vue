<script setup lang="ts">
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  DropdownMenuCheckboxItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
} from "@/components/ui/dropdown-menu"
import { Button } from "@/components/ui/button"
import { ref } from "vue"
import { Pause, Play, RefreshCw, Trash2, MoreVertical } from "lucide-vue-next"

const showLogs = ref(true)
const showMetrics = ref(false)
const sortBy = ref("name")
</script>

<template>
  <Story title="Dropdown menu" group="primitives">
    <Variant title="Action menu">
      <DropdownMenu>
        <DropdownMenuTrigger as-child>
          <Button variant="ghost" size="icon" aria-label="Actions"><MoreVertical class="size-4" /></Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuLabel>Instance actions</DropdownMenuLabel>
          <DropdownMenuItem><RefreshCw class="mr-2 size-4" /> Restart</DropdownMenuItem>
          <DropdownMenuItem><Pause class="mr-2 size-4" /> Pause</DropdownMenuItem>
          <DropdownMenuItem><Play class="mr-2 size-4" /> Resume</DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem class="text-destructive focus:text-destructive">
            <Trash2 class="mr-2 size-4" /> Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </Variant>

    <Variant title="Checkbox + radio items">
      <DropdownMenu>
        <DropdownMenuTrigger as-child>
          <Button variant="outline">Display</Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent class="w-56">
          <DropdownMenuLabel>Columns</DropdownMenuLabel>
          <DropdownMenuCheckboxItem v-model="showLogs">Logs</DropdownMenuCheckboxItem>
          <DropdownMenuCheckboxItem v-model="showMetrics">Metrics</DropdownMenuCheckboxItem>
          <DropdownMenuSeparator />
          <DropdownMenuLabel>Sort by</DropdownMenuLabel>
          <DropdownMenuRadioGroup v-model="sortBy">
            <DropdownMenuRadioItem value="name">Name</DropdownMenuRadioItem>
            <DropdownMenuRadioItem value="created">Created</DropdownMenuRadioItem>
            <DropdownMenuRadioItem value="size">Size</DropdownMenuRadioItem>
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </Variant>
  </Story>
</template>
