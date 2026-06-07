import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';
import { docsSchema } from '@astrojs/starlight/schema';

// Extra blog frontmatter fields — Starlight's docsSchema doesn't know about
// `date`, `authors`, `tags`, so they get dropped before the RSS endpoint
// (src/pages/blog/rss.xml.ts) can read them. Extending the schema keeps
// them on `entry.data` while still validating the rest with Starlight.
const blogExtras = z.object({
  date: z.coerce.date().optional(),
  authors: z.array(z.object({ name: z.string(), url: z.string().url().optional() })).optional(),
  tags: z.array(z.string()).optional(),
});

// Source the Starlight `docs` collection from `docs/public/en/` at the repo
// root rather than the default `src/content/docs/`. Lets website code +
// content ship in one PR with no symlinks (works cleanly on Windows + CI).
//
// `base` is resolved relative to the project root (the directory containing
// `package.json`). From `website/`, `../docs/public/en` resolves to the
// monorepo's `docs/public/en/`.
export const collections = {
  docs: defineCollection({
    loader: glob({
      // The `_*` prefix is reserved for auto-generated reference content
      // (tools/gen-cli-docs.ts, tools/gen-grpc-docs.sh) — those files are
      // drift-detection artifacts, not user-facing pages, so we exclude
      // them from the docs collection. The hand-curated wrapping pages
      // can link to them via raw repo URLs when useful.
      pattern: ['**/*.{md,mdx,markdown,mkdn,mkd,mdwn}', '!**/_*/**'],
      base: '../docs/public/en',
    }),
    schema: docsSchema({ extend: blogExtras }),
  }),
};
