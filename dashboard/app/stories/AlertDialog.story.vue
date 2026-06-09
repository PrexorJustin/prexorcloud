<script setup lang="ts">
import { ref } from "vue"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"

const open1 = ref(false)
const open2 = ref(false)
</script>

<template>
  <Story title="Alert dialog" group="primitives">
    <Variant title="Destructive confirmation">
      <Button variant="outline" class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="open1 = true">
        Delete group
      </Button>
      <AlertDialog v-model:open="open1">
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete group?</AlertDialogTitle>
            <AlertDialogDescription>
              All running instances will be stopped and the group configuration removed. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction class="bg-destructive text-destructive-foreground hover:bg-destructive/90">Delete</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Variant>

    <Variant title="Note">
      <p class="max-w-md text-sm text-muted-foreground">
        Prefer <code class="mono">&lt;ConfirmDialog&gt;</code> for typical confirm flows
        (it wraps this with the title/description/loading-state pattern). Use
        <code class="mono">&lt;AlertDialog&gt;</code> directly only when you need full control.
      </p>
    </Variant>
  </Story>
</template>
