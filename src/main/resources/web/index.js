/* Landing page: check auth state and redirect accordingly. */
(async function () {
    'use strict';
    await OC.I18N.load();
    const me = await OC.Auth.me();
    OC.renderNav(null, me);

    // Show server status
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
        } else {
            statusEl.classList.add('err');
        }
    } catch (_) { /* leave default text */ }

    // Route based on auth state
    const params = new URLSearchParams(location.search);
    const next = params.get('next');
    if (me) {
        // Signed in — go to chat (or the original target)
        location.replace(next || '/chat.html');
    } else {
        // Not signed in — show login button as primary, hide chat
        document.getElementById('go-chat').classList.add('hidden');
        document.getElementById('go-login').textContent = OC.I18N.t('login.title');
        if (next) {
            document.getElementById('go-login').href = '/login.html?next=' + encodeURIComponent(next);
        }
    }
})();
