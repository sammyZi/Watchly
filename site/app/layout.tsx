import type { Metadata, Viewport } from 'next';
import { Noto_Sans } from 'next/font/google';
import { Analytics } from '@vercel/analytics/next';
import './globals.css';
import { ThemeProvider, Nav, OfflineStrip } from '../components/Chrome';

const noto = Noto_Sans({
  subsets: ['latin'],
  weight: ['400', '500', '600', '700'],
});

export const metadata: Metadata = {
  title: 'Watchly',
  description:
    'Movies and series streamed from torrents while they download — in the browser.',
};

/** Edge to edge on notched phones — the CSS pads with env(safe-area-inset-*). */
export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  themeColor: [
    { media: '(prefers-color-scheme: dark)', color: '#000000' },
    { media: '(prefers-color-scheme: light)', color: '#ffffff' },
  ],
};

export default function RootLayout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" className={noto.className} suppressHydrationWarning>
      <body style={{ backgroundColor: 'var(--app-bg)' }}>
        <ThemeProvider>
          <Nav />
          <OfflineStrip />
          {children}
        </ThemeProvider>
        <Analytics />
      </body>
    </html>
  );
}