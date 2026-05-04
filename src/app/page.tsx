import Link from 'next/link'
import GitHubRepoCard from '@/components/GitHubRepoCard'

const features = [
  { icon: '📝', title: 'MDX Tutorials', desc: 'Write in Markdown, embed React components inline.' },
  { icon: '🎨', title: 'Interactive Demos', desc: 'Live widgets — like the cache animation — embedded directly in docs.' },
  { icon: '🔗', title: 'GitHub Repo Cards', desc: 'Live repo stats pulled from the GitHub API.' },
  { icon: '🚗', title: 'AAOS-first', desc: 'C++, Java, and Kotlin syntax highlighting via rehype-pretty-code.' },
]

export default function HomePage() {
  return (
    <div className="max-w-3xl">
      <div className="mb-2 text-sm font-mono text-blue-400 tracking-wide">ANDROID AUTOMOTIVE OS</div>
      <h1 className="text-5xl font-bold mb-4 text-zinc-50 leading-tight">
        Developer Docs<br />& Tutorials
      </h1>
      <p className="text-zinc-400 text-lg mb-10 leading-relaxed">
        Deep-dive guides for building on AAOS — from Vehicle HAL properties to CarAppService,
        with interactive demos and live code examples.
      </p>

      <div className="grid grid-cols-2 gap-4 mb-12">
        {features.map((f) => (
          <div key={f.title} className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-5 hover:border-zinc-600 transition-colors">
            <div className="text-2xl mb-2">{f.icon}</div>
            <div className="font-medium text-zinc-100 mb-1">{f.title}</div>
            <div className="text-sm text-zinc-400 leading-relaxed">{f.desc}</div>
          </div>
        ))}
      </div>

      <h2 className="text-lg font-semibold text-zinc-200 mb-4">Start here</h2>
      <div className="space-y-3 mb-12">
        <Link href="/tutorials/vehicle-hal" className="flex items-center justify-between rounded-lg border border-zinc-800 px-5 py-3.5 hover:border-zinc-600 hover:bg-zinc-900/50 transition-all group">
          <div>
            <div className="font-medium text-zinc-100">Custom Vehicle HAL</div>
            <div className="text-sm text-zinc-500">Implement VHAL properties in C++</div>
          </div>
          <span className="text-zinc-600 group-hover:text-zinc-300 transition-colors">→</span>
        </Link>
        <Link href="/tutorials/car-app-service" className="flex items-center justify-between rounded-lg border border-zinc-800 px-5 py-3.5 hover:border-zinc-600 hover:bg-zinc-900/50 transition-all group">
          <div>
            <div className="font-medium text-zinc-100">CarAppService Basics</div>
            <div className="text-sm text-zinc-500">Build your first in-vehicle app with Kotlin</div>
          </div>
          <span className="text-zinc-600 group-hover:text-zinc-300 transition-colors">→</span>
        </Link>
      </div>

      <h2 className="text-lg font-semibold text-zinc-200 mb-4">Related repos</h2>
      <div className="grid grid-cols-1 gap-3">
        <GitHubRepoCard owner="android" repo="platform_packages_services_Car" />
      </div>
    </div>
  )
}
