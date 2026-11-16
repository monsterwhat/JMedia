(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    class ToastSystem {
        constructor() {
            this.toasts = [];
            this.maxToasts = 5;
            this.defaultDuration = 4000;
            this.maxLifetime = 300000;
            this.container = null;
            this._escapeWarned = false;
            this.init();
        }

        init() {
            this.container = document.getElementById('toast-container');
            if (!this.container) {
                this.container = document.createElement('div');
                this.container.id = 'toast-container';
                this.container.style.cssText = 'position: fixed; top: 20px; right: 20px; z-index: 99999;';
                document.body.appendChild(this.container);
            }
        }

        clearToastTimers(toast) {
            if (!toast) return;
            ['timeoutId', 'showTimerId', 'removeTimerId', 'progressHideTimerId'].forEach((key) => {
                if (toast[key]) {
                    try {
                        clearTimeout(toast[key]);
                    } catch (e) {
                        console.error('[ToastSystem] clearTimeout failed', e);
                    }
                    toast[key] = null;
                }
            });
        }

        show(options) {
            const config = typeof options === 'string'
                ? { message: options, type: 'info' }
                : { ...options };

            const {
                message,
                type = 'info',
                duration = this.defaultDuration,
                clickHandler = null,
                persistent = false
            } = config;

            if (!message) {
                console.warn('Toast: No message provided');
                return null;
            }

            if (this.toasts.length >= this.maxToasts && !persistent) {
                let victimIndex = -1;
                for (let i = 0; i < this.toasts.length; i++) {
                    const candidate = this.toasts[i];
                    if (candidate.persistent) continue;
                    if (candidate.type === 'progress') continue;
                    victimIndex = i;
                    break;
                }
                if (victimIndex !== -1) {
                    const victim = this.toasts.splice(victimIndex, 1)[0];
                    this.clearToastTimers(victim);
                    this.removeToast(victim.element);
                }
            }

            const toastId = `toast-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
            const toast = this.createToastElement(message, type, toastId, clickHandler, persistent);

            const showTimerId = setTimeout(() => {
                try {
                    toast.classList.add('show');
                } catch (e) {
                    console.error('[ToastSystem] show timer failed', e);
                }
            }, 10);

            const record = { id: toastId, element: toast, type, timestamp: Date.now(), timeoutId: null, showTimerId, removeTimerId: null, progressHideTimerId: null, isRemoving: false, persistent };
            this.toasts.push(record);
            this.container.appendChild(toast);

            if (!persistent) {
                const timeoutId = setTimeout(() => {
                    try {
                        this.hideToast(toastId);
                    } catch (e) {
                        console.error('[ToastSystem] hide timer failed', e);
                    }
                }, duration);
                record.timeoutId = timeoutId;
            } else {
                const safetyTimeoutId = setTimeout(() => {
                    try {
                        this.hideToast(toastId);
                    } catch (e) {
                        console.error('[ToastSystem] safety hide timer failed', e);
                    }
                }, this.maxLifetime);
                record.timeoutId = safetyTimeoutId;
            }

            return toastId;
        }

        createToastElement(message, type, toastId, clickHandler, persistent = false) {
            const toast = document.createElement('div');
            toast.className = `toast ${type}`;
            toast.id = toastId;
            toast.setAttribute('role', 'alert');
            toast.setAttribute('aria-live', 'polite');

            const icons = {
                success: '<i class="pi pi-check-circle toast-icon"></i>',
                error: '<i class="pi pi-times-circle toast-icon"></i>',
                warning: '<i class="pi pi-exclamation-triangle toast-icon"></i>',
                info: '<i class="pi pi-info-circle toast-icon"></i>'
            };

            const icon = icons[type] || icons.info;

            const hasEscape = !!(JMedia.Helpers && typeof JMedia.Helpers.escapeHtml === 'function');
            let escapedMessage = '';
            if (hasEscape) {
                escapedMessage = JMedia.Helpers.escapeHtml(message);
            } else {
                if (!this._escapeWarned) {
                    this._escapeWarned = true;
                    console.warn('[ToastSystem] escapeHtml unavailable; using textContent');
                }
            }

            toast.innerHTML = `
                <div class="toast-content">
                    ${icon}
                    <span class="toast-message">${escapedMessage}</span>
                    ${!persistent ? '<button class="toast-close" aria-label="Close notification">×</button>' : ''}
                </div>
            `;

            if (!hasEscape) {
                const msgSpan = toast.querySelector('.toast-message');
                if (msgSpan) msgSpan.textContent = message;
            }

            if (clickHandler) {
                toast.style.cursor = 'pointer';
                toast.addEventListener('click', (e) => {
                    if (!e.target.classList.contains('toast-close')) {
                        clickHandler();
                    }
                });
            }

            const closeBtn = toast.querySelector('.toast-close');
            if (closeBtn) {
                closeBtn.addEventListener('click', () => this.hideToast(toastId));
            }

            toast.setAttribute('tabindex', '-1');
            toast.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' || e.key === 'Enter') {
                    this.hideToast(toastId);
                }
            });

            return toast;
        }

        hideToast(toastId) {
            const toastIndex = this.toasts.findIndex(t => t.id === toastId);
            if (toastIndex === -1) return;

            const toast = this.toasts[toastIndex];
            if (!toast || !toast.element || toast.isRemoving) return;

            toast.isRemoving = true;

            this.clearToastTimers(toast);

            toast.element.classList.remove('show');

            const removeTimerId = setTimeout(() => {
                const currentIndex = this.toasts.findIndex(t => t.id === toastId);
                if (currentIndex !== -1) {
                    this.removeToast(this.toasts[currentIndex].element);
                    this.toasts.splice(currentIndex, 1);
                }
            }, 300);
            toast.removeTimerId = removeTimerId;
        }

        removeToast(toastElement) {
            if (!toastElement) return;
            for (let i = this.toasts.length - 1; i >= 0; i--) {
                if (this.toasts[i].element === toastElement) {
                    this.clearToastTimers(this.toasts[i]);
                }
            }
            if (!toastElement.parentNode) return;
            toastElement.parentNode.removeChild(toastElement);
        }

        clearAll() {
            const toastsToClear = this.toasts.slice();
            this.toasts = [];
            toastsToClear.forEach(toast => {
                this.clearToastTimers(toast);
                this.removeToast(toast.element);
            });
        }

        success(message, options = {}) {
            return this.show({ ...options, message, type: 'success' });
        }

        error(message, options = {}) {
            return this.show({ ...options, message, type: 'error', duration: options.duration || 6000 });
        }

        info(message, options = {}) {
            return this.show({ ...options, message, type: 'info' });
        }

        warning(message, options = {}) {
            return this.show({ ...options, message, type: 'warning' });
        }

        progress(message, percent = 0, options = {}) {
            const id = options.id || 'scan-progress-toast';
            const caption = options.caption || 'App performance may be reduced';

            this.toasts.forEach((t) => {
                if (t.id === id && t.progressHideTimerId) {
                    clearTimeout(t.progressHideTimerId);
                    t.progressHideTimerId = null;
                }
            });

            const hasEscape = !!(JMedia.Helpers && typeof JMedia.Helpers.escapeHtml === 'function');
            if (!hasEscape && !this._escapeWarned) {
                this._escapeWarned = true;
                console.warn('[ToastSystem] escapeHtml unavailable; using textContent');
            }

            let toast = document.getElementById(id);

            if (!toast) {
                const escapedMessage = hasEscape ? JMedia.Helpers.escapeHtml(message) : '';
                const escapedCaption = hasEscape ? JMedia.Helpers.escapeHtml(caption) : '';

                toast = document.createElement('div');
                toast.className = 'toast info';
                toast.id = id;
                toast.style.minWidth = '300px';

                toast.innerHTML = `
                    <div class="toast-content" style="flex-direction: column; align-items: flex-start;">
                        <div style="display: flex; align-items: center; width: 100%; margin-bottom: 8px;">
                            <i class="pi pi-spin pi-spinner" style="margin-right: 10px;"></i>
                            <span id="${id}-message" style="flex-grow: 1;">${escapedMessage}</span>
                        </div>
                        <div class="progress-bar-container" style="width: 100%; height: 6px; background: rgba(255,255,255,0.2); border-radius: 3px; overflow: hidden;">
                            <div id="${id}-bar" style="width: ${percent}%; height: 100%; background: #4CAF50; transition: width 0.3s ease;"></div>
                        </div>
                        <div id="${id}-caption" style="font-size: 0.8em; margin-top: 5px; opacity: 0.8;">${escapedCaption}</div>
                    </div>
                `;

                if (!hasEscape) {
                    const msgEl = toast.querySelector('span[id$="-message"]');
                    if (msgEl) msgEl.textContent = message;
                    const captionEl = toast.querySelector('div[id$="-caption"]');
                    if (captionEl) captionEl.textContent = caption;
                }

                this.container.appendChild(toast);

                const showTimerId = setTimeout(() => {
                    try {
                        toast.classList.add('show');
                    } catch (e) {
                        console.error('[ToastSystem] progress show timer failed', e);
                    }
                }, 10);

                this.toasts = this.toasts.filter((t) => t.id !== id);
                const record = { id, element: toast, type: 'progress', timestamp: Date.now(), persistent: true, timeoutId: null, showTimerId, removeTimerId: null, progressHideTimerId: null, isRemoving: false };
                this.toasts.push(record);
            } else {
                const existingEntries = this.toasts.filter((t) => t.id === id);
                if (existingEntries.length === 0) {
                    this.toasts.push({ id, element: toast, type: 'progress', timestamp: Date.now(), persistent: true, timeoutId: null, showTimerId: null, removeTimerId: null, progressHideTimerId: null, isRemoving: false });
                } else if (existingEntries.length > 1) {
                    let kept = false;
                    this.toasts = this.toasts.filter((t) => {
                        if (t.id !== id) return true;
                        if (!kept) {
                            kept = true;
                            return true;
                        }
                        this.clearToastTimers(t);
                        return false;
                    });
                }

                const msgEl = document.getElementById(`${id}-message`);
                const barEl = document.getElementById(`${id}-bar`);
                if (msgEl) msgEl.textContent = message;
                if (barEl) barEl.style.width = `${percent}%`;
            }

            if (percent >= 100) {
                const hideTimerId = setTimeout(() => {
                    try {
                        this.hideToast(id);
                    } catch (e) {
                        console.error('[ToastSystem] progress hide timer failed', e);
                    }
                }, 2000);
                const rec = this.toasts.find((t) => t.id === id);
                if (rec) rec.progressHideTimerId = hideTimerId;
            }
        }

        getToastCount() {
            return this.toasts.length;
        }

        setMaxToasts(max) {
            this.maxToasts = Math.max(1, parseInt(max) || 5);
        }
    }

    JMedia.ToastSystem = ToastSystem;

    const Toast = new ToastSystem();
    window.Toast = Toast;

    window.showToast = function(message, type = 'info', duration = null, clickHandler = null) {
        return Toast.show({
            message,
            type,
            duration: duration || Toast.defaultDuration,
            clickHandler
        });
    };
})(window);