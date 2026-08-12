import type { Metadata } from 'next';
import { Noto_Sans } from 'next/font/google';
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

export default function RootLayout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" className={noto.className} suppressHydrationWarning>
      <body style={{ backgroundColor: 'var(--app-bg)' }}>
        <ThemeProvider>
          <Nav />
          <OfflineStrip />
          {children}
        </ThemeProvider>
      </body>
    </html>
  );
}