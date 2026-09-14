/* Chat page: live WebSocket bridge with the Minecraft server. */
(async function () {
    'use strict';
    await OC.I18N.load();
    const me = await OC.Auth.requireAuth('/login.html');
    if (!me) return;
    OC.renderNav('chat', me);

    const messagesEl = document.getElementById('messages');
    const composer = document.getElementById('composer');
    const input = document.getElementById('input');
    const sendBtn = document.getElementById('send-btn');
    const connBadge = document.getElementById('conn-badge');
    const playersEl = document.getElementById('players');
    const playersEmpty = document.getElementById('players-empty');
    const accName = document.getElementById('acc-name');
    const accBound = document.getElementById('acc-bound');

    // Style config from the server (prefix text/colour, max length).
    let style = {
        inGamePrefixText: '[In Game]', inGamePrefixColor: '#2ecc71',
        webPrefixText: '[Web Chat]', webPrefixColor: '#e67e22',
        maxMessageLength: 512,
    };

    // Account sidebar
    function renderAccount(acc) {
        accName.textContent = acc.username;
        if (acc.bound) {
            accBound.innerHTML = `${OC.escapeHtml(acc.mcName)} ` +
                (acc.mcOnline
                    ? `<span class="badge ok"><span class="dot"></span>${OC.escapeHtml(OC.I18N.t('bind.status.online'))}</span>`
                    : `<span class="badge"><span class="dot"></span>${OC.escapeHtml(OC.I18N.t('bind.status.offline'))}</span>`);
        } else {
            accBound.innerHTML = `<span class="text-mute small">${OC.escapeHtml(OC.I18N.t('bind.notBound'))}</span>`;
        }
        if (!acc.bound) {
            // Nudge the user toward binding once.
            if (!sessionStorage.getItem('oc.bindNudge')) {
                OC.Toast.info(OC.I18N.t('chat.err.notBound'));
                sessionStorage.setItem('oc.bindNudge', '1');
            }
        }
    }
    renderAccount(me);

    // ───────────── Message rendering ─────────────
    function appendMessage(m) {
        const div = document.createElement('div');
        const isMine = me && m.author && (m.author === me.username || m.author === me.mcName);
        if (m.type === 'system') {
            div.className = 'msg from-system';
            div.innerHTML = `<div class="bubble">${OC.escapeHtml(m.text || '')}</div>`;
        } else {
            const fromGame = m.type === 'chat';
            div.className = 'msg ' + (fromGame ? 'from-game' : 'from-web') + (isMine ? ' mine' : '');
            const initial = (m.author || '?').charAt(0).toUpperCase();
            const prefixText = fromGame ? style.inGamePrefixText : style.webPrefixText;
            const prefixColor = fromGame ? style.inGamePrefixColor : style.webPrefixColor;
            const prefixClass = fromGame ? 'ingame' : 'web';
            div.innerHTML = `
                <div class="avatar">${OC.escapeHtml(initial)}</div>
                <div class="body">
                    <div class="meta">
                        <span class="prefix ${prefixClass}" style="color:${prefixColor}">${OC.escapeHtml(prefixText)}</span>
                        <span class="name">${OC.escapeHtml(m.author || '?')}</span>
                        <span class="ts">${OC.fmtTime(m.ts)}</span>
                    </div>
                    <div class="bubble">${OC.escapeHtml(m.text || '')}</div>
                </div>`;
        }
        messagesEl.appendChild(div);
        // Cap rendered messages to avoid unbounded DOM growth.
        while (messagesEl.children.length > 400) messagesEl.removeChild(messagesEl.firstChild);
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    // ───────────── Connection badge ─────────────
    function setConn(state) {
        connBadge.className = 'badge ' + (state === 'ok' ? 'ok' : state === 'err' ? 'err' : '');
        const label = connBadge.querySelector('span:last-child');
        label.textContent = OC.I18N.t(state === 'ok' ? 'chat.connected'
            : state === 'err' ? 'chat.disconnected' : 'chat.connecting');
    }

    // ───────────── WebSocket ─────────────
    let ws = null;
    let reconnectDelay = 1000;
    let historyLoaded = false;

    function connect() {
        setConn('connecting');
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(`${proto}//${location.host}/ws`);

        ws.addEventListener('open', () => {
            reconnectDelay = 1000;
        });
        ws.addEventListener('message', (ev) => {
            let msg;
            try { msg = JSON.parse(ev.data); } catch (_) { return; }
            switch (msg.type) {
                case 'ready': break;
                case 'auth_ok':
                    setConn('ok');
                    if (msg.username) { me.username = msg.username; me.bound = msg.bound; me.mcName = msg.mcName; me.mcUuid = msg.mcUuid; renderAccount(me); }
                    break;
                case 'auth_error':
                    setConn('err');
                    OC.Toast.err(msg.error || OC.I18N.t('error.unauthorized'));
                    break;
                case 'history':
                    if (!historyLoaded) {
                        messagesEl.innerHTML = '';
                        (msg.messages || []).forEach(appendMessage);
                        historyLoaded = true;
                    }
                    break;
                case 'chat':
                case 'web':
                case 'system':
                    appendMessage(msg);
                    break;
                case 'pong': break;
                case 'error':
                    OC.Toast.err(msg.error || OC.I18N.t('error.generic'));
                    break;
            }
        });
        ws.addEventListener('close', () => {
            setConn('err');
            historyLoaded = false;
            setTimeout(connect, reconnectDelay);
            reconnectDelay = Math.min(reconnectDelay * 2, 15000);
        });
        ws.addEventListener('error', () => setConn('err'));
    }
    connect();

    // Keepalive ping every 50s so proxies do not drop the socket.
    setInterval(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
            try { ws.send(JSON.stringify({ type: 'ping' })); } catch (_) {}
        }
    }, 50000);

    // ───────────── Composer ─────────────
    composer.addEventListener('submit', (e) => {
        e.preventDefault();
        const text = input.value.trim();
        if (!text) return;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
            OC.Toast.warn(OC.I18N.t('chat.disconnected'));
            return;
        }
        ws.send(JSON.stringify({ type: 'chat', text }));
        // Echo locally so the sender sees their message immediately.
        appendMessage({
            type: 'web', ts: Date.now(),
            author: me.mcName || me.username, text,
        });
        input.value = '';
        input.focus();
    });

    // ───────────── Online players sidebar ─────────────
    async function refreshPlayers() {
        try {
            const { ok, body } = await OC.API.get('/api/online');
            if (!ok) return;
            const players = body.players || [];
            playersEl.innerHTML = '';
            if (players.length === 0) {
                playersEmpty.classList.remove('hidden');
            } else {
                playersEmpty.classList.add('hidden');
                players.forEach(p => {
                    const li = document.createElement('li');
                    li.className = 'online';
                    li.innerHTML = `<span class="avatar">${OC.escapeHtml((p.name || '?').charAt(0).toUpperCase())}</span>
                        <span>${OC.escapeHtml(p.name)}</span><span class="status-dot"></span>`;
                    playersEl.appendChild(li);
                });
            }
        } catch (_) {}
    }
    refreshPlayers();
    setInterval(refreshPlayers, 10000);

    // ───────────── Load style config + max length ─────────────
    try {
        const { ok, body } = await OC.API.get('/api/status');
        if (ok && body.style) {
            style = Object.assign(style, body.style);
            input.maxLength = style.maxMessageLength || 512;
        }
    } catch (_) {}

    // Refresh account/binding state periodically.
    setInterval(async () => {
        const acc = await OC.Auth.me();
        if (acc) { me.bound = acc.bound; me.mcName = acc.mcName; me.mcUuid = acc.mcUuid; renderAccount(me); }
    }, 20000);

    input.focus();
})();
