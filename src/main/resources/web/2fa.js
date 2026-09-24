/* Two-factor confirmation page, served for /2fa/auth/<token>.
 * Flow: read the token from the URL → GET /api/2fa/info → if not signed in, bounce through login and
 * come back → show who is waiting → the user approves (player released) or rejects (player kicked).
 */
(async function () {
    'use strict';
    await OC.I18N.load();

    const token = decodeURIComponent(location.pathname.replace(/^\/2fa\/auth\/?/, '')).trim();

    const stLoading = document.getElementById('state-loading');
    const stConfirm = document.getElementById('state-confirm');
    const stResult = document.getElementById('state-result');
    const playerEl = document.getElementById('tf-player');
    const accountEl = document.getElementById('tf-account');
    const progress = document.getElementById('tf-progress');
    const timerEl = document.getElementById('tf-timer');
    const approveBtn = document.getElementById('tf-approve');
    const rejectBtn = document.getElementById('tf-reject');
    const resultBox = document.getElementById('tf-result');
    const resultText = document.getElementById('tf-result-text');
    const resultActions = document.getElementById('tf-result-actions');

    let ticker = null;
    let startedAt = Date.now();

    function show(state) {
        stLoading.classList.toggle('hidden', state !== 'loading');
        stConfirm.classList.toggle('hidden', state !== 'confirm');
        stResult.classList.toggle('hidden', state !== 'result');
    }

    function finish(tone, text, links) {
        if (ticker) { clearInterval(ticker); ticker = null; }
        resultBox.classList.remove('ok', 'err');
        if (tone) resultBox.classList.add(tone);
        resultText.textContent = text;
        resultActions.innerHTML = '';
        (links || []).forEach(l => {
            const a = document.createElement('a');
            a.className = 'btn ' + (l.primary ? 'primary' : 'ghost');
            a.href = l.href;
            a.textContent = l.text;
            resultActions.appendChild(a);
        });
        show('result');
    }

    const linkChat = { href: '/chat.html', text: OC.I18N.t('bind.goChat') };
    const linkAccount = { href: '/account.html', text: OC.I18N.t('nav.account') };

    if (!token) {
        OC.renderNav(null, null);
        finish('err', OC.I18N.t('twofa.err.invalid'), [linkChat]);
        return;
    }

    let info;
    try {
        const { ok, body } = await OC.API.get('/api/2fa/info?token=' + encodeURIComponent(token));
        if (!ok) throw new Error('http');
        info = body;
    } catch (_) {
        OC.renderNav(null, null);
        finish('err', OC.I18N.t('error.network'), []);
        return;
    }

    if (!info.available) {
        OC.renderNav(null, null);
        finish('err', OC.I18N.t('twofa.err.unavailable'), [linkChat]);
        return;
    }

    // Not signed in: the cookie is the second factor, so go get one and come straight back here.
    if (!info.authenticated) {
        location.replace('/login.html?next=' + encodeURIComponent(location.pathname));
        return;
    }
    OC.renderNav(null, { username: info.username });

    if (!info.valid) {
        finish('err', OC.I18N.t('twofa.err.invalid'), [linkChat]);
        return;
    }

    playerEl.textContent = info.playerName || '?';
    accountEl.textContent = info.username || '?';

    // A pending request that belongs to somebody else: this browser cannot approve it. The server will
    // kick the player if we try, so make that explicit and only offer the reject path.
    if (!info.matches) {
        finish('err', OC.I18N.t('twofa.err.mismatch', { player: info.playerName || '?', user: info.username || '?' }), [linkAccount]);
        return;
    }

    show('confirm');
    const deadline = info.expiresAt || (Date.now() + 120000);
    const total = Math.max(1, deadline - startedAt);
    ticker = setInterval(() => {
        const left = Math.max(0, deadline - Date.now());
        progress.value = Math.round((left / total) * 100);
        timerEl.textContent = OC.I18N.t('bind.expiresIn', { s: Math.ceil(left / 1000) });
        if (left <= 0) finish('err', OC.I18N.t('twofa.err.expired'), [linkChat]);
    }, 500);

    approveBtn.addEventListener('click', async () => {
        OC.setLoading(approveBtn, true);
        rejectBtn.disabled = true;
        try {
            const { ok, status, body } = await OC.API.post('/api/2fa/verify', { token });
            if (ok) {
                finish('ok', OC.I18N.t('twofa.ok', { player: info.playerName || '' }), [linkChat]);
            } else if (status === 403) {
                finish('err', OC.I18N.t('twofa.err.wrongAccount'), [linkAccount]);
            } else if (status === 404) {
                finish('err', OC.I18N.t('twofa.err.expired'), [linkChat]);
            } else if (status === 401) {
                location.replace('/login.html?next=' + encodeURIComponent(location.pathname));
            } else {
                finish('err', (body && body.error) || OC.I18N.t('error.generic'), [linkChat]);
            }
        } catch (_) {
            OC.Toast.err(OC.I18N.t('error.network'));
            OC.setLoading(approveBtn, false);
            rejectBtn.disabled = false;
        }
    });

    rejectBtn.addEventListener('click', async () => {
        if (!confirm(OC.I18N.t('twofa.reject.confirm', { player: info.playerName || '' }))) return;
        OC.setLoading(rejectBtn, true);
        approveBtn.disabled = true;
        try {
            const { ok, body } = await OC.API.post('/api/2fa/reject', { token });
            if (ok) {
                finish('ok', OC.I18N.t('twofa.rejected', { player: info.playerName || '' }), [linkAccount]);
            } else {
                finish('err', (body && body.error) || OC.I18N.t('twofa.err.expired'), [linkChat]);
            }
        } catch (_) {
            OC.Toast.err(OC.I18N.t('error.network'));
            OC.setLoading(rejectBtn, false);
            approveBtn.disabled = false;
        }
    });
})();
