'use client';
/** Poster, shelf, hero and thumb — the building blocks every screen shares. */
import Link from 'next/link';
import type { ReactNode } from 'react';
import type { Title } from '../lib/meta';
import { hueOf } from '../lib/title';
import { bytes } from '../lib/format';
import type { Hit } from '../lib/search';

export const titleHref = (t: Pick<Title, 'imdb' | 'kind'>) =>
  `/title/${t.imdb}?kind=${t.kind}`;

export function Thumb({
  name,
  size,
  poster,
  radius = 12,
}: {
  name: string;
  size: number;
  poster?: string | null;
  radius?: number;
}) {
  const hue = hueOf(name);
  const style: React.CSSProperties = {
    background: `hsl(${hue} 45% 22%)`,
    borderRadius: radius,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    flex: 'none',
    overflow: 'hidden',
    width: size,
    height: size * 1.5,
  };
  return (
    <div style={style}>
      {poster ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={poster} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
      ) : (
        <span style={{ fontSize: size * 0.4, color: 'hsl(0 0% 100% / 0.55)' }}>{name[0]}</span>
      )}
    </div>
  );
}

export function Poster({
  title,
  width = 168,
  href,
}: {
  title: Title;
  width?: number;
  href?: string;
}) {
  return (
    <Link href={href ?? titleHref(title)} className="poster" style={{ width }}>
      <div className="poster-art">
        {title.poster ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={title.poster} alt={title.title} loading="lazy" />
        ) : (
          <Thumb name={title.title} size={width} poster={title.poster} />
        )}
        {title.rating && <span className="poster-score">★ {title.rating}</span>}
      </div>
      <div className="poster-cap">{title.title}</div>
      <div className="poster-sub">
        {[title.year?.slice(0, 4), title.kind === 'tv' ? 'Series' : null]
          .filter(Boolean)
          .join(' · ')}
      </div>
    </Link>
  );
}

export function Shelf({ title, href, children }: { title: string; href?: string; children: ReactNode }) {
  return (
    <section className="shelf">
      <div className="shelf-head">
        <h2 className="shelf-title">{title}</h2>
        {href && (
          <Link href={href} className="shelf-more">
            See all →
          </Link>
        )}
      </div>
      <div className="shelf-row">{children}</div>
    </section>
  );
}

export function Hero({
  title,
  actionHref,
  actionLabel = 'Play',
  facts,
  page,
}: {
  title: Title;
  actionHref?: string;
  actionLabel?: string;
  facts?: ReactNode;
  /** Paging dots, when the hero is one of several. */
  page?: { count: number; index: number; onPick: (i: number) => void };
}) {
  return (
    <div className="hero" style={{ background: 'var(--surface)' }}>
      {title.backdrop ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={title.backdrop} alt="" />
      ) : title.poster ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={title.poster} alt="" style={{ objectPosition: 'top' }} />
      ) : null}
      <div className="hero-body">
        <h1 className="hero-title">{title.title}</h1>
        <div className="hero-facts">
          {title.year && <span>{title.year.slice(0, 4)}</span>}
          {title.rating && <span className="warn-text">★ {title.rating}</span>}
          {title.genres.slice(0, 2).map((g) => (
            <span key={g} className="tag">
              {g}
            </span>
          ))}
        </div>
        {facts}
        <div className="actions" style={{ margin: 0, padding: 0 }}>
          {actionHref && (
            <Link href={actionHref} className="btn btn-primary">
              ▶ {actionLabel}
            </Link>
          )}
        </div>
      </div>
      {page && page.count > 1 && (
        <div className="hero-dots">
          {Array.from({ length: page.count }, (_, i) => (
            <button
              key={i}
              className={i === page.index ? 'on' : ''}
              aria-label={`Recommendation ${i + 1}`}
              onClick={() => page.onPick(i)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/** One release row, with swarm health and quality. */
export function ReleaseRow({
  hit,
  onClick,
  right,
}: {
  hit: Hit;
  onClick?: () => void;
  right?: ReactNode;
}) {
  return (
    <div className="row" onClick={onClick} style={onClick ? { cursor: 'pointer' } : undefined}>
      <div className="row-body">
        <div className="row-title">{hit.name}</div>
        <div className="row-sub">
          <span className={`health ${hit.seeders >= 100 ? 'fast' : hit.seeders >= 20 ? 'good' : hit.seeders >= 1 ? 'weak' : 'dead'}`} />
          {hit.seeders} seeds · {bytes(hit.size)}
          {hit.quality ? ` · ${hit.quality}` : ''}
        </div>
      </div>
      {right}
    </div>
  );
}
/**
 * Save/like glyphs.
 *
 * Drawn rather than typed: the text characters these replace were a diamond
 * (◇) standing in for a bookmark and an outline heart from a different
 * family, so the pair never matched each other or the rest of the chrome — and
 * an emoji bookmark rendered at whatever size the platform felt like.
 * `currentColor` lets the button own the state.
 */
export function BookmarkIcon({ filled }: { filled?: boolean }) {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" aria-hidden>
      <path
        d="M6 3.5h12a1.5 1.5 0 0 1 1.5 1.5v15.2a.8.8 0 0 1-1.23.68L12 16.9l-6.27 3.98A.8.8 0 0 1 4.5 20.2V5A1.5 1.5 0 0 1 6 3.5Z"
        fill={filled ? 'currentColor' : 'none'}
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function HeartIcon({ filled }: { filled?: boolean }) {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" aria-hidden>
      <path
        d="M12 20.3s-7.5-4.6-7.5-9.8a4.4 4.4 0 0 1 7.5-3.1 4.4 4.4 0 0 1 7.5 3.1c0 5.2-7.5 9.8-7.5 9.8Z"
        fill={filled ? 'currentColor' : 'none'}
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** Back. Text arrows differ wildly by font — this one always looks the same. */
export function BackIcon() {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" aria-hidden>
      <path
        d="M15 5.5 8 12l7 6.5"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** Player glyphs. Same reason as the others: text characters and emoji render
 *  at whatever size and weight the platform decides. */
const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
};

export function PlayIcon({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden>
      <path d="M7 4.8 19 12 7 19.2Z" fill="currentColor" />
    </svg>
  );
}

export function PauseIcon({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden>
      <path d="M8 5h3v14H8zM13 5h3v14h-3z" fill="currentColor" />
    </svg>
  );
}

/** Back/forward by ten, drawn as a circular arrow with the number inside. */
export function SkipIcon({ back }: { back?: boolean }) {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden>
      <g transform={back ? 'scale(-1,1) translate(-24,0)' : undefined}>
        <path d="M12 5.5a7 7 0 1 0 6.7 5" {...stroke} />
        <path d="M18.6 2.9v3.8h-3.8" {...stroke} />
      </g>
      <text
        x="12"
        y="15.4"
        textAnchor="middle"
        fontSize="7.5"
        fontWeight="700"
        fill="currentColor"
      >
        10
      </text>
    </svg>
  );
}

export function CcIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden>
      <rect x="2.6" y="5" width="18.8" height="14" rx="3" {...stroke} />
      <path d="M10 10.2a2.4 2.4 0 1 0 0 3.6M17 10.2a2.4 2.4 0 1 0 0 3.6" {...stroke} />
    </svg>
  );
}

export function FullscreenIcon({ on }: { on?: boolean }) {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" aria-hidden>
      {on ? (
        <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" {...stroke} />
      ) : (
        <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" {...stroke} />
      )}
    </svg>
  );
}

export function DownloadIcon() {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" aria-hidden>
      <path d="M12 3.5v11M7.5 10.5 12 15l4.5-4.5M4.5 19.5h15" {...stroke} />
    </svg>
  );
}

export function NextIcon() {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" aria-hidden>
      <path d="M5.5 5.5 14 12l-8.5 6.5Z" fill="currentColor" />
      <path d="M18 5v14" {...stroke} />
    </svg>
  );
}

export function LockIcon({ on }: { on?: boolean }) {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" aria-hidden>
      <rect x="4.8" y="10.5" width="14.4" height="9.2" rx="2.4" {...stroke} />
      <path d={on ? 'M8.4 10.5V7.8a3.6 3.6 0 0 1 7.2 0v2.7' : 'M8.4 10.5V7.8a3.6 3.6 0 0 1 6.9-1.3'} {...stroke} />
    </svg>
  );
}
