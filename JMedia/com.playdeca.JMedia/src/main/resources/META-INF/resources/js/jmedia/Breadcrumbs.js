(function(window) {
    'use strict';

    class Breadcrumbs {
        constructor() {
            this.entries = [];
            this.container = document.getElementById('breadcrumb-nav');
            this.list = document.getElementById('breadcrumb-list');
            this._boundClick = null;
        }

        /**
         * Replace entire breadcrumb trail.
         * Each path can be a string (display label, no nav) or
         * { label, navigate } where navigate is a function called on click.
         * The last entry is always the "current page" and never clickable.
         */
        set(paths) {
            this._detach();
            this.entries = paths.map(p => {
                if (typeof p === 'string') return { label: p, navigate: undefined };
                return { label: p.label, navigate: typeof p.navigate === 'function' ? p.navigate : undefined };
            });
            this._attach();
            this.render();
        }

        /**
         * Add one level onto the trail (drill down).
         * navigate: function to call when this breadcrumb level is clicked.
         */
        push(label, navigate) {
            this.entries.push({
                label,
                navigate: typeof navigate === 'function' ? navigate : undefined
            });
            this.render();
        }

        /**
         * Remove the last entry (go back one level).
         */
        pop() {
            if (this.entries.length <= 1) return;
            this.entries.pop();
            this.render();
        }

        /**
         * Replace the last entry's label (e.g. async title loaded).
         */
        updateLast(label) {
            if (this.entries.length === 0) return;
            this.entries[this.entries.length - 1].label = label;
            this.render();
        }

        clear() {
            this.entries = [];
            this.render();
        }

        _attach() {
            if (this._boundClick) return;
            this._boundClick = (e) => {
                const li = e.target.closest('[data-bc-idx]');
                if (!li) return;
                const idx = parseInt(li.dataset.bcIdx, 10);
                if (isNaN(idx) || idx === this.entries.length - 1) return;
                const entry = this.entries[idx];
                if (entry && typeof entry.navigate === 'function') {
                    entry.navigate();
                }
            };
            if (this.list) {
                this.list.addEventListener('click', this._boundClick);
            }
        }

        _detach() {
            if (this._boundClick && this.list) {
                this.list.removeEventListener('click', this._boundClick);
                this._boundClick = null;
            }
        }

        render() {
            // Refresh DOM references (view swaps recreate the elements)
            this.container = document.getElementById('breadcrumb-nav');
            this.list = document.getElementById('breadcrumb-list');
            if (!this.list || !this.container) return;

            if (this.entries.length === 0) {
                this.container.style.display = 'none';
                return;
            }

            this.container.style.display = '';

            // Re-attach click handler to the fresh ul
            this._detach();
            this._attach();

            this.list.innerHTML = this.entries.map((e, i) => {
                const isLast = i === this.entries.length - 1;
                const label = this.escape(e.label);
                if (isLast) {
                    return `<li class="is-active" data-bc-idx="${i}"><a aria-current="page">${label}</a></li>`;
                }
                if (typeof e.navigate === 'function') {
                    return `<li data-bc-idx="${i}"><a href="javascript:void(0)" class="bc-link">${label}</a></li>`;
                }
                return `<li data-bc-idx="${i}"><span class="bc-inert">${label}</span></li>`;
            }).join('');
        }

        escape(str) {
            if (!str) return '';
            return String(str)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;');
        }
    }

    window.Breadcrumbs = new Breadcrumbs();

})(window);
