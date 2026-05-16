import { describe, it, expect } from 'vitest'
import { renderMiniMessage } from '../minimessage'

describe('renderMiniMessage', () => {
  it('returns plain text unchanged (with HTML escaping)', () => {
    expect(renderMiniMessage('hello')).toBe('hello')
    expect(renderMiniMessage('A & B')).toBe('A &amp; B')
  })

  it('wraps the active named-color region in a span with the matching hex', () => {
    expect(renderMiniMessage('<red>hi</red>')).toBe('<span style="color:#FF5555">hi</span>')
  })

  it('supports the <#rrggbb> raw hex syntax', () => {
    expect(renderMiniMessage('<#aabbcc>x</#aabbcc>')).toBe('<span style="color:#aabbcc">x</span>')
  })

  it('supports <color:NAME> syntax for named colors', () => {
    expect(renderMiniMessage('<color:blue>x</color:blue>')).toBe('<span style="color:#5555FF">x</span>')
  })

  it('bold + italic + underlined + strikethrough decoration tags wrap content', () => {
    expect(renderMiniMessage('<bold>b</bold>')).toBe('<b>b</b>')
    expect(renderMiniMessage('<b>b</b>')).toBe('<b>b</b>')
    expect(renderMiniMessage('<italic>i</italic>')).toBe('<em>i</em>')
    expect(renderMiniMessage('<i>i</i>')).toBe('<em>i</em>')
    expect(renderMiniMessage('<underlined>u</underlined>')).toBe('<u>u</u>')
    expect(renderMiniMessage('<u>u</u>')).toBe('<u>u</u>')
    expect(renderMiniMessage('<strikethrough>s</strikethrough>')).toBe('<s>s</s>')
    expect(renderMiniMessage('<st>s</st>')).toBe('<s>s</s>')
  })

  it('stacks nested decorations and renders all wrappers', () => {
    // applyStyle wraps bold first then italic, so the italic wrapper ends
    // up on the outside even for `<bold><italic>x</italic></bold>`.
    const out = renderMiniMessage('<bold><italic>x</italic></bold>')
    expect(out).toBe('<em><b>x</b></em>')
  })

  it('a named color inside a decoration stacks them together', () => {
    const out = renderMiniMessage('<red><b>x</b></red>')
    expect(out).toContain('<span style="color:#FF5555">')
    expect(out).toContain('<b>x</b>')
  })

  it('<reset> drops every style back to the base', () => {
    const out = renderMiniMessage('<red>a<reset>b')
    expect(out).toBe('<span style="color:#FF5555">a</span>b')
  })

  it('ignores closing tags with no matching opener (no crash)', () => {
    expect(() => renderMiniMessage('</red>')).not.toThrow()
  })

  it('escapes < and & inside text', () => {
    expect(renderMiniMessage('A&B')).toBe('A&amp;B')
  })
})
