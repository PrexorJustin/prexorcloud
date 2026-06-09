import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import FileTreeNode from '../templates/FileTreeNode.vue'
import type { TreeNode } from '../templates/FileTreeNode.vue'

function file(overrides: Partial<TreeNode> = {}): TreeNode {
  return { name: 'server.properties', path: 'server.properties', isDirectory: false, size: 1024, ...overrides }
}

function dir(overrides: Partial<TreeNode> = {}): TreeNode {
  return { name: 'plugins', path: 'plugins', isDirectory: true, size: 0, ...overrides }
}

function props(node: TreeNode, overrides: Record<string, unknown> = {}) {
  return { node, getChangeType: () => null, ...overrides }
}

describe('FileTreeNode', () => {
  it('renders the node name', async () => {
    const wrapper = await mountSuspended(FileTreeNode, { props: props(file()) })
    expect(wrapper.text()).toContain('server.properties')
  })

  it('shows a size for a file but not for a directory', async () => {
    const f = await mountSuspended(FileTreeNode, { props: props(file()) })
    expect(f.find('.tabular-nums').exists()).toBe(true)
    const d = await mountSuspended(FileTreeNode, { props: props(dir()) })
    expect(d.find('.tabular-nums').exists()).toBe(false)
  })

  it('renders the change-type indicator returned by getChangeType', async () => {
    const wrapper = await mountSuspended(FileTreeNode, {
      props: props(file(), { getChangeType: () => 'modified' }),
    })
    expect(wrapper.text()).toContain('M')
  })

  it('renders children when an expanded directory has them', async () => {
    const tree = dir({ expanded: true, children: [file({ name: 'config.yml', path: 'plugins/config.yml' })] })
    const wrapper = await mountSuspended(FileTreeNode, { props: props(tree) })
    expect(wrapper.text()).toContain('config.yml')
  })

  it('hides children when the directory is collapsed', async () => {
    const tree = dir({ expanded: false, children: [file({ name: 'config.yml', path: 'plugins/config.yml' })] })
    const wrapper = await mountSuspended(FileTreeNode, { props: props(tree) })
    expect(wrapper.text()).not.toContain('config.yml')
  })

  it('emits click with the node when the row is clicked', async () => {
    const node = file()
    const wrapper = await mountSuspended(FileTreeNode, { props: props(node) })
    await wrapper.find('div.cursor-pointer').trigger('click')
    expect(wrapper.emitted('click')?.[0]).toEqual([node])
  })

  it('emits dblclick with the node for a file', async () => {
    const node = file()
    const wrapper = await mountSuspended(FileTreeNode, { props: props(node) })
    await wrapper.find('div.cursor-pointer').trigger('dblclick')
    expect(wrapper.emitted('dblclick')?.[0]).toEqual([node])
  })

  it('emits delete with the path from the delete action', async () => {
    const wrapper = await mountSuspended(FileTreeNode, { props: props(file()) })
    await wrapper.find('button[title="Delete"]').trigger('click')
    expect(wrapper.emitted('delete')?.[0]).toEqual(['server.properties'])
  })

  it('emits newFile and newDir with the directory path', async () => {
    const wrapper = await mountSuspended(FileTreeNode, { props: props(dir()) })
    await wrapper.find('button[title="New file"]').trigger('click')
    await wrapper.find('button[title="New folder"]').trigger('click')
    expect(wrapper.emitted('newFile')?.[0]).toEqual(['plugins'])
    expect(wrapper.emitted('newDir')?.[0]).toEqual(['plugins'])
  })

  it('shows the Extract action only for .zip files', async () => {
    const plain = await mountSuspended(FileTreeNode, { props: props(file()) })
    expect(plain.find('button[title="Extract ZIP"]').exists()).toBe(false)
    const zip = await mountSuspended(FileTreeNode, {
      props: props(file({ name: 'world.zip', path: 'world.zip' })),
    })
    expect(zip.find('button[title="Extract ZIP"]').exists()).toBe(true)
  })

  it('hides the action buttons in readonly mode', async () => {
    const wrapper = await mountSuspended(FileTreeNode, { props: props(file(), { readonly: true }) })
    expect(wrapper.find('button[title="Delete"]').exists()).toBe(false)
    expect(wrapper.find('div.cursor-pointer').attributes('draggable')).toBe('false')
  })

  it('shows a rename input and confirms it on Enter', async () => {
    const node = file()
    const wrapper = await mountSuspended(FileTreeNode, {
      props: props(node, { renamingPath: 'server.properties', renameValue: 'renamed.txt' }),
    })
    const input = wrapper.find('input')
    expect(input.exists()).toBe(true)
    await input.trigger('keyup.enter')
    expect(wrapper.emitted('confirmRename')?.[0]).toEqual([node])
  })
})
