/**
 * Unified Toast Notification System for JMedia
 * Consolidates all toast implementations into one consistent library
 */

class ToastSystem {
    constructor() {
        this.toasts = [];
        this.maxToasts = 5;
        this.defaultDuration = 4000;
        this.container = null;
        this.init();
    }

    init() {
        // Create toast container if it doesn't exist
        this.container = document.getElementById('toast-container');
        if (!this.container) {
            this.container = document.createElement('div');
            this.container.id = 'toast-container';
            this.container.style.cssText = 'position: fixed; top: 20px; right: 20px; z-index: 10000;';
            document.body.appendChild(this.container);
        }
    }

    // Unified toast creation method
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

        // Remove oldest toast if we exceed maxToasts (unless persistent)
        if (this.toasts.length >= this.maxToasts && !persistent) {
            const oldestToast = this.toasts.shift();
            this.removeToast(oldestToast.element);
        }

        const toastId = `toast-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
        const toast = this.createToastElement(message, type, toastId, clickHandler, persistent);
        
        this.toasts.push({ id: toastId, element: toast, type, timestamp: Date.now() });
        this.container.appendChild(toast);

        // Trigger animation
        setTimeout(() => toast.classList.add('show'), 10);

        // Auto-remove if not persistent
        if (!persistent) {
            setTimeout(() => this.hideToast(toastId), duration);
        }

        return toastId;
    }

    createToastElement(message, type, toastId, clickHandler, persistent = false) {
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.id = toastId;
        toast.setAttribute('role', 'alert');
        toast.setAttribute('aria-live', 'polite');

        // Use enhanced icons from settings.html implementation
        const icons = {
            success: '<i class="pi pi-check-circle toast-icon"></i>',
            error: '<i class="pi pi-times-circle toast-icon"></i>',
            warning: '<i class="pi pi-exclamation-triangle toast-icon"></i>',
            info: '<i class="pi pi-info-circle toast-icon"></i>'
        };

        const icon = icons[type] || icons.info;

        toast.innerHTML = `
            <div class="toast-content">
                ${icon}
                <span class="toast-message">${this.escapeHtml(message)}</span>
                ${!persistent ? '<button class="toast-close" aria-label="Close notification">×</button>' : ''}
            </div>
        `;

        // Add click handler if provided
        if (clickHandler) {
            toast.style.cursor = 'pointer';
            toast.addEventListener('click', (e) => {
                if (!e.target.classList.contains('toast-close')) {
                    clickHandler();
                }
            });
        }

        // Add close button handler
        const closeBtn = toast.querySelector('.toast-close');
        if (closeBtn) {
            closeBtn.addEventListener('click', () => this.hideToast(toastId));
        }

        // Add keyboard support for accessibility
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
        if (toast.element) {
            toast.element.classList.remove('show');
            
            setTimeout(() => {
                this.removeToast(toast.element);
                this.toasts.splice(toastIndex, 1);
            }, 300);
        }
    }

    removeToast(toastElement) {
        if (toastElement && toastElement.parentNode) {
            toastElement.parentNode.removeChild(toastElement);
        }
    }

    clearAll() {
        this.toasts.forEach(toast => {
            this.removeToast(toast.element);
        });
        this.toasts = [];
    }

    // Convenience methods for different toast types
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

    // Escape HTML to prevent XSS
    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // Get current toast count
    getToastCount() {
        return this.toasts.length;
    }

    // Set max concurrent toasts
    setMaxToasts(max) {
        this.maxToasts = Math.max(1, parseInt(max) || 5);
    }
}

// Create global Toast instance
const Toast = new ToastSystem();

// Backward compatibility functions for existing code
window.showToast = function(message, type = 'info', duration = null, clickHandler = null) {
    return Toast.show({
        message,
        type,
        duration: duration || Toast.defaultDuration,
        clickHandler
    });
};

// Export for module usage
if (typeof module !== 'undefined' && module.exports) {
    module.exports = Toast;
}

// Auto-initialize when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => Toast.init());
} else {
    Toast.init();
}