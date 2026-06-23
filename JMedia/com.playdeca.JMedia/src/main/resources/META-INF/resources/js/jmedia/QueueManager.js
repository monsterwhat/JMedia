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
                }
            });

            window.addEventListener('queueChanged', (event) => {
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
            const allRows = document.querySelectorAll('#songQueueTable tr[data-song-id]');
            allRows.forEach(row => {
                row.classList.remove('current-song-row');
            });
            const selectedRow = document.querySelector(`#songQueueTable tr[data-song-id="${songId}"]`);
            if (selectedRow) {
                selectedRow.classList.add('current-song-row');
            }
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

        handleAction: function(action, index, profileIdParam) {
            const profileId = profileIdParam || JMedia.Helpers.getActiveProfileId();
            let url = '';
            if (action === 'skip') {
                url = `/api/music/queue/skip-to/${profileId}/${index}`;
            } else if (action === 'remove') {
                url = `/api/music/queue/remove/${profileId}/${index}`;
            } else {
                return;
            }

            fetch(url, {
                method: 'POST',
                headers: {'Accept': 'application/json'}
            })
                .then(response => {
                    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                    return response.json();
                })
                .then(data => {
                    const tbody = document.querySelector('#songQueueTable tbody');
                    const mobileQueue = document.getElementById('mobileQueueContent');

                    if (tbody) tbody.innerHTML = data.html;
                    if (mobileQueue) {
                        if (data.mobileHtml) {
                            mobileQueue.innerHTML = data.mobileHtml;
                        } else if (window.jmediaMobile && window.jmediaMobile.loadMobileQueue) {
                            window.jmediaMobile.loadMobileQueue(this.currentPage);
                        } else {
                            this.loadPage(this.currentPage);
                        }
                    }

                    if (data.totalQueueSize !== undefined) {
                        this.updateCount(data.totalQueueSize);
                    } else {
                        this.loadPage(1);
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
                    if (window.Toast) {
                        window.Toast.error(`Failed to ${action} song from queue`);
                    }
                });
        },

        bindClearQueue: function() {
            document.addEventListener('DOMContentLoaded', () => {
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
                            .then(data => {
                                const tbody = document.querySelector('#songQueueTable tbody');
                                if (tbody) tbody.innerHTML = data.html;
                                if (window.updateQueueCount) window.updateQueueCount(0);
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
    window.handleQueueAction = function(action, index, profileIdParam) {
        JMedia.QueueManager.handleAction(action, index, profileIdParam);
    };
    window.loadQueuePage = function(page, pid, search) {
        JMedia.QueueManager.loadPage(page, pid, search);
    };

    JMedia.QueueManager.init();

})(window);
