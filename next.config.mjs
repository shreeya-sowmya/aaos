/** @type {import('next').NextConfig} */
const nextConfig = {
  pageExtensions: ['js', 'jsx', 'ts', 'tsx'],
  output: process.env.EXPORT_MODE === 'true' ? 'export' : undefined,
  basePath: process.env.GITHUB_PAGES === 'true' ? '/aaos' : '',
  images: {
    unoptimized: true,
  },
}

export default nextConfig
