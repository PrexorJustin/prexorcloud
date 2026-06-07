/**
 * Client-side mermaid bootstrap. Renders each `.mermaid-wrapper` block
 * via `mermaid.render(id, source)` directly — one wrapper at a time, so
 * a parse error in one diagram doesn't poison the rest of the page and
 * the failure surfaces as an inline error message instead of a silent
 * empty box.
 *
 * Loaded by Starlight's Head override (src/components/docs/Head.astro).
 * The mermaid library itself is dynamically imported only when at least
 * one `.mermaid-wrapper` is present on the page, so docs pages without
 * diagrams pay zero JS cost beyond this small bootstrap.
 *
 * Theme: editorial monochrome (matches docs-editorial.css). Re-renders
 * on `data-theme` attribute change so light/dark toggles repaint.
 */

import { mermaidFontFamily, mermaidThemeDark, mermaidThemeLight } from './mermaid-theme.generated';

type MermaidApi = typeof import('mermaid').default;

let mermaidPromise: Promise<MermaidApi> | null = null;
let initialized = false;

function loadMermaid(): Promise<MermaidApi> {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then((m) => m.default);
  }
  return mermaidPromise;
}

function isLightTheme(): boolean {
  return document.documentElement.dataset.theme === 'light';
}

function configFor(isLight: boolean) {
  // `theme: 'base'` lets every themeVariable take effect; otherwise
  // mermaid's built-in 'dark'/'default' palettes win for unspecified keys
  // and the diagram bleeds the wrong colours.
  return {
    startOnLoad: false,
    theme: 'base' as const,
    securityLevel: 'loose' as const,
    fontFamily: mermaidFontFamily,
    flowchart: { useMaxWidth: true, htmlLabels: true, curve: 'basis' as const },
    // Palette is generated from design-system/dist/tokens.json (see
    // mermaid-theme.generated.ts). Cyan is the single accent (node borders);
    // slate/sand fills give the wireframe-y "kubernetes diagram" feel (README §10).
    themeVariables: isLight ? mermaidThemeLight : mermaidThemeDark,
  };
}

function renderError(wrapper: HTMLElement, source: string, err: unknown): void {
  const msg = err instanceof Error ? err.message : String(err);
  console.error('[mermaid] render failed', err);
  wrapper.innerHTML = `
    <div class="mermaid-error" role="note">
      <strong>diagram render failed</strong>
      <pre>${escapeHtml(msg)}</pre>
      <details><summary>source</summary><pre>${escapeHtml(source)}</pre></details>
    </div>`;
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

let renderToken = 0;

async function render(): Promise<void> {
  const myToken = ++renderToken;

  const wrappers = Array.from(document.querySelectorAll<HTMLElement>('.mermaid-wrapper'));
  if (wrappers.length === 0) return;

  let mermaid: MermaidApi;
  try {
    mermaid = await loadMermaid();
  } catch (err) {
    console.error('[mermaid] dynamic import failed', err);
    for (const w of wrappers) renderError(w, w.dataset.mermaidSource ?? '', err);
    return;
  }
  if (myToken !== renderToken) return; /* stale — newer render started */

  mermaid.initialize(configFor(isLightTheme()));
  initialized = true;

  for (let i = 0; i < wrappers.length; i++) {
    const wrapper = wrappers[i]!;
    const source = wrapper.dataset.mermaidSource ?? wrapper.textContent ?? '';
    if (!source.trim()) continue;
    const id = `pc-mermaid-${Date.now()}-${i}`;
    try {
      const { svg, bindFunctions } = await mermaid.render(id, source);
      if (myToken !== renderToken) return;
      // Inject the SVG; bindFunctions wires interactive callbacks (none in
      // our flowcharts today, but it's the documented API).
      wrapper.innerHTML = svg;
      const svgEl = wrapper.querySelector<SVGElement>('svg');
      if (svgEl) {
        // mermaid sometimes emits style="max-width: …px" inline that fights
        // our wrapper's responsive layout — strip the inline width hint and
        // let the wrapper's own max-width win.
        svgEl.removeAttribute('width');
        svgEl.style.maxWidth = '100%';
        svgEl.style.height = 'auto';
        svgEl.style.display = 'block';
      }
      bindFunctions?.(wrapper);
    } catch (err) {
      renderError(wrapper, source, err);
    }
  }
}

// Initial run — handle both already-loaded and still-parsing pages.
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => void render());
} else {
  void render();
}

// Astro view transitions (only fires when `<ClientRouter />` is enabled;
// harmless no-op otherwise).
document.addEventListener('astro:page-load', () => void render());

// Re-render on theme toggle. Starlight flips `data-theme` on <html>.
new MutationObserver((muts) => {
  if (muts.some((m) => m.attributeName === 'data-theme') && initialized) {
    void render();
  }
}).observe(document.documentElement, {
  attributes: true,
  attributeFilter: ['data-theme'],
});
