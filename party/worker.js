// Watch party relay. One Durable Object per room passes every message to everyone else in the room.
// It never reads them: the apps encrypt with a key that lives only in the invite link's #fragment,
// which browsers never send to a server. No accounts, nothing stored.
//
//   /room/<id>  WebSocket into a room
//   /j/<id>     the invite link: a page that hands the link on to the app

export default {
  async fetch(req, env) {
    const [, kind, id] = new URL(req.url).pathname.match(/^\/(room|j)\/([0-9a-f]{32})$/) || [];
    if (kind === 'j') return new Response(JOIN, { headers: { 'content-type': 'text/html; charset=utf-8' } });
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
const JOIN = `<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Watch party</title>
<style>body{font:16px system-ui,sans-serif;background:#111;color:#eee;display:grid;place-items:center;min-height:90vh;margin:0 16px;text-align:center}a{display:inline-block;margin-top:16px;padding:12px 22px;background:#b81d24;color:#fff;border-radius:8px;text-decoration:none;font-weight:600}</style>
<div><h2>You're invited to a watch party</h2><p>Open it in Watchly.</p><a id=go>Join in Watchly</a>
<p style="opacity:.6;font-size:13px">No Watchly yet? <a href="https://github.com/sammyZi/Watchly/releases/latest" style="all:unset;text-decoration:underline">Get it here</a>, then tap this link again.</p></div>
<script>const id=location.pathname.split('/').pop(),k=location.hash.slice(1);const u='watchly://party/'+id+'?k='+k;go.href=u;location.href=u;</script>`;
