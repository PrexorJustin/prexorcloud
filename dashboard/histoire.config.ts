import { defineConfig } from "histoire"
import { HstVue } from "@histoire/plugin-vue"
import vue from "@vitejs/plugin-vue"
import tailwindcss from "@tailwindcss/vite"
import path from "node:path"

export default defineConfig({
  plugins: [HstVue()],
  // @vitejs/plugin-vue is wired through `vite.plugins` below so .vue stories
  // get parsed and transformed; HstVue doesn't bundle it.
  setupFile: "./histoire.setup.ts",
  storyMatch: ["app/**/*.story.vue"],
  storyIgnored: ["**/node_modules/**", "**/.nuxt/**", "**/.output/**", "**/dist/**"],
  theme: {
    title: "PrexorCloud — Workbench",
    defaultColorScheme: "dark",
  },
  tree: {
    groups: [
      { id: "tokens",     title: "Tokens" },
      { id: "primitives", title: "Primitives" },
      { id: "layout",     title: "Layout" },
    ],
  },
  responsivePresets: [
    { label: "Mobile",  width: 375,  height: 667 },
    { label: "Tablet",  width: 768,  height: 1024 },
    { label: "Desktop", width: 1280, height: 800 },
    { label: "Wide",    width: 1920, height: 1080 },
  ],
  vite: {
    plugins: [vue(), tailwindcss()],
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "./app"),
        "~": path.resolve(__dirname, "./app"),
      },
    },
  },
})
