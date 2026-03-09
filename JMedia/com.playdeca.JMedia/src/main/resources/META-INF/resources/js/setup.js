document.addEventListener('alpine:init', () => {
    Alpine.store('setup', {
        // Form data
        musicLibraryPath: '',
        videoLibraryPath: '',
        installImportFeatures: true,
        outputFormat: 'mp3',
        downloadThreads: 4,
        searchThreads: 4,
        runAsService: false,
        
        // UI state
        loading: false,
        loadingMessage: '',
        currentStep: 1,
        
        // Folder Browser State
        currentBrowserTarget: null,
        currentBrowserPath: '',
        _parentPath: null,

        init() {
            this.checkSetupStatus();
            this.loadExistingSettings();
            this.checkInstallationStatus();
        },

        // Folder Browser Logic
        openFolderBrowser(target) {
            this.currentBrowserTarget = target;
            const currentPath = target === 'music' ? this.musicLibraryPath : this.videoLibraryPath;
            document.getElementById('folderBrowserModal').classList.add('is-active');
            this.loadFolders(currentPath);
        },

        closeFolderBrowser() {
            document.getElementById('folderBrowserModal').classList.remove('is-active');
        },

        async loadFolders(path) {
            const list = document.getElementById('folderBrowserList');
            list.innerHTML = '<div class="p-4 has-text-centered has-text-white"><i class="pi pi-spin pi-spinner mr-2"></i> Listing folders...</div>';
            
            try {
                const res = await fetch(`/api/settings/browse/list-folders?path=${encodeURIComponent(path || '')}`);
                const json = await res.json();
                
                if (res.ok && json.data) {
                    this.currentBrowserPath = json.data.currentPath || '';
                    document.getElementById('currentFolderPathDisplay').value = this.currentBrowserPath || 'System Roots';
                    this._parentPath = json.data.parentPath;
                    
                    const folders = json.data.folders || [];
                    if (folders.length === 0) {
                        list.innerHTML = '<div class="p-4 has-text-centered has-text-grey">No subfolders found</div>';
                    } else {
                        list.innerHTML = folders.map(f => `
                            <div class="p-3 is-clickable folder-item" onclick="Alpine.store('setup').loadFolders('${f.path.replace(/\\/g, '\\\\')}')" 
                                 style="border-bottom: 1px solid rgba(255,255,255,0.05); color: white; transition: 0.2s;">
                                <i class="pi pi-folder mr-3" style="color: #48c774;"></i>
                                <span>${f.name}</span>
                            </div>
                        `).join('');
                    }
                }
            } catch (e) {
                list.innerHTML = `<div class="p-4 has-text-danger">Error loading folders</div>`;
            }
        },

        navigateUpFolder() {
            if (this._parentPath !== null) {
                this.loadFolders(this._parentPath);
            }
        },

        confirmFolderSelection() {
            if (this.currentBrowserTarget === 'music') {
                this.musicLibraryPath = this.currentBrowserPath;
            } else {
                this.videoLibraryPath = this.currentBrowserPath;
            }
            this.closeFolderBrowser();
        },

        // Installation functions
        async installComponent(comp) {
            this.updateComponentStatus(comp, 'Installing...');
            try {
                const response = await fetch(`/api/import/install/${comp}/1`, { method: 'POST' });
                if (response.ok) {
                    this.updateComponentStatus(comp, '⏳ Installation started');
                    // Poll for status
                    setTimeout(() => this.checkInstallationStatus(), 2000);
                }
            } catch (error) {
                this.updateComponentStatus(comp, '❌ Error: ' + error.message);
            }
        },

        installChoco() { this.installComponent('choco'); },
        installPython() { this.installComponent('python'); },
        installFfmpeg() { this.installComponent('ffmpeg'); },
        installSpotdl() { this.installComponent('spotdl'); },
        installWhisper() { this.installComponent('whisper'); },

        updateComponentStatus(component, status) {
            const el = document.getElementById(component + 'Status');
            if (el) el.textContent = status;
        },

        nextStep() { if (this.currentStep < 4) this.currentStep++; },
        previousStep() { if (this.currentStep > 1) this.currentStep--; },

        async loadExistingSettings() {
            try {
                const response = await fetch('/api/settings/1');
                if (response.ok) {
                    const result = await response.json();
                    if (result.data) {
                        this.musicLibraryPath = result.data.libraryPath || '';
                        this.videoLibraryPath = result.data.videoLibraryPath || '';
                        this.runAsService = result.data.runAsService || false;
                    }
                }
            } catch (e) {}
        },

        async checkSetupStatus() {
            try {
                const response = await fetch('/api/setup/status');
                const result = await response.json();
                if (result.success && !result.data.isFirstTimeSetup) {
                    window.location.href = '/';
                }
            } catch (e) {}
        },

        async validateAndNext() {
            if (!this.musicLibraryPath) {
                alert('Please select a music library folder');
                return;
            }
            this.nextStep();
        },

        async checkInstallationStatus() {
            try {
                const response = await fetch('/api/settings/1/install-status');
                const result = await response.json();
                const status = result.data || result;
                
                ['choco', 'python', 'ffmpeg', 'spotdl', 'whisper'].forEach(c => {
                    const isInst = status[`${c}Installed`];
                    this.updateComponentStatus(c, isInst ? '✅ Installed' : '❌ Not installed');
                });
            } catch (e) {}
        },

        async completeSetup() {
            this.loading = true;
            this.loadingMessage = 'Finalizing your JMedia station...';
            
            try {
                const params = new URLSearchParams();
                params.append('musicLibraryPath', this.musicLibraryPath);
                if (this.videoLibraryPath) params.append('videoLibraryPath', this.videoLibraryPath);
                params.append('installImportFeatures', 'true');
                params.append('outputFormat', this.outputFormat);
                params.append('downloadThreads', this.downloadThreads.toString());
                params.append('searchThreads', this.searchThreads.toString());
                params.append('runAsService', this.runAsService);
                
                const response = await fetch('/api/setup/complete', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: params
                });
                
                if (response.ok) {
                    window.location.href = '/';
                } else {
                    alert('Setup failed. Please check the logs.');
                }
            } catch (error) {
                alert('Error completing setup: ' + error.message);
            } finally {
                this.loading = false;
            }
        }
    });
});
