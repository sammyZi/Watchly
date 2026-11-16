// Watch party relay. One Durable Object per room passes every message to everyone else in the room.
// It never reads them: the apps encrypt with a key that lives only in the invite link's #fragment,
// which browsers never send to a server. No accounts, nothing stored.
//
//   /room/<id>  WebSocket into a room
//   /j/<id>     the invite link: a page that hands the link on to the app

export default {
  async fetch(req, env) {
    const [, kind, id] = new URL(req.url).pathname.match(/^\/(room|j)\/([0-9a-f]{32})$/) || [];
    if (kind === 'j') return new Response(join(new URL(req.url)), { headers: { 'content-type': 'text/html; charset=utf-8' } });
    if (kind !== 'room') return new Response('Watchly watch party', { status: 404 });
    if (req.headers.get('Upgrade') !== 'websocket') return new Response('WebSocket only', { status: 426 });
    return env.ROOMS.get(env.ROOMS.idFromName(id)).fetch(req);
  },
};

export class Room {
  constructor(ctx) {
    this.ctx = ctx;
  }

  fetch() {
    if (this.ctx.getWebSockets().length >= 20) return new Response('Room full', { status: 429 });
    const [client, server] = Object.values(new WebSocketPair());
    // Hibernation: an idle room costs nothing while its sockets stay open.
    this.ctx.acceptWebSocket(server);
    this.count();
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, msg) {
    if (typeof msg !== 'string' || msg.length > 16384) return;
    // A clock ping: answered to the sender alone with this server's time, so every phone in the room
    // agrees on one clock and a guest can tell how long ago the leader's position was true.
    if (msg.startsWith('{')) {
      try {
        const { t, c } = JSON.parse(msg);
        if (t === 'time') send(ws, JSON.stringify({ t: 'time', c, s: Date.now() }));
      } catch {}
      return;
    }
    for (const other of this.ctx.getWebSockets()) if (other !== ws) send(other, msg);
  }

  webSocketClose(ws) {
    try { ws.close(); } catch {}
    this.count(ws);
  }

  webSocketError(ws) {
    this.count(ws);
  }

  /** How many are in the room, to all of them: the only message the relay writes itself. */
  count(gone) {
    const all = this.ctx.getWebSockets().filter((w) => w !== gone);
    const n = JSON.stringify({ t: 'count', n: all.length });
    for (const w of all) send(w, n);
  }
}

function send(ws, msg) {
  try { ws.send(msg); } catch {}
}

// The key is after the #: it reaches the app, never this server.
// Watchly's launcher icon (144px), the page's tab icon: inline, so the page needs nothing else from here.
const ICON = 'data:image/webp;base64,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';

const esc = (s) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

// The invite page. A leader's link carries the film's imdb id and name (?i=tt…&t=…), so a chat app that
// previews the link — it fetches the page and reads og: tags, never the #key — shows the film's art. Both
// are checked and escaped: the query is anyone's to write.
function join(url) {
  const i = url.searchParams.get('i') || '';
  const imdb = /^tt[0-9]{1,10}$/.test(i) ? i : '';
  const name = (url.searchParams.get('t') || '').slice(0, 80);
  const title = name ? `Watch ${name} together` : "You're invited to a watch party";
  const art = imdb ? `https://images.metahub.space/background/medium/${imdb}/img` : '';
  const tags = `<meta property=og:type content=website><meta property=og:site_name content=Watchly>`
    + `<meta property=og:title content="${esc(title)}"><meta property=og:description content="Join the watch party in Watchly — same film, in step, with chat.">`
    + (art ? `<meta property=og:image content="${art}"><meta name=twitter:card content=summary_large_image>` : '');
  return `<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><link rel=icon type=image/webp href="${ICON}"><link rel=apple-touch-icon href="${ICON}">${tags}
<style>
html,body{height:100%}
body{margin:0;font:16px system-ui,sans-serif;color:#fff;text-align:center;background:#111 ${art ? `url(${art}) center top/cover no-repeat` : ''};display:flex;flex-direction:column;justify-content:${art ? 'flex-end' : 'center'}}
.on{padding:${art ? '120px' : '0'} 20px 28px;${art ? 'background:linear-gradient(to bottom,transparent,rgba(0,0,0,.55) 35%,rgba(0,0,0,.92))' : ''}}
h2{margin:0 0 6px;font-size:clamp(22px,4.5vw,34px);text-shadow:0 2px 12px rgba(0,0,0,.7)}
p{margin:6px 0;text-shadow:0 1px 8px rgba(0,0,0,.7)}
a.b{display:inline-block;margin-top:14px;padding:12px 26px;background:#b81d24;color:#fff;border-radius:8px;text-decoration:none;font-weight:600}
.s{opacity:.75;font-size:13px}.s a{color:#fff}
</style>
<div class=on><h2>${esc(title)}</h2><p>Open it in Watchly.</p><a class=b id=go>Join in Watchly</a>
<p class=s>No Watchly yet? <a href="https://github.com/sammyZi/Watchly/releases/latest">Get it here</a>, then tap this link again.</p></div>
<script>const id=location.pathname.split('/').pop(),k=location.hash.slice(1);const u='watchly://party/'+id+'?k='+k;go.href=u;location.href=u;</script>`;
}
