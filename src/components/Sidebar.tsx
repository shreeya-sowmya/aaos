'use client'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import clsx from 'clsx'

const nav = [
  {
    title: 'Getting Started',
    items: [
      { label: 'Introduction', href: '/' },
      { label: 'Setup & Build', href: '/guides/setup' },
    ],
  },
  {
    title: 'Tutorials',
    items: [
      { label: 'Custom Vehicle HAL', href: '/tutorials/vehicle-hal' },
      { label: 'CarAppService Basics', href: '/tutorials/car-app-service' },
      { label: 'Audio Focus in AAOS', href: '/tutorials/audio-focus' },
    ],
  },
  {
    title: 'Reference',
    items: [
      { label: 'VHAL Properties', href: '/guides/vhal-properties' },
      { label: 'Permissions', href: '/guides/permissions' },
    ],
  },
]

export default function Sidebar() {
  const path = usePathname()
  return (
    <aside className="w-60 shrink-0 sticky top-14 self-start h-[calc(100vh-3.5rem)] overflow-y-auto border-r border-zinc-800 py-8 pr-4">
      {nav.map((section) => (
        <div key={section.title} className="mb-6">
          <p className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-2 px-3">
            {section.title}
          </p>
          <ul className="space-y-0.5">
            {section.items.map((item) => (
              <li key={item.href}>
                <Link
                  href={item.href}
                  className={clsx(
                    'block px-3 py-1.5 rounded-md text-sm transition-colors',
                    path === item.href
                      ? 'bg-zinc-800 text-zinc-100 font-medium'
                      : 'text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900'
                  )}
                >
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </aside>
  )
}
