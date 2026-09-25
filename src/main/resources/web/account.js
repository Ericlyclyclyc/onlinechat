/* Account page: Minecraft binding, two-factor sign-in, password change and account deletion. */
(async function () {
    'use strict';
    await OC.I18N.load();
    OC.initPasswordToggles();
    const me = await OC.Auth.requireAuth('/login.html');
    if (!me) return;
    OC.renderNav('account', me);

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

    const twofaRow = document.getElementById('twofa-row');
    const twofaToggle = document.getElementById('twofa-toggle');
    const twofaUnavailable = document.getElementById('twofa-unavailable');
    const twofaNeedBind = document.getElementById('twofa-needbind');

    const formPassword = document.getElementById('form-password');
    const pwSubmit = document.getElementById('pw-submit');
    const formDelete = document.getElementById('form-delete');
    const delSubmit = document.getElementById('del-submit');

    let selfPasswordChange = false; // we rotated our own password: the resulting force_logout is expected
    let pendingUntil = 0;
    let pendingTicker = null;
    let resultPoller = null;    // HTTP fallback poller: completes the UI if the WS bind_ok never arrives
    let pendingActive = false;  // true while the countdown section is showing
    let requestedMc = '';       // lowercased MC name we asked to bind to
    let startBoundUuid = null;  // bound UUID when the request started (prevents rebind-to-same false positive)
    let minPasswordLength = 6;

    // ───────────── Account info display ─────────────
    function renderInfo(acc) {
        if (!acc) return;
        document.getElementById('info-username').textContent = acc.username;
        document.getElementById('acc-joined').textContent = acc.createdAt ? OC.fmtDate(acc.createdAt) : '—';
        document.getElementById('acc-lastlogin').textContent = acc.lastLoginAt
            ? `${OC.fmtDate(acc.lastLoginAt)} · ${OC.fmtTime(acc.lastLoginAt)}` : '—';
    }

    // ───────────── Current binding display ─────────────
    function renderCurrent(acc) {
        if (!acc || !acc.bound) {
            currentBody.innerHTML = `<p class="text-mute small">${OC.escapeHtml(OC.I18N.t('bind.notBound'))}</p>`;
            unbindBtn.classList.add('hidden');
            if (!pendingActive) sectionRequest.classList.remove('hidden');
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
                <span class="v row" style="gap:6px;align-items:center;justify-content:flex-end;min-width:0">
                    <span style="font-family:var(--mono);font-size:11px;overflow:hidden;text-overflow:ellipsis">${OC.escapeHtml(acc.mcUuid || '')}</span>
                    <button class="icon-btn mini" type="button" id="copy-uuid" data-i18n-title="account.copyUuid"
                            title="Copy UUID" aria-label="Copy UUID">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    </button>
                </span></div>
            <div class="kv"><span class="k">${OC.escapeHtml(OC.I18N.t('bind.status.mcOnline'))}</span>
                <span class="v">${onlineBadge}</span></div>
        `;
        const copyBtn = currentBody.querySelector('#copy-uuid');
        if (copyBtn) copyBtn.addEventListener('click', async () => {
            if (await OC.copyText(acc.mcUuid || '')) OC.Toast.ok(OC.I18N.t('chat.copied'));
        });
    }

    // ───────────── Two-factor switch ─────────────
    function renderTwoFactor(acc) {
        const available = !!(acc && acc.twoFactorAvailable);
        const bound = !!(acc && acc.bound);
        twofaUnavailable.classList.toggle('hidden', available);
        twofaNeedBind.classList.toggle('hidden', !available || bound);
        twofaRow.classList.toggle('disabled', !available || !bound);
        twofaToggle.disabled = !available || !bound;
        twofaToggle.checked = !!(acc && acc.twoFactor);
    }

    function renderAll(acc) {
        renderInfo(acc);
        renderCurrent(acc);
        renderTwoFactor(acc);
    }
    renderAll(me);

    // Refresh the binding periodically so mcOnline stays fresh.
    async function refreshMe() {
        const acc = await OC.Auth.me();
        if (acc) { Object.assign(me, acc); renderAll(acc); }
    }
    setInterval(refreshMe, 15000);

    // ───────────── Shared WebSocket for live bind events ─────────────
    // The socket is owned by OC.Ws (SharedWorker or direct fallback), so navigating away from
    // this page and back never drops the connection or re-spams connect/disconnect messages.
    OC.Ws.on('bind_pending', onPending);
    OC.Ws.on('bind_ok', onBindOk);
    OC.Ws.on('bind_denied', onBindDenied);
    OC.Ws.on('bind_expired', onBindExpired);
    OC.Ws.on('bind_error', (msg) => OC.Toast.err(msg.error || OC.I18N.t('bind.err.failed')));
    OC.Ws.on('unbind_ok', onUnbindOk);
    OC.Ws.on('auth_error', (msg) => OC.Toast.err(msg.error || OC.I18N.t('error.unauthorized')));
    OC.Ws.on('force_logout', (msg) => {
        // Our own password change also produces this frame; the HTTP reply already handed us a
        // fresh cookie and OC.Ws.resume() re-arms the socket, so just ignore it here.
        if (msg.reason === 'password_changed' && selfPasswordChange) {
            selfPasswordChange = false;
            return;
        }
        OC.Ws.stop();
        OC.Toast.warn(OC.I18N.t('chat.forceLogout'), 6500);
        setTimeout(() => location.replace('/login.html?reason=' + encodeURIComponent(msg.reason || 'kicked')), 1500);
    });
    OC.Ws.on('server_shutdown', () => {
        // The Minecraft server is stopping: halt any pending bind timers, notify; OC.Ws stops reconnecting.
        if (pendingTicker) { clearInterval(pendingTicker); pendingTicker = null; }
        if (resultPoller) { clearInterval(resultPoller); resultPoller = null; }
        OC.Modal.alert({
            tone: 'warn',
            icon: OC.Modal.POWER_ICON,
            title: OC.I18N.t('shutdown.title'),
            message: OC.I18N.t('shutdown.body'),
            buttonText: OC.I18N.t('shutdown.ok'),
        });
    });

    function showPending() {
        pendingActive = true;
        sectionRequest.classList.add('hidden');
        sectionPending.classList.remove('hidden');
    }
    function hidePending() {
        pendingActive = false;
        sectionPending.classList.add('hidden');
        if (!me.bound) sectionRequest.classList.remove('hidden');
        if (pendingTicker) { clearInterval(pendingTicker); pendingTicker = null; }
        if (resultPoller) { clearInterval(resultPoller); resultPoller = null; }
    }

    function onPending(msg) {
        pendingUntil = msg.expiresAt || (Date.now() + 120000);
        pendingCode.textContent = msg.code || '------';
        requestedMc = String(msg.mcName || mcNameInput.value || '').trim().toLowerCase();
        startBoundUuid = (me && me.bound) ? (me.mcUuid || null) : null;
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
        // Fallback: the result is normally pushed over WebSocket (bind_ok). If that socket is down
        // (e.g. an untrusted-cert wss:// handshake keeps failing) the countdown would never stop, so
        // also poll /api/me and complete the UI as soon as the player confirms in game.
        if (resultPoller) clearInterval(resultPoller);
        resultPoller = setInterval(pollBindResult, 2000);
        pollBindResult();
    }

    async function pollBindResult() {
        if (!pendingActive) return;
        let acc;
        try { acc = await OC.Auth.me(); } catch (_) { return; }
        if (!pendingActive || !acc || !acc.bound) return;
        const matchesRequested = requestedMc && acc.mcName && acc.mcName.toLowerCase() === requestedMc;
        const changed = startBoundUuid === null || acc.mcUuid !== startBoundUuid;
        if (matchesRequested && changed) onBindOk({ mcName: acc.mcName });
    }

    async function onBindOk(msg) {
        if (!pendingActive) return;   // already completed (WebSocket push vs. HTTP poll race)
        hidePending();                // clears the ticker + poller and flips pendingActive off
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
        // Best-effort: tell the server to drop the pending code (no-op when the socket is busy).
        OC.Ws.send({ type: 'unbind' });
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

    // ───────────── Two-factor toggle ─────────────
    twofaToggle.addEventListener('change', async () => {
        const enabled = twofaToggle.checked;
        if (enabled && !confirm(OC.I18N.t('account.twofa.confirmOn'))) {
            twofaToggle.checked = false;
            return;
        }
        twofaToggle.disabled = true;
        try {
            const { ok, body } = await OC.API.post('/api/2fa/toggle', { enabled });
            if (ok) {
                me.twoFactor = !!body.twoFactor;
                OC.Toast.ok(OC.I18N.t(me.twoFactor ? 'account.twofa.on' : 'account.twofa.off'));
            } else {
                OC.Toast.err(body.error || OC.I18N.t('error.generic'));
            }
        } catch (_) {
            OC.Toast.err(OC.I18N.t('error.network'));
        } finally {
            await refreshMe();
        }
    });

    // ───────────── Password change ─────────────
    try {
        const { ok, body } = await OC.API.get('/api/status');
        if (ok && body.style && body.style.minPasswordLength) {
            minPasswordLength = body.style.minPasswordLength;
            document.getElementById('pw-hint').textContent = OC.I18N.t('login.password.hint', { n: minPasswordLength });
            document.getElementById('pw-next').minLength = minPasswordLength;
            document.getElementById('pw-next2').minLength = minPasswordLength;
        }
    } catch (_) {}

    formPassword.addEventListener('submit', async (e) => {
        e.preventDefault();
        const current = document.getElementById('pw-current').value;
        const next = document.getElementById('pw-next').value;
        const next2 = document.getElementById('pw-next2').value;
        if (!current || !next) return;
        if (next !== next2) { OC.Toast.err(OC.I18N.t('login.err.mismatch')); return; }
        if (next.length < minPasswordLength) { OC.Toast.err(OC.I18N.t('login.password.hint', { n: minPasswordLength })); return; }
        OC.setLoading(pwSubmit, true);
        selfPasswordChange = true;
        try {
            const { ok, body } = await OC.API.post('/api/account/password', { current, next });
            if (ok) {
                OC.Toast.ok(OC.I18N.t('account.password.ok'));
                formPassword.reset();
                // The server bumped lastLoginAt (invalidating the old token) and closed the shared
                // socket with a force_logout frame; the reply carried a fresh cookie, so re-arm
                // the transport to reconnect and authenticate with it.
                OC.Ws.resume();
            } else {
                selfPasswordChange = false;
                OC.Toast.err(body.error || OC.I18N.t('account.password.err'));
            }
        } catch (_) {
            selfPasswordChange = false;
            OC.Toast.err(OC.I18N.t('error.network'));
        } finally {
            OC.setLoading(pwSubmit, false);
        }
    });

    // ───────────── Delete account ─────────────
    formDelete.addEventListener('submit', async (e) => {
        e.preventDefault();
        const password = document.getElementById('del-password').value;
        if (!password) return;
        if (!confirm(OC.I18N.t('account.delete.confirm', { name: me.username }))) return;
        OC.setLoading(delSubmit, true);
        try {
            const { ok, body } = await OC.API.post('/api/account/delete', { password });
            if (ok) {
                OC.Ws.stop();   // the server closed our sockets anyway; stop the reconnect loop
                OC.Toast.ok(OC.I18N.t('account.delete.ok'));
                setTimeout(() => location.replace('/login.html?reason=account_deleted'), 1200);
            } else {
                OC.Toast.err(body.error || OC.I18N.t('error.generic'));
                OC.setLoading(delSubmit, false);
            }
        } catch (_) {
            OC.Toast.err(OC.I18N.t('error.network'));
            OC.setLoading(delSubmit, false);
        }
    });
})();
