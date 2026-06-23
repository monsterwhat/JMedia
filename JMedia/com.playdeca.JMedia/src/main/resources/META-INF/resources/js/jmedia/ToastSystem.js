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
                const oldestToast = this.toasts.shift();
                this.removeToast(oldestToast.element);
            }

            const toastId = `toast-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
            const toast = this.createToastElement(message, type, toastId, clickHandler, persistent);

            this.toasts.push({ id: toastId, element: toast, type, timestamp: Date.now(), timeoutId: null, isRemoving: false });
            this.container.appendChild(toast);

            setTimeout(() => toast.classList.add('show'), 10);

            if (!persistent) {
                const timeoutId = setTimeout(() => this.hideToast(toastId), duration);
                const toastObj = this.toasts.find(t => t.id === toastId);
                if (toastObj) toastObj.timeoutId = timeoutId;
            } else {
                const safetyTimeoutId = setTimeout(() => this.hideToast(toastId), this.maxLifetime);
                const toastObj = this.toasts.find(t => t.id === toastId);
                if (toastObj) toastObj.timeoutId = safetyTimeoutId;
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

            const escapedMessage = JMedia.Helpers ? JMedia.Helpers.escapeHtml(message) : message;

            toast.innerHTML = `
                <div class="toast-content">
                    ${icon}
                    <span class="toast-message">${escapedMessage}</span>
                    ${!persistent ? '<button class="toast-close" aria-label="Close notification">×</button>' : ''}
                </div>
            `;

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

            if (toast.timeoutId) {
                clearTimeout(toast.timeoutId);
                toast.timeoutId = null;
            }

            toast.element.classList.remove('show');

            setTimeout(() => {
                const currentIndex = this.toasts.findIndex(t => t.id === toastId);
                if (currentIndex !== -1) {
                    this.removeToast(this.toasts[currentIndex].element);
                    this.toasts.splice(currentIndex, 1);
                }
            }, 300);
        }

        removeToast(toastElement) {
            if (!toastElement || !toastElement.parentNode) return;
            toastElement.parentNode.removeChild(toastElement);
        }

        clearAll() {
            const toastsToClear = this.toasts.slice();
            this.toasts = [];
            toastsToClear.forEach(toast => {
                if (toast.timeoutId) {
                    clearTimeout(toast.timeoutId);
                }
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

        progress(message, percent = 0) {
            let toast = document.getElementById('scan-progress-toast');

            if (!toast) {
                const toastId = 'scan-progress-toast';
                toast = document.createElement('div');
                toast.className = 'toast info';
                toast.id = toastId;
                toast.style.minWidth = '300px';

                toast.innerHTML = `
                    <div class="toast-content" style="flex-direction: column; align-items: flex-start;">
                        <div style="display: flex; align-items: center; width: 100%; margin-bottom: 8px;">
                            <i class="pi pi-spin pi-spinner" style="margin-right: 10px;"></i>
                            <span id="scan-progress-message" style="flex-grow: 1;">${message}</span>
                        </div>
                        <div class="progress-bar-container" style="width: 100%; height: 6px; background: rgba(255,255,255,0.2); border-radius: 3px; overflow: hidden;">
                            <div id="scan-progress-bar" style="width: ${percent}%; height: 100%; background: #4CAF50; transition: width 0.3s ease;"></div>
                        </div>
                        <div style="font-size: 0.8em; margin-top: 5px; opacity: 0.8;">App performance may be reduced</div>
                    </div>
                `;

                this.container.appendChild(toast);
                setTimeout(() => toast.classList.add('show'), 10);

                this.toasts.push({ id: toastId, element: toast, type: 'progress', timestamp: Date.now(), persistent: true });
            } else {
                const msgEl = document.getElementById('scan-progress-message');
                const barEl = document.getElementById('scan-progress-bar');
                if (msgEl) msgEl.textContent = message;
                if (barEl) barEl.style.width = `${percent}%`;
            }

            if (percent >= 100) {
                setTimeout(() => {
                    this.hideToast('scan-progress-toast');
                }, 2000);
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
