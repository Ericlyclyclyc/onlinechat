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
    const scrollDownBtn = document.getElementById('scroll-down');
    const loadMoreEl = document.getElementById('load-more');

    // Pagination: the server keeps the full archive and streams it a page at a time (newest first).
    const PAGE_SIZE = 30;
    const MAX_DOM = 2000;             // runaway guard: trim the oldest rendered nodes beyond this
    let oldestLoadedSeq = null;       // smallest message id in the DOM = the exclusive "load before" cursor
    let hasMore = false;              // server reported that older history still exists
    let loadingMore = false;          // a page request is in flight

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
    // The message list is its own scroll area: stick to the bottom only while the user is already
    // there; otherwise keep their position and reveal a "scroll to bottom" button.
    function isNearBottom() {
        return messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight <= 80;
    }
    function scrollToBottom(smooth) {
        messagesEl.scrollTo({ top: messagesEl.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    }
    function updateScrollBtn() {
        const scrollable = messagesEl.scrollHeight - messagesEl.clientHeight > 80;
        scrollDownBtn.classList.toggle('visible', scrollable && !isNearBottom());
    }

    // Builds the DOM node for one message (shared by append + prepend). Stamps data-seq so the
    // pagination cursor can be recovered from the topmost node after trimming.
    function buildMessageNode(m) {
        const div = document.createElement('div');
        if (m.id != null) div.dataset.seq = String(m.id);
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
        return div;
    }

    function appendMessage(m, forceScroll) {
        const stick = forceScroll === true || isNearBottom();
        messagesEl.appendChild(buildMessageNode(m));
        trimTop();
        if (stick) scrollToBottom(false);
        updateScrollBtn();
    }

    // Runaway guard for a very long-lived tab: drop the oldest rendered nodes, compensating scrollTop so
    // the viewport does not jump when the user is reading history, and keep the cursor pointing at the
    // new topmost node (trimmed messages stay safe on the server and can be paged back in).
    function trimTop() {
        while (messagesEl.children.length > MAX_DOM) {
            const first = messagesEl.firstChild;
            const h = first.offsetHeight;
            const near = isNearBottom();
            first.remove();
            if (!near) messagesEl.scrollTop = Math.max(0, messagesEl.scrollTop - h);
            const nf = messagesEl.firstChild;
            if (nf && nf.dataset && nf.dataset.seq) oldestLoadedSeq = Number(nf.dataset.seq);
        }
    }

    // Insert an older page at the top while preserving the exact scroll position (no perceptible jump).
    function prependMessages(list) {
        const prevHeight = messagesEl.scrollHeight;
        const prevTop = messagesEl.scrollTop;
        const frag = document.createDocumentFragment();
        list.forEach(m => frag.appendChild(buildMessageNode(m)));
        messagesEl.insertBefore(frag, messagesEl.firstChild);
        messagesEl.scrollTop = prevTop + (messagesEl.scrollHeight - prevHeight);
        if (list.length && list[0].id != null) oldestLoadedSeq = list[0].id;
        updateScrollBtn();
    }

    // Fetch the page just older than the topmost loaded message and prepend it.
    async function loadMore() {
        if (loadingMore || !hasMore || oldestLoadedSeq == null) return;
        loadingMore = true;
        loadMoreEl.classList.add('visible');
        try {
            const { ok, body } = await OC.API.get('/api/history?before=' + encodeURIComponent(oldestLoadedSeq) + '&limit=' + PAGE_SIZE);
            if (ok && body && Array.isArray(body.messages)) {
                hasMore = !!body.hasMore;
                if (body.messages.length) prependMessages(body.messages);
            }
        } catch (_) { /* transient; the next scroll to top will retry */ }
        finally {
            loadMoreEl.classList.remove('visible');
            loadingMore = false;
        }
    }

    // If the first page does not fill the viewport there is no scrollbar to trigger "load more", so top
    // up automatically until the list scrolls or the history is exhausted.
    async function maybeAutoFill() {
        let guard = 0;
        while (hasMore && !loadingMore && messagesEl.scrollHeight <= messagesEl.clientHeight + 8 && guard++ < 30) {
            await loadMore();
        }
    }

    // ───────────── Connection badge ─────────────
    function setConn(state) {
        connBadge.className = 'badge ' + (state === 'ok' ? 'ok' : (state === 'err' || state === 'closed') ? 'err' : '');
        const label = connBadge.querySelector('span:last-child');
        label.textContent = OC.I18N.t(state === 'ok' ? 'chat.connected'
            : state === 'err' ? 'chat.disconnected'
            : state === 'closed' ? 'chat.serverClosed' : 'chat.connecting');
    }

    // ───────────── WebSocket ─────────────
    let ws = null;
    let reconnectDelay = 1000;
    let historyLoaded = false;
    let forceLoggedOut = false;   // set when the server kicks us (account signed in elsewhere)
    let serverShutdown = false;   // set when the server tells us it is going down (do not reconnect)

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
                        const list = msg.messages || [];
                        const frag = document.createDocumentFragment();
                        list.forEach(m => frag.appendChild(buildMessageNode(m)));
                        messagesEl.appendChild(frag);
                        historyLoaded = true;
                        hasMore = !!msg.hasMore;
                        oldestLoadedSeq = (list.length && list[0].id != null) ? list[0].id : null;
                        scrollToBottom(false);
                        updateScrollBtn();
                        maybeAutoFill().then(() => { scrollToBottom(false); updateScrollBtn(); });
                    }
                    break;
                case 'chat':
                case 'web':
                case 'system':
                    appendMessage(msg);
                    break;
                case 'pong': break;
                case 'force_logout':
                    // This account signed in on another device (or its password changed / it was deleted);
                    // the server superseded our token. Pass the reason on so the login page can explain.
                    forceLoggedOut = true;
                    setConn('err');
                    OC.Toast.warn(OC.I18N.t('chat.forceLogout'), 6500);
                    try { ws.close(); } catch (_) {}
                    setTimeout(() => location.replace('/login.html?reason=' + encodeURIComponent(msg.reason || 'kicked')), 1500);
                    break;
                case 'server_shutdown':
                    // The Minecraft server is stopping: show a blocking notice and stop reconnecting.
                    serverShutdown = true;
                    setConn('closed');
                    try { ws.close(); } catch (_) {}
                    OC.Modal.alert({
                        tone: 'warn',
                        icon: OC.Modal.POWER_ICON,
                        title: OC.I18N.t('shutdown.title'),
                        message: OC.I18N.t('shutdown.body'),
                        buttonText: OC.I18N.t('shutdown.ok'),
                    });
                    break;
                case 'error':
                    OC.Toast.err(msg.error || OC.I18N.t('error.generic'));
                    break;
            }
        });
        ws.addEventListener('close', () => {
            if (forceLoggedOut) return;   // kicked: we are navigating to login, do not reconnect
            if (serverShutdown) return;   // server is down: hold the "server closed" state, do not reconnect
            setConn('err');
            historyLoaded = false;
            loadingMore = false;
            loadMoreEl.classList.remove('visible');
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
        // Echo locally so the sender sees their message immediately (and jump to the bottom).
        appendMessage({
            type: 'web', ts: Date.now(),
            author: me.mcName || me.username, text,
        }, true);
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

    // Scroll-area behaviour: keep the "scroll to bottom" button in sync, wire its click, and trigger
    // "load more" when the user scrolls to the very top of the list.
    messagesEl.addEventListener('scroll', () => {
        updateScrollBtn();
        if (hasMore && !loadingMore && messagesEl.scrollTop <= 48
                && messagesEl.scrollHeight > messagesEl.clientHeight) {
            loadMore();
        }
    }, { passive: true });
    window.addEventListener('resize', updateScrollBtn);
    scrollDownBtn.addEventListener('click', () => scrollToBottom(true));

    input.focus();
})();
