import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ConfirmDialog from '../ConfirmDialog.vue'

// Reka UI Dialog teleports content to <body>
function bodyText() {
  return document.body.textContent ?? ''
}

function findBodyButton(text: string) {
  return Array.from(document.body.querySelectorAll('button')).find(
    b => b.textContent?.includes(text),
  )
}

describe('ConfirmDialog', () => {
  it('renders title and description in portal when open', async () => {
    await mountSuspended(ConfirmDialog, {
      props: {
        open: true,
        title: 'Delete server?',
        description: 'This will stop all instances.',
      },
    })
    expect(bodyText()).toContain('Delete server?')
    expect(bodyText()).toContain('This will stop all instances.')
  })

  it('uses default title and description', async () => {
    await mountSuspended(ConfirmDialog, {
      props: { open: true },
    })
    expect(bodyText()).toContain('Are you sure?')
    expect(bodyText()).toContain('This action cannot be undone.')
  })

  it('shows custom confirm label', async () => {
    await mountSuspended(ConfirmDialog, {
      props: { open: true, confirmLabel: 'Delete forever' },
    })
    expect(bodyText()).toContain('Delete forever')
  })

  it('shows "Please wait..." when loading', async () => {
    await mountSuspended(ConfirmDialog, {
      props: { open: true, loading: true, confirmLabel: 'Delete' },
    })
    expect(bodyText()).toContain('Please wait...')
  })

  it('renders confirm and cancel buttons', async () => {
    await mountSuspended(ConfirmDialog, {
      props: { open: true, confirmLabel: 'Confirm' },
    })
    const confirmBtn = findBodyButton('Confirm')
    const cancelBtn = findBodyButton('Cancel')
    expect(confirmBtn).toBeDefined()
    expect(cancelBtn).toBeDefined()
  })

  it('renders no content when closed', async () => {
    const wrapper = await mountSuspended(ConfirmDialog, {
      props: { open: false },
    })
    // Dialog content should not be rendered when closed
    expect(wrapper.text()).toBe('')
  })
})
