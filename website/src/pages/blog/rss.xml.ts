/*
 * /blog/rss.xml — RSS feed of the PrexorCloud engineering blog.
 *
 * Reads every Starlight docs entry under `blog/`, filters out the index
 * itself, sorts newest-first by frontmatter `date`, and emits an RFC-822
 * feed pointed at the same URLs the user reads. The blog frontmatter
 * format is documented in docs/public/en/blog/index.md.
 */
import rss from '@astrojs/rss';
import { getCollection } from 'astro:content';
import type { APIContext } from 'astro';

interface BlogData {
  title?: string;
  description?: string;
  date?: string | Date;
}

export async function GET(context: APIContext) {
  const all = await getCollection('docs');
  const posts = all
    .filter((e) => e.id.startsWith('blog/') && !/^blog\/(index)\b/.test(e.id))
    .map((e) => {
      const data = e.data as BlogData;
      const slug = e.id.replace(/\.(md|mdx)$/i, '');
      return {
        title: data.title ?? slug,
        description: data.description ?? '',
        link: `/${slug}/`,
        pubDate: data.date ? new Date(data.date) : new Date(0),
      };
    })
    .sort((a, b) => b.pubDate.getTime() - a.pubDate.getTime());

  return rss({
    title: 'PrexorCloud Blog',
    description:
      'Engineering posts, release notes, and architecture deep-dives from the PrexorCloud team.',
    site: context.site ?? 'https://prexor.cloud',
    items: posts,
    customData: '<language>en</language>',
  });
}
