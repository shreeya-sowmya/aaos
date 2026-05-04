import type { Metadata } from 'next'
import './globals.css'
import Sidebar from '@/components/Sidebar'
import TopBar from '@/components/TopBar'

export const metadata: Metadata = {
  title: 'AAOS Docs',
  description: 'Android Automotive OS — developer tutorials, interactive demos, and reference guides.',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className="dark">
      <body className="bg-zinc-950 text-zinc-100 min-h-screen flex flex-col">
        <TopBar />
        <div className="flex flex-1 max-w-7xl mx-auto w-full">
          <Sidebar />
          <main className="flex-1 min-w-0 px-8 py-10">{children}</main>
        </div>
      </body>
    </html>
  )
}
