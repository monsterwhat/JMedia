(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.QueueManager = {
        currentPage: 1,
        searchQuery: '',
        limit: 50,
        totalSize: Infinity,
        isFetching: false,
        hasInitialLoad: false,
        lastKnownQueue: [],

        init: function() {
            this.setupEventListeners();
            this.bindClearQueue();
            window.refreshQueue = () => {
                if (!JMedia.QueueManager.hasInitialLoad) {
                    JMedia.QueueManager.hasInitialLoad = true;
                    try {
                        JMedia.QueueManager.loadPage(1, undefined, JMedia.QueueManager.searchQuery);
                    } catch (error) {
                        console.error('[QueueManager] Failed to refresh queue:', error);
                        JMedia.QueueManager.hasInitialLoad = false;
                    }
                }
            };
            window.refreshQueue();
        },

        setupEventListeners: function() {
            window.addEventListener('statePropertyChanged', (e) => {
                if (e.detail.property === 'cue') {
                    this.handleQueueChange(e.detail.oldValue, e.detail.newValue);
                } else if (e.detail.property === 'currentSongId' && e.detail.newValue) {
                    // Keep the queue's current-song highlight in sync without reloading the list.
                    this.updateCurrentSong(e.detail.newValue);
                }
            });

            window.addEventListener('queueChanged', (event) => {
                // The musicBar state bridge also emits queueChanged. A local card
                // removal must not turn into a full list reload, so honor the
                // suppression window here too.
                if (this._suppressQueueReloadUntil && Date.now() < this._suppressQueueReloadUntil) {
                    return;
                }
                try {
                    this.loadPage(1, undefined, this.searchQuery);
                } catch (error) {
                    console.error('[QueueManager] Failed to load queue on change event:', error);
                }
            });
        },

        handleQueueChange: function(oldQueue, newQueue) {
            const queueChanged = this.hasQueueChanged(newQueue, oldQueue);
            const queueLengthChanged = (oldQueue?.length || 0) !== (newQueue?.length || 0);

            if (queueChanged || queueLengthChanged) {
                this.lastKnownQueue = [...(newQueue || [])];
                // A local remove already dropped the card from the DOM. The server's
                // state echo repeats the same change; rebuilding the whole list here
                // would undo that and make removals feel like a page reload.
                if (this._suppressQueueReloadUntil && Date.now() < this._suppressQueueReloadUntil) {
                    return;
                }
                window.dispatchEvent(new CustomEvent('queueChanged', {
                    detail: {
                        queueSize: newQueue?.length || 0,
                        queueChanged: queueChanged,
                        queueLengthChanged: queueLengthChanged,
                        oldQueue: oldQueue,
                        newQueue: newQueue
                    }
                }));
            }
        },

        hasQueueChanged: function(newCue, oldCue) {
            if (!newCue && !oldCue) return false;
            if (!newCue || !oldCue) return true;
            if (newCue.length !== oldCue.length) return true;
            if (newCue[0] !== oldCue[0] || newCue[newCue.length - 1] !== oldCue[oldCue.length - 1]) return true;
            return JSON.stringify(newCue) !== JSON.stringify(oldCue);
        },

        getSearchQuery: function() {
            const input = document.getElementById('queueSearchInput');
            return input ? input.value.trim() : '';
        },

        updateCount: function(size) {
            const countSpan = document.getElementById('queueCount');
            if (countSpan) {
                countSpan.textContent = size;
            }
        },

        updateCurrentSong: function(songId) {
            const selector = '#songQueueTable tr[data-song-id], .mobile-queue-item[data-song-id]';
            document.querySelectorAll(selector).forEach(row => {
                row.classList.remove('current-song-row');
            });
            document.querySelectorAll(`#songQueueTable tr[data-song-id="${songId}"], .mobile-queue-item[data-song-id="${songId}"]`).forEach(row => {
                row.classList.add('current-song-row');
            });
        },

        loadPage: function(page = 1, profileIdParam, searchParam) {
            if (this.isFetching) return;
            this.isFetching = true;
            this.currentPage = page;
            const profileId = profileIdParam || JMedia.Helpers.getActiveProfileId();
            const search = searchParam !== undefined ? searchParam : this.getSearchQuery();
            this.searchQuery = search;

            const searchEncoded = encodeURIComponent(search);
            fetch(`/api/music/ui/queue-fragment/${profileId}?page=${this.currentPage}&limit=${this.limit}&search=${searchEncoded}`, {
                headers: {'Accept': 'application/json'}
            })
                .then(response => {
                    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                    return response.json();
                })
                .then(data => {
                    const tbody = document.querySelector('#songQueueTable tbody');
                    const mobileQueue = document.getElementById('mobileQueueContent');

                    if (!tbody && !mobileQueue) {
                        this.isFetching = false;
                        return;
                    }

                    if (tbody) tbody.innerHTML = data.html;
                    if (mobileQueue) mobileQueue.innerHTML = data.mobileHtml || data.html;

                    this.totalSize = data.totalQueueSize;
                    this.isFetching = false;

                    if (tbody) {
                        tbody.querySelectorAll('tr').forEach(row => {
                            const cell = row.querySelector('td:nth-child(2)');
                            if (cell) JMedia.Helpers.applyMarqueeEffect(cell);
                        });
                    }

                    this.updateCount(this.totalSize);
                })
                .catch(error => {
                    console.error('[QueueManager] loadPage failed:', error);
                    this.isFetching = false;
                });
        },

        /**
         * Resolve the queue index from either a number or the clicked element.
         * Templates call handleQueueAction('remove', this) so the index is read
         * from the card and never has to be rewritten in inline handlers.
         */
        resolveQueueIndex: function(indexOrElement) {
            if (typeof indexOrElement === 'number') {
                return indexOrElement;
            }
            if (typeof indexOrElement === 'string' && indexOrElement.trim() !== '' && !isNaN(parseInt(indexOrElement, 10))) {
                return parseInt(indexOrElement, 10);
            }
            const el = (indexOrElement && indexOrElement.closest) ? indexOrElement : null;
            const card = el ? el.closest('[data-queue-index]') : null;
            if (card) {
                const n = parseInt(card.getAttribute('data-queue-index'), 10);
                return isNaN(n) ? null : n;
            }
            return null;
        },

        /**
         * Remove one queue card locally and shift the indices of later cards so
         * the next removal still targets the right row.
         */
        removeQueueCard: function(index) {
            const cards = Array.from(document.querySelectorAll(`[data-queue-index="${index}"]`));
            if (cards.length === 0) {
                return false;
            }
            cards.forEach(card => card.remove());
            document.querySelectorAll('[data-queue-index]').forEach(card => {
                const i = parseInt(card.getAttribute('data-queue-index'), 10);
                if (!isNaN(i) && i > index) {
                    card.setAttribute('data-queue-index', String(i - 1));
                }
            });
            const items = document.querySelectorAll('.mobile-queue-item');
            items.forEach((item, i) => item.style.setProperty('--i', i));
            return true;
        },

        decrementQueueCount: function() {
            if (typeof this.totalSize === 'number' && isFinite(this.totalSize)) {
                this.totalSize = Math.max(0, this.totalSize - 1);
                this.updateCount(this.totalSize);
                if (window.updateQueueCount) window.updateQueueCount(this.totalSize);
                return;
            }
            const span = document.getElementById('queueCount');
            const current = span ? parseInt(span.textContent, 10) : NaN;
            if (!isNaN(current)) {
                this.updateCount(Math.max(0, current - 1));
            }
        },

        handleAction: function(action, indexOrElement, profileIdParam, element) {
            const profileId = profileIdParam || JMedia.Helpers.getActiveProfileId();
            if (!profileId) {
                console.warn('[QueueManager] No active profile, cannot ' + action);
                return;
            }
            const index = this.resolveQueueIndex(indexOrElement);
            if (index === null) {
                console.warn('[QueueManager] Could not resolve queue index for ' + action);
                return;
            }

            let url = '';
            if (action === 'skip') {
                url = `/api/music/queue/skip-to/${profileId}/${index}`;
            } else if (action === 'remove') {
                url = `/api/music/queue/remove/${profileId}/${index}`;
            } else {
                return;
            }

            let removedLocally = false;
            if (action === 'remove') {
                // The server echoes the new cue over the WebSocket. Suppress the
                // resulting list rebuild so only the removed card disappears.
                this._suppressQueueReloadUntil = Date.now() + 3000;
                // Drop the card before the round-trip: the POST only persists it.
                removedLocally = this.removeQueueCard(index);
                if (removedLocally) {
                    this.decrementQueueCount();
                }
            }

            fetch(url, {
                method: 'POST',
                headers: {'Accept': 'application/json'}
            })
                .then(response => {
                    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                    return response.json().catch(() => ({}));
                })
                .then(() => {
                    if (action === 'remove') {
                        if (!removedLocally) {
                            // Card not rendered in this view: resync once.
                            this._suppressQueueReloadUntil = 0;
                            this.loadPage(this.currentPage);
                        }
                    } else {
                        const card = (element && element.closest && element.closest('[data-song-id]'))
                            || document.querySelector(`[data-queue-index="${index}"][data-song-id]`);
                        const songId = card ? card.getAttribute('data-song-id') : null;
                        if (songId) this.updateCurrentSong(songId);
                    }

                    if (window.Toast) {
                        if (action === 'skip') {
                            window.Toast.success('Skipped to selected song in queue');
                        } else if (action === 'remove') {
                            window.Toast.success('Song removed from queue');
                        }
                    }
                })
                .catch(error => {
                    console.error(`[QueueManager] handleAction failed for ${action}:`, error);
                    this._suppressQueueReloadUntil = 0;
                    if (window.Toast) {
                        window.Toast.error(`Failed to ${action} song from queue`);
                    }
                    this.loadPage(this.currentPage);
                });
        },

        bindClearQueue: function() {
            (window.__whenDomReady || function (fn) {
                if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn, { once: true });
                else fn();
            })(() => {
                const clearBtn = document.getElementById('clearQueueBtn');
                if (clearBtn) {
                    clearBtn.addEventListener('click', () => {
                        const profileId = JMedia.Helpers.getActiveProfileId();
                        fetch(`/api/music/queue/clear/${profileId}`, {
                            method: 'POST',
                            headers: {'Accept': 'application/json'}
                        })
                            .then(response => {
                                if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                                return response.json();
                            })
                            .then(() => {
                                if (window.updateQueueCount) window.updateQueueCount(0);
                                // One fragment render is enough; the old code wrote
                                // data.html (undefined) and then reloaded separately.
                                this.loadPage(1);
                            })
                            .catch(error => {
                                console.error('[QueueManager] clearQueue failed:', error);
                            });
                    });
                }
            });
        }
    };

    window.updateQueueCount = function(size) {
        JMedia.QueueManager.updateCount(size);
    };
    window.updateQueueCurrentSong = function(songId) {
        JMedia.QueueManager.updateCurrentSong(songId);
    };
    window.handleQueueAction = function(action, index, profileIdParam, element) {
        JMedia.QueueManager.handleAction(action, index, profileIdParam, element);
    };
    window.loadQueuePage = function(page, pid, search) {
        JMedia.QueueManager.loadPage(page, pid, search);
    };

    JMedia.QueueManager.init();

})(window);
