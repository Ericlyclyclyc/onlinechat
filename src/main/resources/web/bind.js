/* Bind page: request a binding, listen for the in-game confirmation over WebSocket. */
(async function () {
    'use strict';
    await OC.I18N.load();
    const me = await OC.Auth.requireAuth('/login.html');
    if (!me) return;
    OC.renderNav('bind', me);

    const sectionRequest = document.getElementById('section-request');
    const sectionPending = document.getElementById('section-pending');
    const currentBody = document.getElementById('current-body');
    const unbindBtn = document.getElementById('unbind-btn');
    const form = document.getElementById('form-bind');
    const submitBtn = document.getElementById('bind-submit');
    const mcNameInput = document.getElementById('mc-name');
    const pendingCode = document.getElementById('pending-code');
    const pendingProgress = document.getElementById('pending-progress');
    const pendingTimer = document.getElementById('pending-timer');
    const cancelBtn = document.getElementById('cancel-bind');

    let ws = null;
    let pendingUntil = 0;
    let pendingTicker = null;

    // ───────────── Current binding display ─────────────
    function renderCurrent(acc) {
        if (!acc || !acc.bound) {
            currentBody.innerHTML = `<p class="text-mute small">${OC.escapeHtml(OC.I18N.t('bind.notBound'))}</p>`;
            unbindBtn.classList.add('hidden');
            sectionRequest.classList.remove('hidden');
            return;
        }
        unbindBtn.classList.remove('hidden');
        sectionRequest.classList.add('hidden');
        const onlineBadge = acc.mcOnline
            ? `<span class="badge ok"><span class="dot"></span>${OC.escapeHtml(OC.I18N.t('bind.status.online'))}</span>`
            : `<span class="badge"><span class="dot"></span>${OC.escapeHtml(OC.I18N.t('bind.status.offline'))}</span>`;
        currentBody.innerHTML = `
            <div class="kv"><span class="k">${OC.escapeHtml(OC.I18N.t('bind.status.boundTo'))}</span>
                <span class="v">${OC.escapeHtml(acc.mcName || '?')}</span></div>
            <div class="kv"><span class="k">${OC.escapeHtml(OC.I18N.t('bind.status.mcUuid'))}</span>
                <span class="v" style="font-family:var(--mono);font-size:11px">${OC.escapeHtml(acc.mcUuid || '')}</span></div>
            <div class="kv"><span class="k">${OC.escapeHtml(OC.I18N.t('bind.status.mcOnline'))}</span>
                <span class="v">${onlineBadge}</span></div>
        `;
    }
    renderCurrent(me);

    // Refresh the binding periodically so mcOnline stays fresh.
    async function refreshMe() {
        const acc = await OC.Auth.me();
        if (acc) renderCurrent(acc);
    }
    setInterval(refreshMe, 15000);

    // ───────────── WebSocket for live bind events ─────────────
    function connect() {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(`${proto}//${location.host}/ws`);
        ws.addEventListener('message', (ev) => {
            let msg;
            try { msg = JSON.parse(ev.data); } catch (_) { return; }
            switch (msg.type) {
                case 'bind_pending': onPending(msg); break;
                case 'bind_ok':      onBindOk(msg); break;
                case 'bind_denied':  onBindDenied(msg); break;
                case 'bind_expired': onBindExpired(msg); break;
                case 'bind_error':   OC.Toast.err(msg.error || OC.I18N.t('bind.err.failed')); break;
                case 'unbind_ok':    onUnbindOk(); break;
                case 'auth_ok':      /* cookie auto-auth succeeded */ break;
                case 'auth_error':   OC.Toast.err(msg.error || OC.I18N.t('error.unauthorized')); break;
            }
        });
        ws.addEventListener('close', () => {
            // Reconnect after a short delay (page may still be open).
            setTimeout(() => { if (!ws || ws.readyState === WebSocket.CLOSED) connect(); }, 3000);
        });
    }
    connect();

    function showPending() {
        sectionRequest.classList.add('hidden');
        sectionPending.classList.remove('hidden');
    }
    function hidePending() {
        sectionPending.classList.add('hidden');
        sectionRequest.classList.remove('hidden');
        if (pendingTicker) { clearInterval(pendingTicker); pendingTicker = null; }
    }

    function onPending(msg) {
        pendingUntil = msg.expiresAt || (Date.now() + 120000);
        pendingCode.textContent = msg.code || '------';
        document.querySelector('#section-pending .callout strong').textContent =
            OC.I18N.t('bind.pending', { name: msg.mcName || '' });
        showPending();
        if (pendingTicker) clearInterval(pendingTicker);
        pendingTicker = setInterval(() => {
            const left = Math.max(0, pendingUntil - Date.now());
            const total = 120000;
            pendingProgress.value = Math.round((left / total) * 100);
            pendingTimer.textContent = OC.I18N.t('bind.expiresIn', { s: Math.ceil(left / 1000) });
            if (left <= 0) {
                clearInterval(pendingTicker); pendingTicker = null;
                hidePending();
                OC.Toast.warn(OC.I18N.t('bind.expired'));
            }
        }, 500);
    }

    async function onBindOk(msg) {
        if (pendingTicker) { clearInterval(pendingTicker); pendingTicker = null; }
        hidePending();
        OC.Toast.ok(OC.I18N.t('bind.ok', { name: (msg && msg.mcName) || mcNameInput.value }));
        await refreshMe();
    }
    function onBindDenied() {
        hidePending();
        OC.Toast.err(OC.I18N.t('bind.denied'));
    }
    function onBindExpired() {
        hidePending();
        OC.Toast.warn(OC.I18N.t('bind.expired'));
    }
    async function onUnbindOk() {
        OC.Toast.ok(OC.I18N.t('bind.unbind.ok'));
        await refreshMe();
    }

    // ───────────── Request binding ─────────────
    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const mcName = mcNameInput.value.trim();
        if (!mcName) return;
        OC.setLoading(submitBtn, true);
        try {
            const { ok, body } = await OC.API.post('/api/bind', { mcName });
            if (ok) {
                // Server accepted; the WebSocket will also push bind_pending, but handle HTTP too.
                onPending({ code: body.code, expiresAt: body.expiresAt, mcName: body.mcName || mcName });
            } else {
                const hint = body && body.hint ? ' — ' + body.hint : '';
                OC.Toast.err((body && body.error ? body.error : OC.I18N.t('bind.err.failed')) + hint);
            }
        } catch (_) {
            OC.Toast.err(OC.I18N.t('error.network'));
        } finally {
            OC.setLoading(submitBtn, false);
        }
    });

    cancelBtn.addEventListener('click', () => {
        hidePending();
        // Best-effort: tell the server to drop the pending code.
        if (ws && ws.readyState === WebSocket.OPEN) {
            try { ws.send(JSON.stringify({ type: 'unbind' })); } catch (_) {}
        }
    });

    unbindBtn.addEventListener('click', async () => {
        const acc = await OC.Auth.me();
        if (!acc || !acc.bound) return;
        if (!confirm(OC.I18N.t('bind.unbind.confirm', { name: acc.mcName }))) return;
        OC.setLoading(unbindBtn, true);
        try {
            const { ok, body } = await OC.API.post('/api/unbind');
            if (ok) {
                OC.Toast.ok(OC.I18N.t('bind.unbind.ok'));
                await refreshMe();
            } else {
                OC.Toast.err(body.error || OC.I18N.t('bind.err.failed'));
            }
        } catch (_) {
            OC.Toast.err(OC.I18N.t('error.network'));
        } finally {
            OC.setLoading(unbindBtn, false);
        }
    });
})();
