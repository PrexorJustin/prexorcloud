import { describe, it, expect } from 'vitest'
import { h } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import PageHeader from '../PageHeader.vue'

describe('PageHeader', () => {
  it('renders the title in the heading element', async () => {
    const wrapper = await mountSuspended(PageHeader, { props: { title: 'Nodes' } })
    expect(wrapper.find('h1').text()).toBe('Nodes')
  })

  it('renders the description and eyebrow when provided', async () => {
    const wrapper = await mountSuspended(PageHeader, {
      props: { title: 'Nodes', description: 'Cluster machines', eyebrow: 'Cluster' },
    })
    expect(wrapper.text()).toContain('Cluster machines')
    expect(wrapper.find('.eyebrow').text()).toBe('Cluster')
  })

  it('omits the description and eyebrow when neither prop nor slot is given', async () => {
    const wrapper = await mountSuspended(PageHeader, { props: { title: 'Nodes' } })
    expect(wrapper.find('.eyebrow').exists()).toBe(false)
    // Only the title <p>-less heading; no description paragraph.
    expect(wrapper.findAll('p')).toHaveLength(0)
  })

  it('prefers the eyebrow slot over the eyebrow prop', async () => {
    const wrapper = await mountSuspended(PageHeader, {
      props: { title: 'Nodes', eyebrow: 'prop value' },
      slots: { eyebrow: () => 'slot value' },
    })
    expect(wrapper.find('.eyebrow').text()).toBe('slot value')
  })

  it('renders the actions slot only when supplied', async () => {
    const without = await mountSuspended(PageHeader, { props: { title: 'Nodes' } })
    expect(without.find('.shrink-0').exists()).toBe(false)

    const withActions = await mountSuspended(PageHeader, {
      props: { title: 'Nodes' },
      slots: { actions: () => h('button', 'New') },
    })
    expect(withActions.find('.shrink-0').exists()).toBe(true)
    expect(withActions.find('button').text()).toBe('New')
  })
})
