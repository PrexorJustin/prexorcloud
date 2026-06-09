FROM node:22-alpine AS builder

# Pin pnpm to 9.x — matches `lockfileVersion: 9.0` in pnpm-lock.yaml and
# avoids pnpm 10's default behaviour of refusing to run dependency build
# scripts (esbuild, parcel/watcher, etc.) without an explicit allowlist.
RUN corepack enable && corepack prepare pnpm@9.15.4 --activate

WORKDIR /build
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
# Skip the `sdk:check` step (part of `pnpm build`) inside Docker — it shells
# out to `redocly lint ../docs/openapi.json`, but `../docs/` isn't part of
# the dashboard-only build context. CI on the repo root runs the full
# `pnpm build` so the schema is still validated on every PR.
RUN pnpm exec nuxt build

FROM nginx:alpine

COPY --from=builder /build/.output/public /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 3000

CMD ["nginx", "-g", "daemon off;"]
