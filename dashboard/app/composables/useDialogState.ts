/**
 * Boilerplate refs for dialogs that share the open/loading/error pattern.
 * Per-dialog form-field resets stay in the component because they vary.
 *
 * Usage:
 *   const { open, loading, error, reset } = useDialogState()
 *   function handleOpen(value: boolean) {
 *     open.value = value
 *     if (!value) {
 *       reset()
 *       // clear component-specific form state here
 *     }
 *   }
 */
export function useDialogState() {
  const open = ref(false)
  const loading = ref(false)
  const error = ref('')

  function reset() {
    error.value = ''
    loading.value = false
  }

  return { open, loading, error, reset }
}
