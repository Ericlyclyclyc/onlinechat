/* Online Chat — shared WebSocket worker (rebuilt).
 *
 * Keeps exactly ONE cookie-authenticated WebSocket per origin inside a SharedWorker, so
 * switching between chat.html / account.html / login.html never drops or re-creates the
 * socket: no "disconnected → reconnecting" churn in the UI, no connect/disconnect system
 * messages on the server. Pages talk to this worker over MessagePorts; where SharedWorker
 * is unavailable the client falls back to a per-page WebSocket (see common.js OC.Ws).
 *
 * Page → worker:
 *   { type: 'send',   ref, payload }   forward a WebSocket frame; the worker answers
 *                                      synchronously with { type:'send_ack', ref, ok }
 *   { type: 'resume' }                 a page just logged in (fresh cookie): reconnect
 *   { type: 'stop' }                   logout / force-logout: close and stop reconnecting
 *
 * Worker → page: every server frame, plus
 *   { type: 'ws_status', state: 'connecting'|'ok'|'err'|'closed' }
 *   { type: 'send_ack', ref, ok }
 * and cached copies of the last auth_ok / history (marked cached: true) so a page that
 * attaches after authentication renders instantly instead of waiting for a reconnect.
 *
 * The worker never renders or edits messages: it only forwards. Senders see their own
 * chat because the SERVER echoes the archived frame back to every session (the web UI
 * has no optimistic local echo any more).
 */
'use strict';

const ports = new Set();
const SNAPSHOT_CAP = 200;      // rolling recent messages for late-attaching pages
const PING_MS = 50000;         // keepalive; the server's read-idle timeout is 120 s

let ws = null;
let reconnectTimer = null;
let reconnectDelay = 1000;
let stopped = false;           // true after stop / auth_error / force_logout / server_shutdown
let authed = null;             // last auth_ok payload
let snapshot = [];             // rolling recent messages
let snapshotHasMore = false;

function safe(fn) {
    try { fn(); } catch (_) { /* never let an exception escape into the worker scope */ }
}

function broadcast(msg) {
    for (const port of ports) {
        try { port.postMessage(msg); } catch (_) { ports.delete(port); }
    }
}

function wsUrl() {
    return (location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + location.host + '/ws';
}

function sendFrame(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        try { ws.send(JSON.stringify(obj)); return true; } catch (_) { /* fall through */ }
    }
    return false;
}

function setStatus(state) {
    broadcast({ type: 'ws_status', state });
}

function open() {
    if (stopped) return;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    setStatus('connecting');
    try {
        ws = new WebSocket(wsUrl());
    } catch (_) {
        scheduleReconnect();
        return;
    }
    ws.addEventListener('open', () => safe(() => { reconnectDelay = 1000; }));
    ws.addEventListener('message', (ev) => safe(() => {
        let msg;
        try { msg = JSON.parse(ev.data); } catch (_) { return; }
        handle(msg);
    }));
    ws.addEventListener('close', () => safe(() => {
        ws = null;
        setStatus(stopped ? 'closed' : 'err');
        if (!stopped) scheduleReconnect();
    }));
    ws.addEventListener('error', () => { /* a close event follows */ });
}

function scheduleReconnect() {
    if (stopped || reconnectTimer) return;
    reconnectTimer = setTimeout(() => { reconnectTimer = null; open(); }, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 15000);
}

function stopSocket() {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    if (ws) {
        try { ws.close(); } catch (_) {}
        ws = null;
    }
}

function handle(msg) {
    switch (msg.type) {
        case 'ready':
            break; // server hello; cookie auto-auth follows immediately
        case 'auth_ok':
            reconnectDelay = 1000;
            authed = msg;
            setStatus('ok');
            broadcast(msg); // { type:'auth_ok', username, bound, mcName?, mcUuid? }
            break;
        case 'auth_error':
            // Invalid/expired cookie: stop hammering the server until a page logs in again (resume).
            authed = null;
            stopped = true;
            stopSocket();
            setStatus('err');
            broadcast(msg);
            break;
        case 'history':
            snapshot = Array.isArray(msg.messages) ? msg.messages : [];
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
        case 'force_logout':
            authed = null;
            stopped = true;
            stopSocket();
            setStatus('err');
            broadcast(msg);
            break;
        case 'server_shutdown':
            stopped = true;
            stopSocket();
            setStatus('closed');
            broadcast(msg);
            break;
        case 'pong':
        case 'bind_pending':
        case 'bind_ok':
        case 'bind_denied':
        case 'bind_expired':
        case 'bind_error':
        case 'unbind_ok':
        case 'error':
            broadcast(msg);
            break;
        default:
            break; // unknown server frames are ignored
    }
}

self.addEventListener('connect', (e) => safe(() => {
    const port = e.ports[0];
    ports.add(port);

    // Bring the new page up to speed immediately with the cached state.
    if (authed) {
        port.postMessage(Object.assign({ cached: true }, authed));
        port.postMessage({ type: 'history', messages: snapshot, hasMore: snapshotHasMore, cached: true });
        port.postMessage({ type: 'ws_status', state: (ws && ws.readyState === WebSocket.OPEN) ? 'ok' : 'connecting' });
    } else {
        port.postMessage({ type: 'ws_status', state: stopped ? 'err' : 'connecting' });
    }

    port.addEventListener('message', (ev) => safe(() => {
        const d = ev.data || {};
        if (d.type === 'send' && d.payload) {
            // Synchronous answer: ok = the frame really left the socket right now.
            port.postMessage({ type: 'send_ack', ref: d.ref, ok: sendFrame(d.payload) });
        } else if (d.type === 'resume') {
            // A page just logged in (fresh cookie): allow reconnecting again.
            stopped = false;
            reconnectDelay = 1000;
            open();
        } else if (d.type === 'stop') {
            stopped = true;
            stopSocket();
            setStatus('closed');
        }
    }));
    port.start();

    if (!ws && !stopped) open();
}));

// Keepalive so proxies / the 120 s server read-idle timeout never drop the socket.
setInterval(() => safe(() => { sendFrame({ type: 'ping' }); }), PING_MS);
