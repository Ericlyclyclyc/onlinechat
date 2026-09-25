/* Online Chat — shared WebSocket worker.
 *
 * Keeps ONE cookie-authenticated WebSocket per origin inside a SharedWorker, so switching
 * between chat.html / account.html / login.html never drops or re-creates the socket:
 * no "disconnected → reconnecting" churn in the UI, no connect/disconnect system messages
 * on the server. Pages talk to this worker over MessagePorts; where SharedWorker is
 * unavailable the client falls back to a per-page WebSocket (see common.js OC.Ws).
 *
 * Page → worker messages:
 *   { type: 'send',   payload: {...} }   forward a WebSocket frame
 *   { type: 'resume' }                   a page just logged in: clear the stopped flag and reconnect
 *   { type: 'stop' }                     logout: close the socket and stop reconnecting
 *
 * Worker → page messages: the usual server frames plus
 *   { type: 'ws_status', state: 'connecting'|'ok'|'err'|'closed' }
 * and cached copies of the last auth_ok / history (marked cached: true) for late-attaching pages.
 */
'use strict';

const ports = new Set();
let ws = null;
let reconnectDelay = 1000;
let reconnectTimer = null;
let stopped = false;            // force_logout / server_shutdown / auth_error: stop reconnecting
let authed = null;              // last auth_ok payload
let snapshot = [];              // rolling recent messages for late-attaching pages
let snapshotHasMore = false;
const SNAPSHOT_CAP = 200;

function broadcast(msg) {
    for (const port of ports) {
        try { port.postMessage(msg); } catch (_) { /* port closed */ }
    }
}

function sendFrame(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        try { ws.send(JSON.stringify(obj)); return true; } catch (_) { /* fall through */ }
    }
    return false;
}

function connect() {
    if (stopped) return;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    broadcast({ type: 'ws_status', state: 'connecting' });
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    try {
        ws = new WebSocket(proto + '//' + location.host + '/ws');
    } catch (_) {
        scheduleReconnect();
        return;
    }
    ws.addEventListener('open', () => {
        reconnectDelay = 1000;
    });
    ws.addEventListener('message', (ev) => {
        let msg;
        try { msg = JSON.parse(ev.data); } catch (_) { return; }
        onFrame(msg);
    });
    ws.addEventListener('close', () => {
        broadcast({ type: 'ws_status', state: stopped ? 'closed' : 'err' });
        if (!stopped) scheduleReconnect();
    });
    ws.addEventListener('error', () => { /* a close event follows */ });
}

function scheduleReconnect() {
    if (stopped || reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connect();
    }, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 15000);
}

function onFrame(msg) {
    switch (msg.type) {
        case 'ready':
            break; // server hello; cookie auto-auth follows immediately
        case 'auth_ok':
            reconnectDelay = 1000;
            authed = msg;
            broadcast({ type: 'ws_status', state: 'ok' });
            broadcast(msg); // { type:'auth_ok', username, bound, mcName?, mcUuid? }
            break;
        case 'auth_error':
            // Invalid/expired cookie: stop hammering the server until a page logs in again (resume).
            authed = null;
            stopped = true;
            broadcast({ type: 'ws_status', state: 'err' });
            broadcast(msg);
            break;
        case 'history':
            snapshot = msg.messages || [];
            snapshotHasMore = !!msg.hasMore;
            broadcast({ type: 'history', messages: snapshot, hasMore: snapshotHasMore, fresh: true });
            break;
        case 'chat':
        case 'web':
        case 'system':
            if (snapshot.length >= SNAPSHOT_CAP) snapshot.shift();
            snapshot.push(msg);
            broadcast(msg);
            break;
        case 'pong':
            broadcast(msg);
            break;
        case 'force_logout':
            stopped = true;
            authed = null;
            broadcast(msg);
            try { ws.close(); } catch (_) {}
            break;
        case 'server_shutdown':
            stopped = true;
            broadcast({ type: 'ws_status', state: 'closed' });
            broadcast(msg);
            try { ws.close(); } catch (_) {}
            break;
        case 'bind_pending':
        case 'bind_ok':
        case 'bind_denied':
        case 'bind_expired':
        case 'bind_error':
        case 'unbind_ok':
        case 'error':
            broadcast(msg);
            break;
    }
}

self.addEventListener('connect', (e) => {
    const port = e.ports[0];
    ports.add(port);

    // Bring the new page up to speed immediately with the cached state.
    if (authed) {
        port.postMessage(Object.assign({ cached: true }, authed));
        port.postMessage({ type: 'history', messages: snapshot, hasMore: snapshotHasMore, cached: true });
        port.postMessage({ type: 'ws_status', state: 'ok' });
    } else {
        const state = (ws && !stopped && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING))
            ? 'connecting' : 'err';
        port.postMessage({ type: 'ws_status', state });
    }

    port.addEventListener('message', (ev) => {
        const d = ev.data || {};
        if (d.type === 'send' && d.payload) {
            if (!sendFrame(d.payload)) {
                port.postMessage({ type: 'error', error: 'not connected' });
            }
        } else if (d.type === 'resume') {
            // A page just logged in (fresh cookie): allow reconnecting again.
            stopped = false;
            reconnectDelay = 1000;
            if (!ws || ws.readyState === WebSocket.CLOSED) {
                connect();
            } else {
                port.postMessage({ type: 'ws_status', state: 'connecting' });
            }
        } else if (d.type === 'stop') {
            stopped = true;
            if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
            if (ws) { try { ws.close(); } catch (_) {} }
        }
    });
    port.start();

    if (!ws && !stopped) connect();
});

// Keepalive so proxies / the 120 s server read-idle timeout never drop the socket.
setInterval(() => sendFrame({ type: 'ping' }), 50000);
