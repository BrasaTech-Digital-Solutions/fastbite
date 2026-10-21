/**
 * Kitchen Audio Chime Helper
 * Synthesizes chime alerts via the Web Audio API without requiring external audio assets.
 * Plays work alert on CREATED/ACCEPTED, and ready alert on DONE.
 * Deduplicates multiple orders in one poll to a single chime per tone.
 * Suppresses chimes for actions performed by this client screen (own actions).
 */
class KitchenChime {
    constructor() {
        this.storageKey = 'fastbite-kitchen-chime-enabled';
        this.audioCtx = null;
        this.enabled = localStorage.getItem(this.storageKey) === 'true';
        this.blocked = false;
        this.lastKnownStatus = new Map();
        this.ownActions = new Map();
        this.tapToEnableText = 'Tap to enable sound';
    }

    init() {
        const btn = document.getElementById('kitchenAudioToggleBtn');
        if (btn && btn.dataset && btn.dataset.tapToEnable) {
            this.tapToEnableText = btn.dataset.tapToEnable;
        }

        if (this.enabled) {
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            if (AudioContextClass) {
                try {
                    this.audioCtx = new AudioContextClass();
                    if (this.audioCtx.state === 'suspended') {
                        this.blocked = true;
                        this.setupUnlockListeners();
                    }
                } catch (e) {
                    console.warn('Unable to create AudioContext on init', e);
                }
            }
        }
        this.updateUi();
    }

    setupUnlockListeners() {
        const unlock = () => {
            if (this.audioCtx && this.audioCtx.state === 'suspended') {
                this.audioCtx.resume().then(() => {
                    this.blocked = false;
                    this.updateUi();
                }).catch(() => {});
            }
        };
        window.addEventListener('click', unlock, { once: true, passive: true });
        window.addEventListener('keydown', unlock, { once: true, passive: true });
        window.addEventListener('touchstart', unlock, { once: true, passive: true });
    }

    getAudioContext() {
        if (!this.audioCtx) {
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            if (AudioContextClass) {
                this.audioCtx = new AudioContextClass();
            }
        }
        if (this.audioCtx && this.audioCtx.state === 'suspended') {
            this.audioCtx.resume().catch(() => {});
        }
        return this.audioCtx;
    }

    toggle() {
        if (this.blocked) {
            const ctx = this.getAudioContext();
            if (ctx && ctx.state === 'suspended') {
                ctx.resume().then(() => {
                    this.blocked = false;
                    this.updateUi();
                    this.play('work');
                }).catch(() => {});
            } else {
                this.blocked = false;
                this.updateUi();
            }
            return this.enabled;
        }

        this.enabled = !this.enabled;
        localStorage.setItem(this.storageKey, this.enabled);
        if (this.enabled) {
            this.getAudioContext();
            this.updateUi();
            this.play('work');
        } else {
            this.updateUi();
        }
        return this.enabled;
    }

    updateUi() {
        const icon = document.getElementById('kitchenAudioIcon');
        const text = document.getElementById('kitchenAudioText');
        const btn = document.getElementById('kitchenAudioToggleBtn');
        if (!btn) return;

        if (this.blocked) {
            if (icon) icon.className = 'fas fa-volume-mute me-1 text-warning';
            if (text) text.textContent = this.tapToEnableText;
            btn.classList.remove('btn-warning-subtle');
            btn.classList.add('btn-warning');
        } else if (this.enabled) {
            if (icon) icon.className = 'fas fa-bell me-1 text-warning';
            if (text) text.textContent = 'Chime On';
            btn.classList.remove('btn-warning');
            btn.classList.add('btn-warning-subtle');
        } else {
            if (icon) icon.className = 'fas fa-bell-slash me-1';
            if (text) text.textContent = 'Chime Off';
            btn.classList.remove('btn-warning', 'btn-warning-subtle');
        }
    }

    expectOwn(id, expectedStatus) {
        if (!id || !expectedStatus) return;
        const key = String(id);
        const existing = this.ownActions.get(key);
        const statuses = existing && existing.expiresAt > Date.now() ? existing.statuses : new Set();
        if (Array.isArray(expectedStatus)) {
            expectedStatus.forEach(s => statuses.add(String(s)));
        } else {
            statuses.add(String(expectedStatus));
        }
        this.ownActions.set(key, {
            statuses: statuses,
            expiresAt: Date.now() + 30000
        });
    }

    observe(orders, options) {
        const opts = options || {};
        const initial = Boolean(opts.initial);
        const list = Array.isArray(orders) ? orders : (orders ? [orders] : []);
        if (list.length === 0) return;

        if (initial) {
            list.forEach(o => {
                if (o && o.id) {
                    this.lastKnownStatus.set(String(o.id), String(o.status || ''));
                }
            });
            return;
        }

        let chimeWork = false;
        let chimeReady = false;
        const now = Date.now();

        // Expire old own actions (> 30s)
        for (const [id, rec] of this.ownActions) {
            if (rec.expiresAt < now) {
                this.ownActions.delete(id);
            }
        }

        for (const o of list) {
            if (!o || !o.id) continue;
            const id = String(o.id);
            const currentStatus = String(o.status || '');
            const prevStatus = this.lastKnownStatus.get(id);

            // If same status, no transition
            if (prevStatus === currentStatus) {
                continue;
            }

            this.lastKnownStatus.set(id, currentStatus);

            // Check if own action expects this transition
            const own = this.ownActions.get(id);
            if (own && own.statuses && own.statuses.has(currentStatus)) {
                own.statuses.delete(currentStatus);
                if (own.statuses.size === 0) {
                    this.ownActions.delete(id);
                }
                continue; // Suppress chime for screen's own action
            }

            if (currentStatus === 'CREATED' || currentStatus === 'ACCEPTED') {
                chimeWork = true;
            } else if (currentStatus === 'DONE') {
                chimeReady = true;
            }
        }

        // Play at most one chime per tone per poll
        if (chimeWork) {
            this.play('work');
        }
        if (chimeReady) {
            if (chimeWork) {
                setTimeout(() => this.play('ready'), 600);
            } else {
                this.play('ready');
            }
        }
    }

    play(tone) {
        if (!this.enabled || this.blocked) return;
        try {
            const ctx = this.getAudioContext();
            if (!ctx) return;
            if (ctx.state === 'suspended') {
                this.blocked = true;
                this.updateUi();
                return;
            }

            const now = ctx.currentTime;
            if (tone === 'ready') {
                // 3-note rising chime for DONE (ready for waiter/pickup): C6 -> E6 -> G6
                const notes = [
                    { freq: 1046.50, start: now, dur: 0.18, gain: 0.35 },
                    { freq: 1318.51, start: now + 0.12, dur: 0.18, gain: 0.35 },
                    { freq: 1567.98, start: now + 0.24, dur: 0.45, gain: 0.4 }
                ];
                notes.forEach(n => {
                    const osc = ctx.createOscillator();
                    const gain = ctx.createGain();
                    osc.type = 'sine';
                    osc.frequency.setValueAtTime(n.freq, n.start);
                    gain.gain.setValueAtTime(n.gain, n.start);
                    gain.gain.exponentialRampToValueAtTime(0.001, n.start + n.dur);
                    osc.connect(gain);
                    gain.connect(ctx.destination);
                    osc.start(n.start);
                    osc.stop(n.start + n.dur);
                });
            } else {
                // Two-tone ding-dong chime for new work (CREATED / ACCEPTED): D5 -> A5
                const osc1 = ctx.createOscillator();
                const gain1 = ctx.createGain();
                osc1.type = 'sine';
                osc1.frequency.setValueAtTime(587.33, now);
                gain1.gain.setValueAtTime(0.3, now);
                gain1.gain.exponentialRampToValueAtTime(0.001, now + 0.35);
                osc1.connect(gain1);
                gain1.connect(ctx.destination);
                osc1.start(now);
                osc1.stop(now + 0.35);

                const osc2 = ctx.createOscillator();
                const gain2 = ctx.createGain();
                osc2.type = 'sine';
                osc2.frequency.setValueAtTime(880, now + 0.15);
                gain2.gain.setValueAtTime(0.4, now + 0.15);
                gain2.gain.exponentialRampToValueAtTime(0.001, now + 0.65);
                osc2.connect(gain2);
                gain2.connect(ctx.destination);
                osc2.start(now + 0.15);
                osc2.stop(now + 0.65);
            }
        } catch (e) {
            console.warn('Unable to play kitchen chime', e);
        }
    }
}

window.kitchenChime = new KitchenChime();

function toggleKitchenAudio() {
    if (window.kitchenChime) {
        window.kitchenChime.toggle();
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (window.kitchenChime) {
        window.kitchenChime.init();
    }
});
