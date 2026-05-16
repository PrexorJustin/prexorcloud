/*
 * Dynamic OpenGraph image generator. Emits one PNG per docs entry at
 * `/og/<slug>.png` plus a synthetic `/og/default.png` for the landing
 * page and any non-docs route.
 *
 * Pipeline: satori (JSX → SVG, embeds title/description) → resvg-js
 * (SVG → PNG bytes). Runs at build time for every Starlight docs entry,
 * so the dist has 1200×630 PNGs ready to serve as static assets.
 *
 * Layout: dark gradient background, cyan title, muted-foreground
 * description, "prexor.cloud" mono footer with the section breadcrumb.
 * Inter + JetBrains Mono fetched from Google Fonts at build time.
 */
import type { APIRoute } from 'astro';
import { getCollection } from 'astro:content';
import satori from 'satori';
import { Resvg } from '@resvg/resvg-js';

const W = 1200;
const H = 630;

const COLORS = {
  bg: '#0b1020',
  bgGrad: '#0a1430',
  accent: '#22d3ee',
  fg: '#e2e8f0',
  muted: '#94a3b8',
  mono: '#67e8f9',
};

async function fetchFont(url: string): Promise<ArrayBuffer> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Font fetch failed (${res.status}): ${url}`);
  return res.arrayBuffer();
}

const fontPromise = (async () => {
  const css = await fetch(
    'https://fonts.googleapis.com/css2?family=Inter:wght@400;700;800&family=JetBrains+Mono:wght@500&display=swap',
    { headers: { 'User-Agent': 'Mozilla/5.0 (compatible; AstroOG)' } },
  ).then(r => r.text());
  const urls = [...css.matchAll(/url\((https:[^)]+\.(?:woff2|ttf|otf))\)/g)].map(m => m[1]);
  const [interReg, interBold, interBlack, monoMed] = await Promise.all([
    fetchFont(urls[0]!).catch(() => fetchFont(urls[1]!)),
    fetchFont(urls.find(u => /Inter[^/]*7\d\d/.test(u)) ?? urls[1]!),
    fetchFont(urls.find(u => /Inter[^/]*8\d\d/.test(u)) ?? urls[urls.length - 3]!),
    fetchFont(urls[urls.length - 1]!),
  ]);
  return [
    { name: 'Inter', data: interReg, weight: 400 as const, style: 'normal' as const },
    { name: 'Inter', data: interBold, weight: 700 as const, style: 'normal' as const },
    { name: 'Inter', data: interBlack, weight: 800 as const, style: 'normal' as const },
    { name: 'JetBrains Mono', data: monoMed, weight: 500 as const, style: 'normal' as const },
  ];
})();

interface Pageish {
  title: string;
  description: string;
  section: string;
}

function deriveSection(slug: string): string {
  const top = slug.split('/')[0] ?? '';
  const map: Record<string, string> = {
    'getting-started': 'Getting Started',
    concepts: 'Concepts',
    guides: 'Guides',
    operations: 'Operations',
    recipes: 'Recipes',
    reference: 'Reference',
    internals: 'Internals',
    compare: 'Compare',
    blog: 'Blog',
  };
  return map[top] ?? 'Docs';
}

function template({ title, description, section }: Pageish) {
  return {
    type: 'div',
    props: {
      style: {
        width: W,
        height: H,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        padding: '72px 80px',
        background: `linear-gradient(135deg, ${COLORS.bg} 0%, ${COLORS.bgGrad} 100%)`,
        fontFamily: 'Inter',
        color: COLORS.fg,
        position: 'relative',
      },
      children: [
        {
          type: 'div',
          props: {
            style: {
              position: 'absolute',
              top: 0,
              left: 0,
              width: W,
              height: 8,
              background: `linear-gradient(90deg, ${COLORS.accent}, #6366f1)`,
            },
          },
        },
        {
          type: 'div',
          props: {
            style: { display: 'flex', flexDirection: 'column', gap: 24 },
            children: [
              {
                type: 'div',
                props: {
                  style: {
                    fontFamily: 'JetBrains Mono',
                    fontSize: 22,
                    fontWeight: 500,
                    color: COLORS.accent,
                    letterSpacing: '0.04em',
                    textTransform: 'uppercase',
                  },
                  children: section,
                },
              },
              {
                type: 'div',
                props: {
                  style: {
                    fontSize: 68,
                    fontWeight: 800,
                    lineHeight: 1.08,
                    letterSpacing: '-0.02em',
                    color: COLORS.fg,
                    maxWidth: 1040,
                    display: 'flex',
                  },
                  children: title,
                },
              },
              {
                type: 'div',
                props: {
                  style: {
                    fontSize: 28,
                    fontWeight: 400,
                    lineHeight: 1.4,
                    color: COLORS.muted,
                    maxWidth: 1040,
                    display: 'flex',
                  },
                  children: description,
                },
              },
            ],
          },
        },
        {
          type: 'div',
          props: {
            style: {
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              fontFamily: 'JetBrains Mono',
              fontSize: 24,
              fontWeight: 500,
              color: COLORS.mono,
            },
            children: [
              { type: 'div', props: { children: 'prexor.cloud' } },
              { type: 'div', props: { style: { color: COLORS.muted }, children: 'Self-hosted Minecraft cloud orchestrator' } },
            ],
          },
        },
      ],
    },
  };
}

export async function getStaticPaths() {
  const entries = await getCollection('docs');
  const paths = entries.map(e => {
    const slug = e.id.replace(/\.(md|mdx)$/i, '');
    return {
      params: { slug },
      props: {
        title: (e.data as { title?: string }).title ?? 'PrexorCloud',
        description: (e.data as { description?: string }).description ?? '',
      },
    };
  });
  paths.push({
    params: { slug: 'default' },
    props: {
      title: 'PrexorCloud',
      description: 'Self-hosted, OSS Minecraft cloud orchestrator. Deploy, scale, and manage with zero-trust security.',
    },
  });
  return paths;
}

export const GET: APIRoute = async ({ params, props }) => {
  const fonts = await fontPromise;
  const slug = String(params.slug ?? 'default');
  const title = (props as { title?: string }).title ?? 'PrexorCloud';
  const description = (props as { description?: string }).description ?? '';
  const section = slug === 'default' ? 'PrexorCloud' : deriveSection(slug);

  // satori's React typings expect a specific element shape; the runtime is
  // happy with our plain JSON-ish tree. Cast through `unknown` to silence the
  // type-checker without pulling react/jsx-runtime into the build.
  const svg = await satori(
    template({ title, description, section }) as unknown as Parameters<typeof satori>[0],
    { width: W, height: H, fonts },
  );
  const png = new Resvg(svg, { background: COLORS.bg }).render().asPng();

  return new Response(new Uint8Array(png), {
    headers: {
      'Content-Type': 'image/png',
      'Cache-Control': 'public, max-age=31536000, immutable',
    },
  });
};
