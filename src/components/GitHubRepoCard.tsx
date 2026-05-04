interface Repo {
  full_name: string
  description: string | null
  stargazers_count: number
  forks_count: number
  html_url: string
  language: string | null
}

async function getRepo(owner: string, repo: string): Promise<Repo | null> {
  try {
    const res = await fetch(`https://api.github.com/repos/${owner}/${repo}`, {
      next: { revalidate: 3600 },
      headers: { Accept: 'application/vnd.github.v3+json' },
    })
    if (!res.ok) return null
    return res.json()
  } catch {
    return null
  }
}

export default async function GitHubRepoCard({ owner, repo }: { owner: string; repo: string }) {
  const data = await getRepo(owner, repo)
  const name = data?.full_name ?? `${owner}/${repo}`
  const desc = data?.description ?? 'No description available.'
  const stars = data?.stargazers_count?.toLocaleString() ?? '—'
  const forks = data?.forks_count?.toLocaleString() ?? '—'
  const url = data?.html_url ?? `https://github.com/${owner}/${repo}`
  const lang = data?.language ?? null

  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="flex flex-col gap-2 rounded-xl border border-zinc-800 bg-zinc-900/40 p-4 hover:border-zinc-600 transition-colors"
    >
      <div className="flex items-start justify-between gap-2">
        <span className="font-mono text-sm text-blue-400 truncate">{name}</span>
        {lang && (
          <span className="shrink-0 text-xs bg-zinc-800 text-zinc-400 px-2 py-0.5 rounded-full">{lang}</span>
        )}
      </div>
      <p className="text-sm text-zinc-400 leading-relaxed">{desc}</p>
      <div className="flex gap-4 text-xs text-zinc-500 mt-1">
        <span>★ {stars}</span>
        <span>⑂ {forks}</span>
      </div>
    </a>
  )
}
