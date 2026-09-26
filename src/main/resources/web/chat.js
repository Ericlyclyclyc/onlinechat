/* Chat page: live WebSocket bridge with the Minecraft server.
 * Message list features: date separators, consecutive-message grouping, clickable links,
 * @mention highlight, per-message copy, unread title badge, optional sound, draft restore,
 * @-mention autocomplete, full-archive search overlay, online players + web users sidebar.
 */
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
    const webUsersEl = document.getElementById('web-users');
    const webUsersEmpty = document.getElementById('web-users-empty');
    const accName = document.getElementById('acc-name');
    const accBound = document.getElementById('acc-bound');
    const scrollDownBtn = document.getElementById('scroll-down');
    const loadMoreEl = document.getElementById('load-more');
    const soundToggle = document.getElementById('sound-toggle');

    // Pagination: the server keeps the full archive and streams it a page at a time (newest first).
    const PAGE_SIZE = 30;
    const MAX_DOM = 2000;             // runaway guard: trim the oldest rendered nodes beyond this
    const GROUP_WINDOW_MS = 3 * 60 * 1000;  // same author + same side within 3 min → compact grouping
    let oldestLoadedSeq = null;       // smallest message id in the DOM = the exclusive "load before" cursor
    let hasMore = false;              // server reported that older history still exists
    let loadingMore = false;          // a page request is in flight
    let lastGroup = null;             // grouping/divider state of the newest rendered message

    // Style config from the server (prefix text/colour, max length).
    let style = {
        inGamePrefixText: '[In Game]', inGamePrefixColor: '#2ecc71',
        webPrefixText: '[Web Chat]', webPrefixColor: '#e67e22',
        maxMessageLength: 512,
    };
    let playersCache = [];            // [{name, uuid}] from /api/online
    let webUsersCache = [];           // [{username, mcName?, self}] from /api/webusers

    // ───────────── Sound / unread notifications ─────────────
    if (soundToggle) {
        soundToggle.checked = OC.Sound.enabled();
        soundToggle.addEventListener('change', () => {
            OC.Sound.setEnabled(soundToggle.checked);
            if (soundToggle.checked) OC.Sound.beep();   // audible confirmation
        });
    }
    document.addEventListener('click', () => OC.Sound.unlock(), { once: true });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) OC.TitleBadge.reset(); });
    window.addEventListener('focus', () => OC.TitleBadge.reset());

    function maybeNotify(m) {
        if (m.author && me && (m.author === me.username || m.author === me.mcName)) return;
        if (document.hidden) OC.TitleBadge.incr();
        if (document.hidden || !document.hasFocus()) OC.Sound.beep();
    }

    // ───────────── Account sidebar ─────────────
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
    const COPY_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';

    function dayOf(ts) { return new Date(ts || Date.now()).toDateString(); }
    function groupable(m) { return m && (m.type === 'chat' || m.type === 'web'); }
    function shouldGroup(prev, m) {
        return !!(prev && groupable(prev) && groupable(m) &&
            prev.author === m.author && prev.type === m.type &&
            m.ts - prev.ts >= 0 && m.ts - prev.ts < GROUP_WINDOW_MS);
    }
    function mentionsMe(text) {
        if (!me || !text) return false;
        const s = text.toLowerCase();
        const names = [me.username];
        if (me.mcName) names.push(me.mcName);
        return names.some(n => {
            if (!n) return false;
            const needle = '@' + n.toLowerCase();
            let idx = s.indexOf(needle);
            while (idx >= 0) {
                const before = idx === 0 ? '' : s[idx - 1];
                const afterIdx = idx + needle.length;
                const after = afterIdx >= s.length ? '' : s[afterIdx];
                if (!/[a-z0-9_]/.test(before) && !/[a-z0-9_]/.test(after)) return true;
                idx = s.indexOf(needle, idx + 1);
            }
            return false;
        });
    }
    // Server config strings land in a style="" attribute; keep only harmless colour-ish values.
    function styleColor(c) {
        return /^[#\w\s,().%-]+$/.test(String(c || '')) ? String(c) : '';
    }

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

    // Builds the DOM node for one message (shared by append, prepend and search results).
    // The wrapper .msg-entry always renders the full structure; grouping and dividers are pure
    // CSS classes so a later boundary fix can toggle them without rebuilding the node.
    function buildMessageNode(m, opts) {
        opts = opts || {};
        const entry = document.createElement('div');
        entry.className = 'msg-entry';
        if (m.id != null) entry.dataset.seq = String(m.id);
        entry.dataset.type = m.type || 'system';
        entry.dataset.ts = String(m.ts || 0);
        if (m.author != null) entry.dataset.author = m.author;

        if (opts.divider) {
            const sep = document.createElement('div');
            sep.className = 'day-sep';
            const span = document.createElement('span');
            span.textContent = opts.divider;
            sep.appendChild(span);
            entry.appendChild(sep);
        }

        const isMine = me && m.author && (m.author === me.username || m.author === me.mcName);
        if (m.type === 'system') {
            const div = document.createElement('div');
            div.className = 'msg from-system' + (m.systemKind === 'announce' ? ' sys-announce' : '');
            const bubble = document.createElement('div');
            bubble.className = 'bubble';
            if (m.systemKind === 'announce') {
                const tag = document.createElement('span');
                tag.className = 'announce-tag';
                tag.textContent = '📢 ' + OC.I18N.t('chat.announce.label');
                bubble.appendChild(tag);
            }
            bubble.appendChild(OC.renderText(m.text || ''));
            div.appendChild(bubble);
            entry.appendChild(div);
            return entry;
        }

        const fromGame = m.type === 'chat';
        const div = document.createElement('div');
        div.className = 'msg ' + (fromGame ? 'from-game' : 'from-web') + (isMine ? ' mine' : '') + (opts.grouped ? ' compact' : '');
        const initial = (m.author || '?').charAt(0).toUpperCase();
        const prefixText = fromGame ? style.inGamePrefixText : style.webPrefixText;
        const prefixColor = fromGame ? style.inGamePrefixColor : style.webPrefixColor;
        const prefixClass = fromGame ? 'ingame' : 'web';
        div.innerHTML = `
            <div class="avatar">${OC.escapeHtml(initial)}</div>
            <div class="body">
                <div class="meta">
                    <span class="prefix ${prefixClass}" style="color:${styleColor(prefixColor) || (fromGame ? '#2ecc71' : '#e67e22')}">${OC.escapeHtml(prefixText)}</span>
                    <span class="name">${OC.escapeHtml(m.author || '?')}</span>
                    <span class="ts">${OC.fmtTime(m.ts)}</span>
                </div>
                <div class="bubble"></div>
            </div>`;
        const bubble = div.querySelector('.bubble');
        bubble.appendChild(OC.renderText(m.text || ''));
        if (mentionsMe(m.text)) bubble.classList.add('mention-hit');
        const copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'copy-btn';
        copyBtn.innerHTML = COPY_SVG;
        copyBtn.title = OC.I18N.t('chat.copy');
        copyBtn.setAttribute('aria-label', OC.I18N.t('chat.copy'));
        copyBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            if (await OC.copyText(m.text || '')) OC.Toast.ok(OC.I18N.t('chat.copied'));
        });
        bubble.appendChild(copyBtn);
        if (opts.grouped) div.title = `${m.author || '?'} · ${OC.fmtTime(m.ts)}`;
        entry.appendChild(div);
        return entry;
    }

    function appendMessage(m, forceScroll) {
        const stick = forceScroll === true || isNearBottom();
        const prev = lastGroup;
        const divider = !prev || dayOf(m.ts) !== prev.day ? OC.fmtDate(m.ts) : null;
        const grouped = shouldGroup(prev, m);
        messagesEl.appendChild(buildMessageNode(m, { divider, grouped }));
        lastGroup = { author: m.author, type: m.type, ts: m.ts, day: dayOf(m.ts) };
        trimTop();
        if (stick) scrollToBottom(false);
        updateScrollBtn();
        maybeNotify(m);
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

    function firstEntryInfo() {
        const first = messagesEl.querySelector('.msg-entry');
        if (!first) return null;
        const ts = Number(first.dataset.ts || 0);
        return { author: first.dataset.author || null, type: first.dataset.type, ts, day: dayOf(ts) };
    }

    // Insert an older page at the top while preserving the exact scroll position (no perceptible jump).
    function prependMessages(list) {
        const prevHeight = messagesEl.scrollHeight;
        const prevTop = messagesEl.scrollTop;
        let prev = firstEntryInfo();
        const frag = document.createDocumentFragment();
        for (const m of list) {
            const divider = !prev || dayOf(m.ts) !== prev.day ? OC.fmtDate(m.ts) : null;
            const grouped = shouldGroup(prev, m);
            frag.appendChild(buildMessageNode(m, { divider, grouped }));
            prev = { author: m.author, type: m.type, ts: m.ts, day: dayOf(m.ts) };
        }
        messagesEl.insertBefore(frag, messagesEl.firstChild);
        if (list.length) {
            fixBoundary(list[list.length - 1]);
            if (list[0].id != null) oldestLoadedSeq = list[0].id;
        }
        messagesEl.scrollTop = prevTop + (messagesEl.scrollHeight - prevHeight);
        updateScrollBtn();
    }

    // The old topmost node may now (a) start a new date and need a divider, or (b) follow a message
    // from the same author and need compact grouping. Toggle both — pure class/child tweaks.
    function fixBoundary(lastNew) {
        const nodes = messagesEl.querySelectorAll('.msg-entry');
        if (nodes.length < 2 || !lastNew) return;
        const second = nodes[1];
        const secTs = Number(second.dataset.ts || 0);
        const secInfo = { author: second.dataset.author || null, type: second.dataset.type, ts: secTs };
        const msgEl = second.querySelector('.msg');
        if (msgEl) msgEl.classList.toggle('compact', !!shouldGroup(lastNew, secInfo));
        const existingSep = second.querySelector(':scope > .day-sep');
        if (dayOf(lastNew.ts) !== dayOf(secTs)) {
            if (!existingSep) {
                const sep = document.createElement('div');
                sep.className = 'day-sep';
                const span = document.createElement('span');
                span.textContent = OC.fmtDate(secTs);
                sep.appendChild(span);
                second.insertBefore(sep, second.firstChild);
            }
        } else if (existingSep) {
            existingSep.remove();
        }
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

    // ───────────── WebSocket (shared across pages via OC.Ws) ─────────────
    // The socket lives in a SharedWorker (or a direct fallback socket) owned by OC.Ws, so
    // navigating between chat.html and account.html keeps the same connection — no
    // disconnect/reconnect churn. All server frames arrive through OC.Ws.on handlers.

    OC.Ws.on('ws_status', (msg) => setConn(msg.state));

    OC.Ws.on('auth_ok', (msg) => {
        if (msg.username) { me.username = msg.username; me.bound = msg.bound; me.mcName = msg.mcName; me.mcUuid = msg.mcUuid; renderAccount(me); }
    });

    OC.Ws.on('auth_error', (msg) => {
        OC.Toast.err(msg.error || OC.I18N.t('error.unauthorized'));
    });

    // The worker replays its cached snapshot to late-attaching pages and streams a fresh
    // page after every (re)authentication — re-render the list in both cases.
    OC.Ws.on('history', (msg) => {
        messagesEl.innerHTML = '';
        lastGroup = null;
        const list = msg.messages || [];
        const frag = document.createDocumentFragment();
        for (const m of list) {
            const divider = !lastGroup || dayOf(m.ts) !== lastGroup.day ? OC.fmtDate(m.ts) : null;
            const grouped = shouldGroup(lastGroup, m);
            frag.appendChild(buildMessageNode(m, { divider, grouped }));
            lastGroup = { author: m.author, type: m.type, ts: m.ts, day: dayOf(m.ts) };
        }
        messagesEl.appendChild(frag);
        hasMore = !!msg.hasMore;
        oldestLoadedSeq = (list.length && list[0].id != null) ? list[0].id : null;
        loadingMore = false;
        loadMoreEl.classList.remove('visible');
        scrollToBottom(false);
        updateScrollBtn();
        maybeAutoFill().then(() => { scrollToBottom(false); updateScrollBtn(); });
    });

    OC.Ws.on('chat', (msg) => appendMessage(msg));
    OC.Ws.on('web', (msg) => appendMessage(msg));
    OC.Ws.on('system', (msg) => appendMessage(msg));

    OC.Ws.on('force_logout', (msg) => {
        // This account signed in on another device (or its password changed / it was deleted);
        // the server superseded our token. Pass the reason on so the login page can explain.
        OC.Ws.stop();
        setConn('err');
        OC.Toast.warn(OC.I18N.t('chat.forceLogout'), 6500);
        setTimeout(() => location.replace('/login.html?reason=' + encodeURIComponent(msg.reason || 'kicked')), 1500);
    });

    OC.Ws.on('server_shutdown', () => {
        // The Minecraft server is stopping: show a blocking notice; OC.Ws stops reconnecting.
        setConn('closed');
        OC.Modal.alert({
            tone: 'warn',
            icon: OC.Modal.POWER_ICON,
            title: OC.I18N.t('shutdown.title'),
            message: OC.I18N.t('shutdown.body'),
            buttonText: OC.I18N.t('shutdown.ok'),
        });
    });

    OC.Ws.on('error', (msg) => {
        OC.Toast.err(msg.error || OC.I18N.t('error.generic'));
    });

    // ───────────── Composer ─────────────
    // Draft persistence: remember the un-sent text across reloads.
    const DRAFT_KEY = 'oc.draft';
    const saveDraft = OC.debounce(() => {
        const v = input.value;
        if (v) sessionStorage.setItem(DRAFT_KEY, v);
        else sessionStorage.removeItem(DRAFT_KEY);
    }, 400);
    input.addEventListener('input', saveDraft);
    const draft = sessionStorage.getItem(DRAFT_KEY);
    if (draft) input.value = draft;

    composer.addEventListener('submit', async (e) => {
        e.preventDefault();
        const text = input.value.trim();
        if (!text) return;
        // OC.Ws.send resolves to true only when the transport really accepted the frame
        // (the shared worker acknowledges synchronously). On failure keep the text so
        // nothing is lost.
        const sent = await OC.Ws.send({ type: 'chat', text });
        if (!sent) {
            OC.Toast.warn(OC.I18N.t('chat.disconnected'));
            return;
        }
        // NO local echo. The server archives the message and pushes the authoritative
        // 'web' frame back to every session — including this one — and that echo is what
        // renders it here. Own messages can therefore never be shown-but-unsent (or
        // silently swallowed) the way the old optimistic echo could.
        input.value = '';
        sessionStorage.removeItem(DRAFT_KEY);
        closeMention();
        input.focus();
    });

    // Escape clears the input when the mention dropdown is closed.
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !mentionState.open) {
            if (input.value) {
                input.value = '';
                sessionStorage.removeItem(DRAFT_KEY);
            } else {
                input.blur();
            }
        }
    });

    // ───────────── @-mention autocomplete ─────────────
    const mentionBox = document.createElement('div');
    mentionBox.className = 'mention-box hidden';
    composer.appendChild(mentionBox);
    let mentionState = { open: false, items: [], active: -1, tokenStart: -1 };

    function mentionToken() {
        const pos = input.selectionStart || 0;
        const text = input.value.slice(0, pos);
        const m = text.match(/(?:^|\s)@([^\s@]*)$/);
        if (!m) return null;
        const tokenStart = pos - m[0].length + (m[0][0] === '@' ? 0 : 1);
        return { query: m[1].toLowerCase(), tokenStart };
    }

    function mentionCandidates(q) {
        const out = [];
        const seen = new Set();
        for (const p of playersCache) {
            const key = p.name.toLowerCase();
            if (seen.has(key)) continue;
            if (!q || key.startsWith(q)) {
                seen.add(key);
                out.push({ text: p.name, group: 'player' });
            }
        }
        for (const w of webUsersCache) {
            const key = w.username.toLowerCase();
            if (seen.has(key)) continue;
            if (!q || key.startsWith(q)) {
                seen.add(key);
                out.push({ text: w.username, group: 'web' });
            }
        }
        return out.slice(0, 8);
    }

    function renderMentionBox() {
        const items = mentionState.items;
        if (!items.length) { closeMention(); return; }
        mentionBox.innerHTML = '';
        items.forEach((item, i) => {
            const div = document.createElement('div');
            div.className = 'mention-item' + (i === mentionState.active ? ' active' : '');
            div.innerHTML = `<span class="avatar mini">${OC.escapeHtml(item.text.charAt(0).toUpperCase())}</span>
                <span>${OC.escapeHtml(item.text)}</span>
                <span class="mention-tag ${item.group}">${OC.escapeHtml(OC.I18N.t(item.group === 'web' ? 'chat.mention.web' : 'chat.mention.player'))}</span>`;
            div.addEventListener('mousedown', (e) => {
                e.preventDefault();
                insertMention(item);
            });
            mentionBox.appendChild(div);
        });
        mentionBox.classList.remove('hidden');
        mentionState.open = true;
    }

    function closeMention() {
        mentionState.open = false;
        mentionState.items = [];
        mentionState.active = -1;
        mentionBox.classList.add('hidden');
    }

    function insertMention(item) {
        const pos = input.selectionStart || input.value.length;
        const text = input.value.slice(0, pos);
        const m = text.match(/(?:^|\s)@[^\s@]*$/);
        if (!m) return;
        const atIdx = pos - m[0].length + (m[0][0] === '@' ? 0 : 1);
        const tail = input.value.slice(pos);
        input.value = input.value.slice(0, atIdx) + '@' + item.text + ' ' + tail;
        const caret = atIdx + item.text.length + 2;
        input.setSelectionRange(caret, caret);
        closeMention();
        input.focus();
        saveDraft();
    }

    input.addEventListener('input', () => {
        const tok = mentionToken();
        if (!tok) { closeMention(); return; }
        mentionState.items = mentionCandidates(tok.query);
        mentionState.tokenStart = tok.tokenStart;
        mentionState.active = mentionState.items.length ? 0 : -1;
        renderMentionBox();
    });
    input.addEventListener('keydown', (e) => {
        if (!mentionState.open) return;
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            if (!mentionState.items.length) return;
            const delta = e.key === 'ArrowDown' ? 1 : -1;
            mentionState.active = (mentionState.active + delta + mentionState.items.length) % mentionState.items.length;
            renderMentionBox();
        } else if (e.key === 'Enter' || e.key === 'Tab') {
            if (mentionState.active >= 0 && mentionState.items[mentionState.active]) {
                e.preventDefault();
                insertMention(mentionState.items[mentionState.active]);
            } else {
                closeMention();
            }
        } else if (e.key === 'Escape') {
            e.stopPropagation();
            closeMention();
        }
    });
    document.addEventListener('click', (e) => {
        if (!composer.contains(e.target)) closeMention();
    });

    // ───────────── Online players sidebar ─────────────
    async function refreshPlayers() {
        try {
            const { ok, body } = await OC.API.get('/api/online');
            if (!ok) return;
            const players = body.players || [];
            playersCache = players;
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

    // ───────────── Web users sidebar ─────────────
    async function refreshWebUsers() {
        try {
            const { ok, body } = await OC.API.get('/api/webusers');
            if (!ok) return;
            webUsersCache = body.webUsers || [];
            webUsersEl.innerHTML = '';
            const others = webUsersCache.filter(w => !w.self);
            webUsersEmpty.classList.toggle('hidden', others.length > 0);
            others.forEach(w => {
                const li = document.createElement('li');
                li.className = 'online web-user';
                li.innerHTML = `<span class="avatar">${OC.escapeHtml((w.username || '?').charAt(0).toUpperCase())}</span>
                    <span>${OC.escapeHtml(w.username)}</span>
                    ${w.mcName ? `<span class="web-bound">→ ${OC.escapeHtml(w.mcName)}</span>` : ''}
                    <span class="status-dot"></span>`;
                webUsersEl.appendChild(li);
            });
        } catch (_) {}
    }
    refreshPlayers();
    refreshWebUsers();
    setInterval(refreshPlayers, 10000);
    setInterval(refreshWebUsers, 10000);

    // ───────────── Full-archive search overlay ─────────────
    const searchLayer = document.getElementById('search-layer');
    const searchInput = document.getElementById('search-input');
    const searchResults = document.getElementById('search-results');
    const searchStatus = document.getElementById('search-status');
    const searchMoreBtn = document.getElementById('search-more');
    const searchOpenBtn = document.getElementById('search-open');
    const searchCloseBtn = document.getElementById('search-close');
    let searchState = { q: '', loading: false };

    function openSearch() {
        searchLayer.classList.remove('hidden');
        searchInput.focus();
        searchInput.select();
    }
    function closeSearch() {
        searchLayer.classList.add('hidden');
    }
    searchOpenBtn.addEventListener('click', openSearch);
    searchCloseBtn.addEventListener('click', closeSearch);

    function renderSearchStatus(text, kind) {
        searchStatus.className = 'search-status' + (kind ? ' ' + kind : '');
        searchStatus.textContent = text;
    }

    async function runSearch(q, before) {
        if (searchState.loading) return;
        searchState.loading = true;
        const isFirst = !before;
        try {
            const params = new URLSearchParams({ q, limit: String(PAGE_SIZE) });
            if (before) params.set('before', String(before));
            const { ok, body } = await OC.API.get('/api/search?' + params.toString());
            if (!ok) {
                renderSearchStatus(body && body.error ? body.error : OC.I18N.t('error.generic'), 'err');
                return;
            }
            const list = body.messages || [];
            if (isFirst) searchResults.innerHTML = '';
            const frag = document.createDocumentFragment();
            list.forEach(m => frag.appendChild(buildMessageNode(m, {})));
            searchResults.appendChild(frag);
            searchState.hasMore = !!body.hasMore;
            searchMoreBtn.classList.toggle('hidden', !searchState.hasMore);
            if (isFirst) {
                renderSearchStatus(
                    list.length === 0 ? OC.I18N.t('chat.search.noResults')
                        : OC.I18N.t('chat.search.results', { n: list.length }),
                    list.length === 0 ? 'empty' : 'ok');
            }
        } catch (_) {
            renderSearchStatus(OC.I18N.t('error.network'), 'err');
        } finally {
            searchState.loading = false;
        }
    }

    searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            const q = searchInput.value.trim();
            if (!q) return;
            searchState.q = q;
            runSearch(q, 0);
        } else if (e.key === 'Escape') {
            closeSearch();
        }
    });

    searchMoreBtn.addEventListener('click', () => {
        const entries = searchResults.querySelectorAll('.msg-entry');
        const lastSeq = entries.length ? Number(entries[entries.length - 1].dataset.seq) : 0;
        if (searchState.q && lastSeq) runSearch(searchState.q, lastSeq);
    });

    // Clicking a result jumps into the main list when that message is still in the DOM.
    searchResults.addEventListener('click', (e) => {
        if (e.target.closest('.copy-btn') || e.target.closest('a.autolink')) return;
        const entry = e.target.closest('.msg-entry');
        if (!entry || entry.dataset.seq == null) return;
        jumpToSeq(Number(entry.dataset.seq));
    });

    function jumpToSeq(seq) {
        const node = messagesEl.querySelector(`.msg-entry[data-seq="${seq}"]`);
        if (!node) {
            OC.Toast.info(OC.I18N.t('chat.search.notLoaded'));
            return;
        }
        closeSearch();
        node.scrollIntoView({ block: 'center' });
        node.classList.add('flash');
        setTimeout(() => node.classList.remove('flash'), 2000);
    }

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
