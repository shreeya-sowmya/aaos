# AAOS Docs

Android Automotive OS developer documentation — built with Next.js 14, MDX, and rehype-pretty-code.

## Features

- ✍️ **MDX** — write in Markdown, drop React components inline
- 🎨 **Syntax highlighting** — C++, Java, Kotlin via rehype-pretty-code (Shiki)
- 🔗 **GitHub repo cards** — live star/fork counts via GitHub API
- 🌙 **Dark theme** — AAOS-appropriate dark-first design
- 🚀 **Dual deploy** — Vercel (production) + GitHub Pages (static export)

## Getting started

```bash
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000).

## Deploying

### Vercel
Push to `main` — Vercel auto-deploys via the project integration.

### GitHub Pages
The `.github/workflows/deploy.yml` workflow triggers on push to `main`
and exports a static build to GitHub Pages automatically.

Set `basePath` in `next.config.mjs` to match your repo name if needed.
