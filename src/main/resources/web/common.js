/* Online Chat — shared client library.
 * Provides: API helpers with cookie credentials, i18n loader, toast UI, nav rendering.
 */
(function (global) {
    'use strict';

    // ───────────────────────────── API ─────────────────────────────
    const API = {
        async request(path, opts = {}) {
            const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
            const res = await fetch(path, Object.assign({
                credentials: 'same-origin',
                headers,
            }, opts));
            let body = {};
            try { body = await res.json(); } catch (_) {}
            return { ok: res.ok, status: res.status, body };
        },
        get(path) { return API.request(path, { method: 'GET' }); },
        post(path, data) { return API.request(path, { method: 'POST', body: JSON.stringify(data || {}) }); },
    };

    // ───────────────────────────── I18n ─────────────────────────────
    const SUPPORTED_LOCALES = ['en', 'zh-CN'];
    const I18N = {
        locale: 'en',
        dict: {},
        async load(preferred) {
            const saved = localStorage.getItem('oc.locale');
            const browser = (navigator.language || 'en').replace('_', '-');
            const pick = preferred || saved || (SUPPORTED_LOCALES.includes(browser) ? browser : browser.split('-')[0]);
            this.locale = SUPPORTED_LOCALES.includes(pick) ? pick
                : SUPPORTED_LOCALES.includes(pick.split('-')[0]) ? pick.split('-')[0]
                : 'en';
            try {
                const res = await fetch(`/locales/${this.locale}.json`, { credentials: 'same-origin' });
                if (!res.ok) throw new Error('locale http ' + res.status);
                this.dict = await res.json();
            } catch (e) {
                // Fall back to English if the requested locale is missing.
                if (this.locale !== 'en') {
                    this.locale = 'en';
                    try {
                        const res = await fetch('/locales/en.json', { credentials: 'same-origin' });
                        this.dict = await res.json();
                    } catch (_) { this.dict = {}; }
                }
            }
            localStorage.setItem('oc.locale', this.locale);
            document.documentElement.lang = this.locale;
            this.apply();
        },
        t(key, vars) {
            let s = this.dict[key];
            if (s == null) s = key;
            if (vars) for (const k of Object.keys(vars)) s = s.replace(new RegExp('\\{' + k + '\\}', 'g'), String(vars[k]));
            return s;
        },
        apply(root) {
            const scope = root || document;
            scope.querySelectorAll('[data-i18n]').forEach(el => {
                el.textContent = I18N.t(el.dataset.i18n);
            });
            scope.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
                el.placeholder = I18N.t(el.dataset.i18nPlaceholder);
            });
            scope.querySelectorAll('[data-i18n-title]').forEach(el => {
                el.title = I18N.t(el.dataset.i18nTitle);
            });
            scope.querySelectorAll('[data-i18n-html]').forEach(el => {
                el.innerHTML = I18N.t(el.dataset.i18nHtml);
            });
        },
        setLocale(loc) {
            localStorage.setItem('oc.locale', loc);
            location.reload();
        },
    };

    // ───────────────────────────── Toast ─────────────────────────────
    const Toast = {
        stack: null,
        ensure() {
            if (!this.stack) {
                this.stack = document.createElement('div');
                this.stack.className = 'toast-stack';
                document.body.appendChild(this.stack);
            }
            return this.stack;
        },
        show(message, kind = 'info', timeout = 3800) {
            const stack = this.ensure();
            const el = document.createElement('div');
            el.className = 'toast ' + kind;
            el.textContent = message;
            stack.appendChild(el);
            setTimeout(() => {
                el.style.transition = 'opacity .25s, transform .25s';
                el.style.opacity = '0';
                el.style.transform = 'translateX(10px)';
                setTimeout(() => el.remove(), 260);
            }, timeout);
        },
        ok(m) { this.show(m, 'ok'); },
        err(m) { this.show(m, 'err', 5200); },
        warn(m) { this.show(m, 'warn'); },
        info(m) { this.show(m, 'info'); },
    };

    // ───────────────────────────── Modal ─────────────────────────────
    // A single blocking dialog (backdrop + card). Used for notices that must not be missed — e.g. the
    // server shutting down. Only one modal is shown at a time; re-invoking replaces the current one.
    const Modal = {
        DEFAULT_ICON: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
        // Power-off glyph, used for the "server has shut down" notice.
        POWER_ICON: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2" x2="12" y2="12"/></svg>',
        /**
         * @param {object} opts { title, message, buttonText, tone='warn', icon, dismissible=false, onClose }
         * @returns {HTMLElement} the overlay element (removed on close)
         */
        alert(opts = {}) {
            const title = opts.title || '';
            const message = opts.message || '';
            const buttonText = opts.buttonText || 'OK';
            const tone = opts.tone || 'warn';
            const icon = opts.icon || Modal.DEFAULT_ICON;
            const dismissible = !!opts.dismissible;
            const onClose = opts.onClose || null;

            // Never stack duplicates (a repeated frame would otherwise pile up overlays).
            document.querySelectorAll('.modal-overlay').forEach(n => n.remove());

            const overlay = document.createElement('div');
            overlay.className = 'modal-overlay';
            overlay.setAttribute('role', 'dialog');
            overlay.setAttribute('aria-modal', 'true');
            overlay.innerHTML = `
                <div class="modal-card tone-${tone}">
                    <div class="modal-icon">${icon}</div>
                    <h2 class="modal-title"></h2>
                    <p class="modal-msg"></p>
                    <div class="modal-actions"><button class="btn primary modal-btn"></button></div>
                </div>`;
            // textContent (not innerHTML) for the copy so it is always treated as plain text.
            overlay.querySelector('.modal-title').textContent = title;
            overlay.querySelector('.modal-msg').textContent = message;
            const btn = overlay.querySelector('.modal-btn');
            btn.textContent = buttonText;

            function onKey(e) { if (e.key === 'Escape' && dismissible) close(); }
            function close() {
                document.removeEventListener('keydown', onKey);
                overlay.classList.remove('show');
                overlay.classList.add('closing');
                setTimeout(() => { overlay.remove(); if (onClose) onClose(); }, 160);
            }
            btn.addEventListener('click', close);
            if (dismissible) overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
            document.addEventListener('keydown', onKey);

            document.body.appendChild(overlay);
            requestAnimationFrame(() => overlay.classList.add('show'));
            btn.focus();
            return overlay;
        },
    };

    // ───────────────────────────── Auth ─────────────────────────────
    const Auth = {
        // Token is optional: the server also accepts the oc_token cookie.
        // We keep it in memory only (never localStorage) so a stolen device
        // does not carry an offline-copyable credential.
        token: null,
        user: null,

        async me() {
            const { ok, body } = await API.get('/api/me');
            if (!ok) return null;
            this.user = body;
            return body;
        },
        async requireAuth(redirectTo = '/login.html') {
            const me = await this.me();
            if (!me) {
                location.replace(redirectTo + '?next=' + encodeURIComponent(location.pathname + location.search));
                return null;
            }
            return me;
        },
        async logout() {
            try { await API.post('/api/logout'); } catch (_) {}
            this.token = null;
            this.user = null;
            Ws.stop();   // the server closed our sockets anyway; stop the reconnect loop
            location.href = '/login.html';
        },
    };

    // ───────────────────────────── Shared WebSocket transport ─────────────────────────────
    // One authenticated WebSocket per origin lives inside a SharedWorker (ws-shared.js), so
    // in-app navigation (chat.html ⇄ account.html) never drops or re-creates the socket.
    // Falls back to a per-page WebSocket where SharedWorker is unavailable. The transport
    // only moves frames: pages render exclusively what the SERVER sends (server echo +
    // history replay), so no frame is ever invented locally.
    const Ws = {
        mode: 'none',            // 'shared' | 'direct'
        worker: null,
        port: null,
        socket: null,
        handlers: new Map(),     // type -> Set<fn>
        status: 'connecting',
        _reconnectDelay: 1000,
        _reconnectTimer: null,
        _stopped: false,
        _pingTimer: null,
        _ackSeq: 0,
        _pendingAcks: new Map(), // send ref -> resolve(ok)

        _init() {
            if (this.mode !== 'none') return;
            if (typeof SharedWorker === 'function') {
                try {
                    this.worker = new SharedWorker('/ws-shared.js', 'onlinechat-ws');
                    this.port = this.worker.port;
                    this.port.addEventListener('message', (ev) => this._dispatch(ev.data));
                    this.port.start();
                    this.mode = 'shared';
                    // A worker 'error' (uncaught exception / load failure) must never leave two
                    // sockets alive: terminate the worker before falling back to a direct one.
                    this.worker.addEventListener('error', () => this._fallbackDirect());
                    return;
                } catch (_) { /* fall through to a direct socket */ }
            }
            this._startDirect();
        },

        _fallbackDirect() {
            if (this.worker) { try { this.worker.terminate(); } catch (_) {} }
            if (this.port) { try { this.port.close(); } catch (_) {} }
            this.worker = null;
            this.port = null;
            // Fail any outstanding send acks — their frames never left this page.
            for (const resolve of this._pendingAcks.values()) resolve(false);
            this._pendingAcks.clear();
            this._startDirect();
        },

        _startDirect() {
            this.mode = 'direct';
            this._connectDirect();
            if (!this._pingTimer) {
                this._pingTimer = setInterval(() => {
                    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
                        try { this.socket.send(JSON.stringify({ type: 'ping' })); } catch (_) {}
                    }
                }, 50000);
            }
        },

        _connectDirect() {
            const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
            const ws = new WebSocket(proto + '//' + location.host + '/ws');
            this.socket = ws;
            this._emit('ws_status', { type: 'ws_status', state: 'connecting' });
            ws.addEventListener('open', () => { this._reconnectDelay = 1000; });
            ws.addEventListener('message', (ev) => {
                let m;
                try { m = JSON.parse(ev.data); } catch (_) { return; }
                if (m && m.type === 'auth_ok') this._emit('ws_status', { type: 'ws_status', state: 'ok' });
                this._dispatch(m);
            });
            ws.addEventListener('close', () => {
                this.socket = null;
                this._emit('ws_status', { type: 'ws_status', state: this._stopped ? 'closed' : 'err' });
                if (!this._stopped) {
                    this._reconnectTimer = setTimeout(() => {
                        this._reconnectTimer = null;
                        if (this.mode === 'direct') this._connectDirect();
                    }, this._reconnectDelay);
                    this._reconnectDelay = Math.min(this._reconnectDelay * 2, 15000);
                }
            });
            ws.addEventListener('error', () => { /* a close event follows */ });
        },

        _dispatch(msg) {
            if (!msg) return;
            if (msg.type === 'send_ack') {
                const resolve = this._pendingAcks.get(msg.ref);
                if (resolve) { this._pendingAcks.delete(msg.ref); resolve(!!msg.ok); }
                return;
            }
            if (msg.type === 'ws_status') this.status = msg.state;
            this._emit(msg.type, msg);
        },

        _emit(type, msg) {
            const set = this.handlers.get(type);
            if (set) for (const fn of [...set]) { try { fn(msg); } catch (_) {} }
        },

        /** Registers a handler for a server frame type (or 'ws_status'). Returns an unsubscribe fn. */
        on(type, fn) {
            // Register BEFORE initialising: the shared worker may push cached frames
            // (auth_ok / history) back-to-back with the connection, and a handler that is
            // added after _init() could theoretically miss the first frames.
            if (!this.handlers.has(type)) this.handlers.set(type, new Set());
            this.handlers.get(type).add(fn);
            this._init();
            return () => this.off(type, fn);
        },
        off(type, fn) {
            const set = this.handlers.get(type);
            if (set) set.delete(fn);
        },

        /**
         * Forwards a WebSocket frame. Resolves to true only when the transport really accepted
         * the frame (the worker acknowledges synchronously, and the direct socket reports the
         * send itself). Callers NEVER render from this promise — the server echo is the only
         * thing that renders a message.
         */
        send(payload) {
            this._init();
            if (this.mode === 'shared' && this.port) {
                const ref = ++this._ackSeq;
                return new Promise((resolve) => {
                    const timer = setTimeout(() => {
                        this._pendingAcks.delete(ref);
                        resolve(false);
                    }, 3000);
                    this._pendingAcks.set(ref, (ok) => { clearTimeout(timer); resolve(ok); });
                    try {
                        this.port.postMessage({ type: 'send', ref, payload });
                    } catch (_) {
                        clearTimeout(timer);
                        this._pendingAcks.delete(ref);
                        resolve(false);
                    }
                });
            }
            if (this.mode === 'direct' && this.socket && this.socket.readyState === WebSocket.OPEN) {
                try { this.socket.send(JSON.stringify(payload)); return Promise.resolve(true); } catch (_) { return Promise.resolve(false); }
            }
            return Promise.resolve(false);
        },

        /** After a successful login the cookie changed — tell the shared worker to reconnect. */
        resume() {
            this._init();
            this._stopped = false;
            this._reconnectDelay = 1000;
            if (this.port) {
                try { this.port.postMessage({ type: 'resume' }); } catch (_) {}
            } else if (this.mode === 'direct' && (!this.socket || this.socket.readyState === WebSocket.CLOSED)) {
                this._connectDirect();
            }
        },

        /** Logout / force_logout: close the socket and stop any reconnect loop. */
        stop() {
            this._stopped = true;
            if (this._reconnectTimer) { clearTimeout(this._reconnectTimer); this._reconnectTimer = null; }
            if (this.port) { try { this.port.postMessage({ type: 'stop' }); } catch (_) {} }
            if (this.socket) { try { this.socket.close(); } catch (_) {} }
        },
    };

    // ───────────────────────────── Smooth in-app page transitions ─────────────────────────────
    // Intercept same-origin navigation links: fade the current page out, then navigate — the
    // target page already fades in, giving a cross-fade between pages. Combined with the shared
    // WebSocket this makes in-app navigation feel seamless.
    function smoothNav() {
        document.addEventListener('click', (e) => {
            if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
            const a = e.target.closest('a[href]');
            if (!a || a.target === '_blank' || a.hasAttribute('download')) return;
            const href = a.getAttribute('href');
            if (!href || href.startsWith('#') || href.startsWith('?')) return;
            let dest;
            try { dest = new URL(href, location.href); } catch (_) { return; }
            if (dest.origin !== location.origin) return;
            if (dest.pathname === location.pathname && dest.search === location.search) return;
            e.preventDefault();
            document.documentElement.classList.add('page-leave');
            setTimeout(() => { location.assign(dest.href); }, 160);
        });
        // Back/forward via bfcache: make sure the page is fully visible again.
        window.addEventListener('pageshow', () => document.documentElement.classList.remove('page-leave'));
    }

    // ───────────────────────────── Nav ─────────────────────────────
    function renderNav(activePage, user) {
        const nav = document.querySelector('.nav');
        if (!nav) return;
        const initial = (user && user.username ? user.username[0] : '?').toUpperCase();
        const langOptions = SUPPORTED_LOCALES.map(l =>
            `<option value="${l}" ${l === I18N.locale ? 'selected' : ''}>${l === 'en' ? 'English' : '中文'}</option>`
        ).join('');
        nav.innerHTML = `
            <div class="brand">
                <div class="brand-mark">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                    </svg>
                </div>
                <span data-i18n="app.name">Online Chat</span>
            </div>
            ${user ? `
            <div class="links">
                <a href="/chat.html" data-page="chat" data-i18n="nav.chat">Chat</a>
                <a href="/account.html" data-page="account" data-i18n="nav.account">Account</a>
            </div>` : ''}
            <div class="spacer"></div>
            <label class="lang" title="Language">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="10"/><path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
                </svg>
                <select id="lang-select">${langOptions}</select>
            </label>
            ${user ? `
            <div class="who">
                <div class="avatar">${initial}</div>
                <span>${escapeHtml(user.username)}</span>
            </div>
            <button class="btn ghost" id="nav-logout" data-i18n="nav.logout">Log out</button>
            ` : ''}
        `;
        if (activePage) {
            const a = nav.querySelector(`[data-page="${activePage}"]`);
            if (a) a.classList.add('active');
        }
        const sel = nav.querySelector('#lang-select');
        if (sel) sel.addEventListener('change', () => I18N.setLocale(sel.value));
        const out = nav.querySelector('#nav-logout');
        if (out) out.addEventListener('click', () => Auth.logout());
        I18N.apply(nav);
    }

    // ───────────────────────────── Utils ─────────────────────────────
    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, c =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    function fmtTime(ts) {
        const d = new Date(ts || Date.now());
        return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }

    /** "Today" / "Yesterday" / a short locale date — used for chat date separators and account info. */
    function fmtDate(ts) {
        const d = new Date(ts || Date.now());
        const startOf = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
        const diffDays = Math.round((startOf(new Date()) - startOf(d)) / 86400000);
        if (diffDays === 0) {
            const today = I18N.t('date.today');
            return today === 'date.today' ? 'Today' : today;
        }
        if (diffDays === 1) {
            const yesterday = I18N.t('date.yesterday');
            return yesterday === 'date.yesterday' ? 'Yesterday' : yesterday;
        }
        return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
    }

    /**
     * Escapes chat text and turns http(s):// links into real anchors.
     * Returns a DocumentFragment — safe to append to any element.
     */
    function renderText(text) {
        const frag = document.createDocumentFragment();
        const s = String(text == null ? '' : text);
        const parts = s.split(/(https?:\/\/[^\s]+)/g);
        for (const part of parts) {
            if (!part) continue;
            if (/^https?:\/\//i.test(part)) {
                const url = part.replace(/[.,;:!?)\]}>]+$/, '');
                const a = document.createElement('a');
                a.href = url;
                a.target = '_blank';
                a.rel = 'noopener noreferrer';
                a.textContent = url;
                a.className = 'autolink';
                frag.appendChild(a);
            } else {
                frag.appendChild(document.createTextNode(part));
            }
        }
        return frag;
    }

    /** Copies text to the clipboard; returns true on success. Falls back to execCommand for older browsers. */
    async function copyText(text) {
        try {
            await navigator.clipboard.writeText(String(text));
            return true;
        } catch (_) {
            try {
                const ta = document.createElement('textarea');
                ta.value = String(text);
                ta.style.position = 'fixed';
                ta.style.opacity = '0';
                document.body.appendChild(ta);
                ta.select();
                const ok = document.execCommand('copy');
                ta.remove();
                return ok;
            } catch (_) {
                return false;
            }
        }
    }

    function debounce(fn, ms) {
        let t = null;
        return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
    }

    // ───────────────────────────── Sound (opt-in "new message" blip) ─────────────────────────────
    const Sound = {
        ctx: null,
        enabled() { return localStorage.getItem('oc.sound') === '1'; },
        setEnabled(v) { localStorage.setItem('oc.sound', v ? '1' : '0'); },
        _ensure() {
            if (!this.ctx) {
                const AC = window.AudioContext || window.webkitAudioContext;
                if (!AC) return null;
                this.ctx = new AC();
            }
            if (this.ctx.state === 'suspended') this.ctx.resume().catch(() => {});
            return this.ctx;
        },
        /** Short, quiet two-tone blip. Only plays when the user opted in. */
        beep() {
            if (!this.enabled()) return;
            try {
                const ctx = this._ensure();
                if (!ctx) return;
                const now = ctx.currentTime;
                const osc = ctx.createOscillator();
                const gain = ctx.createGain();
                osc.type = 'sine';
                osc.frequency.setValueAtTime(740, now);
                osc.frequency.setValueAtTime(988, now + 0.07);
                gain.gain.setValueAtTime(0.0001, now);
                gain.gain.exponentialRampToValueAtTime(0.07, now + 0.012);
                gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.16);
                osc.connect(gain);
                gain.connect(ctx.destination);
                osc.start(now);
                osc.stop(now + 0.17);
            } catch (_) {}
        },
        /** Call from a user gesture so the AudioContext is allowed to start. */
        unlock() {
            if (!this.enabled()) return;
            this._ensure();
        },
    };

    // ───────────────────────────── Unread count in the page title ─────────────────────────────
    const TitleBadge = {
        n: 0,
        base: null,
        incr() { this.n++; this.apply(); },
        reset() { this.n = 0; this.apply(); },
        apply() {
            if (this.base == null) this.base = document.title;
            document.title = this.n > 0 ? `(${this.n}) ${this.base}` : this.base;
        },
    };

    // ───────────────────────────── Password visibility toggles ─────────────────────────────
    // Buttons carry data-pw-toggle="#selector"; they swap the input between type=password and text.
    function initPasswordToggles(scope) {
        const root = scope || document;
        root.querySelectorAll('[data-pw-toggle]').forEach(btn => {
            if (btn.dataset.pwBound) return;
            btn.dataset.pwBound = '1';
            const input = document.querySelector(btn.dataset.pwToggle);
            if (!input) return;
            btn.addEventListener('click', () => {
                const show = input.type === 'password';
                input.type = show ? 'text' : 'password';
                btn.classList.toggle('on', show);
                btn.title = I18N.t(show ? 'login.hidePassword' : 'login.showPassword');
                input.focus();
            });
        });
    }

    function setLoading(btn, loading) {
        if (!btn) return;
        btn.classList.toggle('loading', !!loading);
        btn.disabled = !!loading;
    }

    function ensureSpinner(btn) {
        if (!btn || btn.querySelector('.spinner')) return;
        const sp = document.createElement('span');
        sp.className = 'spinner';
        btn.prepend(sp);
        const label = document.createElement('span');
        label.className = 'label';
        while (btn.firstChild && btn.firstChild !== sp) label.appendChild(btn.firstChild);
        // Move remaining children (text nodes) into the label.
        const kids = Array.from(btn.childNodes).filter(n => n !== sp);
        kids.forEach(k => label.appendChild(k));
        btn.appendChild(label);
    }

    global.OC = {
        API, I18N, Toast, Modal, Auth, renderNav,
        escapeHtml, fmtTime, fmtDate, renderText, copyText, debounce,
        Sound, TitleBadge, initPasswordToggles,
        Ws, smoothNav,
        setLoading, ensureSpinner, SUPPORTED_LOCALES,
    };

    // Smooth page transitions are opt-out only via the reduced-motion media query in the CSS.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', smoothNav);
    } else {
        smoothNav();
    }
})(window);
