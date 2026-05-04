import Link from 'next/link'

export default function TopBar() {
  return (
    <header className="sticky top-0 z-40 border-b border-zinc-800 bg-zinc-950/90 backdrop-blur-sm">
      <div className="max-w-7xl mx-auto px-6 h-14 flex items-center justify-between">
        <Link href="/" className="flex items-center gap-2.5 font-semibold text-zinc-100 hover:text-white">
          <span className="w-7 h-7 rounded-md bg-blue-600 flex items-center justify-center text-xs font-bold">A</span>
          AAOS Docs
        </Link>
        <nav className="flex items-center gap-6 text-sm text-zinc-400">
          <Link href="/tutorials" className="hover:text-zinc-100 transition-colors">Tutorials</Link>
          <Link href="/guides" className="hover:text-zinc-100 transition-colors">Guides</Link>
          <a
            href="https://github.com/your-org/aaos-docs"
            target="_blank"
            rel="noopener noreferrer"
            className="hover:text-zinc-100 transition-colors"
          >
            GitHub ↗
          </a>
        </nav>
      </div>
    </header>
  )
}
