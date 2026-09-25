/* Login / register page logic. */
(async function () {
    'use strict';
    await OC.I18N.load();
    OC.initPasswordToggles();

    const params = new URLSearchParams(location.search);
    // Any force-logout reason means our cookie is stale: kicked/login_elsewhere, password_changed, account_deleted.
    const reason = params.get('reason');
    const kicked = !!reason;
    const reasonKey = reason === 'password_changed' ? 'login.reason.passwordChanged'
        : reason === 'account_deleted' ? 'login.reason.accountDeleted'
        : 'login.kicked';

    // If already signed in, skip straight to chat - unless we were just force-logged-out here, in
    // which case a stale (now-invalid) cookie must not bounce the user back into the app.
    if (!kicked) {
        const existing = await OC.Auth.me();
        if (existing) {
            location.replace(params.get('next') || '/chat.html');
            return;
        }
    }
    OC.renderNav(null, null);
    if (kicked) OC.Toast.warn(OC.I18N.t(reasonKey), 7000);

    // Fetch server status (also tells us whether registration is open).
    let registrationOpen = true;
    try {
        const { ok, body } = await OC.API.get('/api/status');
        const statusEl = document.getElementById('status');
        const statusText = document.getElementById('status-text');
        if (ok) {
            statusEl.classList.add('ok');
            statusText.textContent = OC.I18N.t('login.serverStatus', {
                motd: body.motd || '-',
                online: body.onlinePlayers || 0,
                max: body.maxPlayers || 0,
                web: body.onlineWeb || 0,
            });
            registrationOpen = !!body.registration;
        } else {
            statusEl.classList.add('err');
        }
    } catch (_) {
        document.getElementById('status').classList.add('err');
    }
    if (!registrationOpen) {
        document.getElementById('tab-register').disabled = true;
        document.getElementById('tab-register').style.opacity = '.45';
        document.getElementById('rg-closed').classList.remove('hidden');
    }

    // Tab switching
    const tabLogin = document.getElementById('tab-login');
    const tabRegister = document.getElementById('tab-register');
    const formLogin = document.getElementById('form-login');
    const formRegister = document.getElementById('form-register');
    function showTab(which) {
        const login = which === 'login';
        tabLogin.classList.toggle('active', login);
        tabRegister.classList.toggle('active', !login);
        formLogin.classList.toggle('hidden', !login);
        formRegister.classList.toggle('hidden', login);
    }
    tabLogin.addEventListener('click', () => showTab('login'));
    tabRegister.addEventListener('click', () => { if (registrationOpen) showTab('register'); });

    // Password hint with configured min length
    try {
        const { ok, body } = await OC.API.get('/api/status');
        if (ok && body.style && body.style.minPasswordLength) {
            document.getElementById('rg-pass-hint').textContent =
                OC.I18N.t('login.password.hint', { n: body.style.minPasswordLength });
            document.getElementById('rg-pass').minLength = body.style.minPasswordLength;
            document.getElementById('rg-pass2').minLength = body.style.minPasswordLength;
        }
    } catch (_) {}

    function redirectAfterAuth() {
        const next = new URLSearchParams(location.search).get('next') || '/chat.html';
        location.href = next;
    }

    // Sign-in
    formLogin.addEventListener('submit', async (e) => {
        e.preventDefault();
        const btn = document.getElementById('li-submit');
        const username = document.getElementById('li-user').value.trim();
        const password = document.getElementById('li-pass').value;
        if (!username || !password) return;
        OC.setLoading(btn, true);
        try {
            const { ok, body } = await OC.API.post('/api/login', { username, password });
            if (ok) {
                OC.Toast.ok(OC.I18N.t('login.title') + ' — ' + body.username);
                // Fresh cookie: re-arm the shared WebSocket so the chat page is already
                // authenticated when it loads.
                OC.Ws.resume();
                setTimeout(redirectAfterAuth, 350);
            } else {
                OC.Toast.err(body.error || OC.I18N.t('login.err.failed'));
                OC.setLoading(btn, false);
            }
        } catch (_) {
            OC.Toast.err(OC.I18N.t('error.network'));
            OC.setLoading(btn, false);
        }
    });

    // Register
    formRegister.addEventListener('submit', async (e) => {
        e.preventDefault();
        const btn = document.getElementById('rg-submit');
        const username = document.getElementById('rg-user').value.trim();
        const password = document.getElementById('rg-pass').value;
        const password2 = document.getElementById('rg-pass2').value;
        if (password !== password2) {
            OC.Toast.err(OC.I18N.t('login.err.mismatch'));
            return;
        }
        if (!username || !password) return;
        OC.setLoading(btn, true);
        try {
            const { ok, body } = await OC.API.post('/api/register', { username, password });
            if (ok) {
                OC.Toast.ok(OC.I18N.t('login.ok.registered'));
                OC.Ws.resume();
                setTimeout(redirectAfterAuth, 500);
            } else {
                OC.Toast.err(body.error || OC.I18N.t('login.err.register'));
                OC.setLoading(btn, false);
            }
        } catch (_) {
            OC.Toast.err(OC.I18N.t('error.network'));
            OC.setLoading(btn, false);
        }
    });
})();
