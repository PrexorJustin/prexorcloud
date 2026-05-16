<script setup lang="ts">
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar"
</script>

<template>
  <Story title="Avatar" group="primitives">
    <Variant title="Sizes">
      <div class="flex items-center gap-3">
        <Avatar class="size-6 bg-primary/15 text-primary"><AvatarFallback class="text-[10px] font-bold">A</AvatarFallback></Avatar>
        <Avatar class="size-8 bg-primary/15 text-primary"><AvatarFallback class="text-xs font-bold">AB</AvatarFallback></Avatar>
        <Avatar class="size-10 bg-primary/15 text-primary"><AvatarFallback class="text-sm font-bold">AB</AvatarFallback></Avatar>
        <Avatar class="size-14 bg-primary/15 text-primary"><AvatarFallback class="text-base font-bold">AB</AvatarFallback></Avatar>
      </div>
    </Variant>

    <Variant title="Image with fallback">
      <Avatar class="size-12">
        <AvatarImage src="https://example.invalid/missing.png" alt="" />
        <AvatarFallback class="bg-primary text-primary-foreground font-bold">DV</AvatarFallback>
      </Avatar>
    </Variant>

    <Variant title="Tone-tinted (for role display)">
      <div class="flex items-center gap-3">
        <Avatar class="size-10 bg-primary/15 text-primary"><AvatarFallback class="text-sm font-bold">A</AvatarFallback></Avatar>
        <Avatar class="size-10 bg-success/15 text-success"><AvatarFallback class="text-sm font-bold">O</AvatarFallback></Avatar>
        <Avatar class="size-10 bg-warning/15 text-warning"><AvatarFallback class="text-sm font-bold">D</AvatarFallback></Avatar>
        <Avatar class="size-10 bg-muted-foreground/15 text-muted-foreground"><AvatarFallback class="text-sm font-bold">V</AvatarFallback></Avatar>
      </div>
    </Variant>
  </Story>
</template>
