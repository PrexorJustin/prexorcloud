/**
 * Populates the SDK global before any modules load.
 * The auto-generated bridge file at /sdk/prexorcloud.mjs reads from this global
 * and re-exports as proper ES module exports.
 *
 * We import Vue separately to guarantee ALL exports are available —
 * `export * from 'vue'` in sdk/index.ts may not capture every internal
 * when processed by the bundler (e.g. createSlots, createPropsRestProxy).
 */
import * as vue from 'vue'
import * as sdk from '~/sdk/index'

export default defineNuxtPlugin(() => {
  globalThis.__prexorcloud_sdk__ = { ...vue, ...sdk }
})
