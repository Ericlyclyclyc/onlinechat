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
            location.href = '/login.html';
        },
    };

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
                <a href="/bind.html" data-page="bind" data-i18n="nav.bind">Bind</a>
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

    global.OC = { API, I18N, Toast, Auth, renderNav, escapeHtml, fmtTime, setLoading, ensureSpinner, SUPPORTED_LOCALES };
})(window);
