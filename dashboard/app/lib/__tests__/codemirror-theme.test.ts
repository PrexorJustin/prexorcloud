import { describe, it, expect } from 'vitest'
import {
  langMap,
  prexorHighlight,
  prexorThemeDark,
  prexorThemeLight,
  prexorTheme,
} from '../codemirror-theme'

describe('lib/codemirror-theme', () => {
  it('langMap covers the editor-supported languages', () => {
    expect(Object.keys(langMap).sort()).toEqual(
      ['css', 'html', 'java', 'javascript', 'json', 'markdown', 'typescript', 'xml', 'yaml'].sort(),
    )
  })

  it('every langMap entry is a factory that produces an extension', () => {
    for (const [name, factory] of Object.entries(langMap)) {
      expect(typeof factory, name).toBe('function')
      expect(factory(), name).toBeTruthy()
    }
  })

  it('typescript and javascript resolve to distinct configured extensions', () => {
    // Both wrap the same lang package but with different options, so the
    // produced extension objects must not be identity-equal.
    expect(langMap.typescript!()).not.toBe(langMap.javascript!())
  })

  it('exposes a defined highlight style', () => {
    expect(prexorHighlight).toBeTruthy()
  })

  it('prexorTheme(true) returns the dark theme, prexorTheme(false) the light theme', () => {
    expect(prexorTheme(true)).toBe(prexorThemeDark)
    expect(prexorTheme(false)).toBe(prexorThemeLight)
  })

  it('the dark and light themes are distinct extension objects', () => {
    expect(prexorThemeDark).not.toBe(prexorThemeLight)
  })
})
