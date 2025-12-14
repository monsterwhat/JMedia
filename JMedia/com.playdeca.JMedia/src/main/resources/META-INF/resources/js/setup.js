document.addEventListener('alpine:init', () => {
    Alpine.store('setup', {
        // Form data
        musicLibraryPath: '',
        videoLibraryPath: '',
        installImportFeatures: window.initialSetupData?.installImportFeatures ?? true,
        outputFormat: window.initialSetupData?.outputFormat ?? 'mp3',
        downloadThreads: window.initialSetupData?.downloadThreads ?? 4,
        searchThreads: window.initialSetupData?.searchThreads ?? 4,
        organizeLibrary: window.initialSetupData?.organizeLibrary ?? false,
        multipleUsers: window.initialSetupData?.multipleUsers ?? false,
        videoLibrary: window.initialSetupData?.videoLibrary ?? false,
        privateMode: window.initialSetupData?.privateMode ?? false,
        runAsService: window.initialSetupData?.runAsService ?? false,
        
        // UI state
        loading: false,
        loadingMessage: '',
        currentStep: 1,
        isTransitioning: false,
        currentFeatureCard: 1,
        
        // Watch for step changes
        currentStepChanged() {
            console.log('[Setup] Step changed to:', this.currentStep);
            if (this.currentStep === 3) {
                // Features step - check installation status
                setTimeout(() => {
                    console.log('[Setup] Features step detected, checking installation status');
                    this.checkInstallationStatus();
                }, 500);
            }
        },
        
        // Watch for runAsService changes
        runAsServiceChanged() {
            console.log('[Setup] runAsService changed to:', this.runAsService);
        },
        

        
        // Initialize
        init() {
            console.log('Setup store initializing with installImportFeatures:', this.installImportFeatures);
            this.checkSetupStatus();
            this.loadExistingSettings();
            // Ensure first step is active and visible
            setTimeout(() => {
                const firstStep = document.querySelector('.step-content:nth-child(1)');
                if (firstStep) {
                    firstStep.style.display = 'block';
                    firstStep.classList.add('active');
                }
                // Initialize carousel position
                this.updateCarouselPosition();
            });
        },
        
        // Watch for changes to installImportFeatures
        installImportFeaturesChanged() {
            if (this.installImportFeatures) {
                setTimeout(() => {
                    this.checkInstallationStatus();
                }, 100);
            }
        },

        // Installation functions
        async installPython() {
            this.updateComponentStatus('python', 'Installing...');
            try {
                const response = await fetch(`/api/import/install/python/${globalActiveProfileId}`, { method: 'POST' });
                const result = await response.json();
                if (result.success) {
                    this.updateComponentStatus('python', '✅ Installed');
                    // Refresh main status after installation
                    setTimeout(() => this.checkInstallationStatus(), 1000);
                } else {
                    this.updateComponentStatus('python', '❌ Failed: ' + result.message);
                }
            } catch (error) {
                this.updateComponentStatus('python', '❌ Error: ' + error.message);
            }
        },

        

        updateComponentStatus(component, status) {
            const statusElement = document.getElementById(component + 'Status');
            if (statusElement) {
                statusElement.textContent = status;
            }
        },

        async installFfmpeg() {
            this.updateComponentStatus('ffmpeg', 'Installing...');
            try {
                const response = await fetch(`/api/import/install/ffmpeg/${globalActiveProfileId}`, { method: 'POST' });
                const result = await response.json();
                if (result.success) {
                    this.updateComponentStatus('ffmpeg', '✅ Installed');
                    // Refresh main status after installation
                    setTimeout(() => this.checkInstallationStatus(), 1000);
                } else {
                    this.updateComponentStatus('ffmpeg', '❌ Failed: ' + result.message);
                }
            } catch (error) {
                this.updateComponentStatus('ffmpeg', '❌ Error: ' + error.message);
            }
        },

        async installSpotdl() {
            this.updateComponentStatus('spotdl', 'Installing...');
            try {
                const response = await fetch(`/api/import/install/spotdl/${globalActiveProfileId}`, { method: 'POST' });
                const result = await response.json();
                if (result.success) {
                    this.updateComponentStatus('spotdl', '✅ Installed');
                    // Refresh main status after installation
                    setTimeout(() => this.checkInstallationStatus(), 1000);
                } else {
                    this.updateComponentStatus('spotdl', '❌ Failed: ' + result.message);
                }
            } catch (error) {
                this.updateComponentStatus('spotdl', '❌ Error: ' + error.message);
            }
        },

        async installWhisper() {
            this.updateComponentStatus('whisper', 'Installing...');
            try {
                const response = await fetch(`/api/import/install/whisper/${globalActiveProfileId}`, { method: 'POST' });
                const result = await response.json();
                if (result.success) {
                    this.updateComponentStatus('whisper', '✅ Installed');
                    // Refresh main status after installation
                    setTimeout(() => this.checkInstallationStatus(), 1000);
                } else {
                    this.updateComponentStatus('whisper', '❌ Failed: ' + result.message);
                }
            } catch (error) {
                this.updateComponentStatus('whisper', '❌ Error: ' + error.message);
            }
        },

        // Navigate to next step with animation
        nextStep() {
            if (this.isTransitioning) return;
            
            this.isTransitioning = true;
            
            const currentStepElement = document.querySelector(`.step-content:nth-child(${this.currentStep})`);
            const nextStepElement = document.querySelector(`.step-content:nth-child(${this.currentStep + 1})`);
            
            if (currentStepElement && nextStepElement) {
                // Update step counter immediately
                this.currentStep++;
                
                // Prepare next step (hidden but ready)
                nextStepElement.style.display = 'block';
                nextStepElement.classList.add('slide-in-right');
                
                // Start animations in next frame
                requestAnimationFrame(() => {
                    // Slide out current step
                    currentStepElement.classList.add('slide-out-left');
                    currentStepElement.classList.remove('active');
                    
                    // Slide in next step
                    nextStepElement.classList.add('active');
                    
                    // Handle Features step specific logic
                    if (this.currentStep === 3) {
                        this.currentFeatureCard = 1;
                        this.updateCarouselPosition();
                        
                        // Check installation status after a short delay
                        setTimeout(() => {
                            this.checkInstallationStatus();
                        }, 300);
                    }
                    
                    // Add entrance animation to glass box
                    const glassBox = nextStepElement.querySelector('.glass-box');
                    if (glassBox) {
                        setTimeout(() => {
                            glassBox.classList.add('entrance-animation');
                            setTimeout(() => {
                                glassBox.classList.remove('entrance-animation');
                            }, 800);
                        }, 100);
                    }
                });
                
                // Clean up after animation completes
                setTimeout(() => {
                    currentStepElement.style.display = 'none';
                    currentStepElement.classList.remove('slide-out-left', 'slide-in-right', 'slide-in-left');
                    nextStepElement.classList.remove('slide-in-right', 'slide-in-left');
                    this.isTransitioning = false;
                }, 400);
            } else {
                this.currentStep++;
                this.isTransitioning = false;
            }
        },

        // Navigate to previous step with animation
        previousStep() {
            if (this.isTransitioning || this.currentStep <= 1) return;
            
            this.isTransitioning = true;
            
            const currentStepElement = document.querySelector(`.step-content:nth-child(${this.currentStep})`);
            const prevStepElement = document.querySelector(`.step-content:nth-child(${this.currentStep - 1})`);
            
            if (currentStepElement && prevStepElement) {
                // Update step counter immediately
                this.currentStep--;
                
                // Prepare previous step (hidden but ready)
                prevStepElement.style.display = 'block';
                prevStepElement.classList.add('slide-in-left');
                
                // Start animations in next frame
                requestAnimationFrame(() => {
                    // Slide out current step
                    currentStepElement.classList.add('slide-out-right');
                    currentStepElement.classList.remove('active');
                    
                    // Slide in previous step
                    prevStepElement.classList.add('active');
                    
                    // Add entrance animation to glass box
                    const glassBox = prevStepElement.querySelector('.glass-box');
                    if (glassBox) {
                        setTimeout(() => {
                            glassBox.classList.add('entrance-animation');
                            setTimeout(() => {
                                glassBox.classList.remove('entrance-animation');
                            }, 800);
                        }, 100);
                    }
                });
                
                // Clean up after animation completes
                setTimeout(() => {
                    currentStepElement.style.display = 'none';
                    currentStepElement.classList.remove('slide-out-left', 'slide-out-right', 'slide-in-right', 'slide-in-left');
                    prevStepElement.classList.remove('slide-in-right', 'slide-in-left');
                    this.isTransitioning = false;
                }, 400);
            } else {
                this.currentStep--;
                this.isTransitioning = false;
            }
        },

        // Load import settings
        async loadImportSettings() {
            try {
                const response = await fetch(`/api/settings/${globalActiveProfileId}`);
                if (response.ok) {
                    const result = await response.json();
                    if (result.data) {
                        if (result.data.outputFormat) {
                            this.outputFormat = result.data.outputFormat;
                        }
                        if (result.data.downloadThreads) {
                            this.downloadThreads = result.data.downloadThreads;
                        }
                        if (result.data.searchThreads) {
                            this.searchThreads = result.data.searchThreads;
                        }
                    }
                }
            } catch (error) {
                console.error('Error loading import settings:', error);
            }
        },

        // Navigate to next feature card
        nextFeatureCard() {
            console.log('nextFeatureCard called, current:', this.currentFeatureCard);
            const maxCards = this.getMaxFeatureCards();
            if (this.currentFeatureCard < maxCards) {
                this.currentFeatureCard++;
                console.log('nextFeatureCard advanced to:', this.currentFeatureCard);
                this.updateCarouselPosition();
            }
        },

        // Navigate to previous feature card
        previousFeatureCard() {
            if (this.currentFeatureCard > 1) {
                this.currentFeatureCard--;
                this.updateCarouselPosition();
            }
        },
        
        // Get maximum number of feature cards
        getMaxFeatureCards() {
            return 5; // Fixed number of cards since Import Settings is now inline
        },

        // Update carousel position
        updateCarouselPosition() {
            const allSlides = document.querySelectorAll('.feature-card-slide');
            allSlides.forEach((slide, index) => {
                if (index === this.currentFeatureCard - 1) {
                    slide.style.display = 'flex';
                } else {
                    slide.style.display = 'none';
                }
            });
        },

        // Skip import features configuration
        skipImportFeatures() {
            this.installImportFeatures = false;
            // Jump directly to the last feature card (Setup Complete)
            this.currentFeatureCard = 5;
        },

        

        // Load existing settings
        async loadExistingSettings() {
            try {
                const response = await fetch(`/api/settings/${globalActiveProfileId}`);
                if (response.ok) {
                    const result = await response.json();
                    if (result.data) {
                        console.log('[Setup] Loading settings from API:', result.data);
                        if (result.data.libraryPath) {
                            this.musicLibraryPath = result.data.libraryPath;
                        }
                        if (result.data.videoLibraryPath) {
                            this.videoLibraryPath = result.data.videoLibraryPath;
                        }
                        if (result.data.runAsService !== undefined) {
                            console.log('[Setup] Setting runAsService to:', result.data.runAsService);
                            this.runAsService = result.data.runAsService;
                        } else {
                            console.log('[Setup] runAsService not found in response data');
                        }
                    }
                }
            } catch (error) {
                console.error('Error loading existing settings:', error);
            }
        },

        // Check if setup is needed
        async checkSetupStatus() {
            try {
                const response = await fetch('/api/setup/status');
                const result = await response.json();
                
                if (result.success && !result.data.isFirstTimeSetup) {
                    // Setup already completed, redirect to main app
                    window.location.href = '/';
                }
            } catch (error) {
                console.error('Error checking setup status:', error);
            }
        },

        // Browse for folder
        async browseFolder(type) {
            try {
                // Use settings API endpoints instead of setup API
                const endpoint = type === 'music' ? 
                    `/api/settings/${globalActiveProfileId}/browse-folder` : 
                    `/api/settings/${globalActiveProfileId}/browse-video-folder`;
                
                this.setLoading(true, 'Opening folder browser...');
                
                const response = await fetch(endpoint);
                
                // Handle case where user cancels folder selection (NO_CONTENT status)
                if (response.status === 204) {
                    console.log("[Setup] Folder selection cancelled by user");
                    return; // Silently return - no notification needed for cancel
                }
                
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
                }
                
                const result = await response.json();
                
                if (result.data) {
                    if (type === 'music') {
                        this.musicLibraryPath = result.data;
                    } else {
                        this.videoLibraryPath = result.data;
                    }
                } else {
                    this.showError('No folder selected');
                }
            } catch (error) {
                console.error('Error browsing folder:', error);
                this.showError('Failed to open folder browser');
            } finally {
                this.setLoading(false);
            }
        },

        // Validate paths and proceed to next step
        async validateAndNext() {
            if (!this.musicLibraryPath || this.musicLibraryPath.trim() === '') {
                this.showError('Please select a music library folder');
                return;
            }

            try {
                this.setLoading(true, 'Validating paths...');
                
                const response = await fetch(`/api/settings/${globalActiveProfileId}/validate-paths`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        musicLibraryPath: this.musicLibraryPath,
                        videoLibraryPath: this.videoLibraryPath
                    })
                });
                
                const result = await response.json();
                
                if (result.data) {
                    const validation = result.data;
                    
                    if (!validation.musicLibraryValid) {
                        this.showError('Music library path is not valid or does not exist');
                        return;
                    }
                    
                    if (this.videoLibraryPath && !validation.videoLibraryValid) {
                        this.showError('Video library path is not valid or does not exist');
                        return;
                    }
                    
                    // All validations passed, move to next step
                    this.nextStep();
                } else {
                    this.showError('Failed to validate paths');
                }
            } catch (error) {
                console.error('Error validating paths:', error);
                this.showError('Failed to validate paths');
            } finally {
                this.setLoading(false);
            }
        },

        // Check installation status for import features
        async checkInstallationStatus() {
            try {
                // Use same endpoint as settings page with profile context
                const response = await fetch(`/api/settings/${globalActiveProfileId}/install-status`);
                const result = await response.json();
                
                console.log('[Setup] Installation status response:', result);
                console.log('[Setup] Response status:', response.status);
                console.log('[Setup] Response ok:', response.ok);
                console.log('[Setup] Result structure:', {
                    hasSuccess: 'success' in result,
                    successValue: result.success,
                    hasData: 'data' in result,
                    dataValue: result.data,
                    allKeys: Object.keys(result)
                });
                
                // Handle different response structures
                let status = null;
                if (result.success && result.data) {
                    // Standard API response format
                    status = result.data;
                } else if (result.data) {
                    // Direct data response (no success wrapper)
                    status = result.data;
                } else if (result.pythonInstalled !== undefined) {
                    // Direct status response (no wrapper)
                    status = result;
                } else {
                    console.error('[Setup] Unexpected response structure:', result);
                    return;
                }
                
                // Ensure we have the expected properties
                if (status.pythonInstalled === undefined) {
                    console.error('[Setup] Invalid status object - missing pythonInstalled property');
                    return;
                }
                
                console.log('[Setup] Status data:', status);
                console.log('[Setup] Individual statuses:', {
                    pythonInstalled: status.pythonInstalled,
                    ffmpegInstalled: status.ffmpegInstalled,
                    spotdlInstalled: status.spotdlInstalled,
                    whisperInstalled: status.whisperInstalled
                });
                this.updateInstallStatus(status);
            } catch (error) {
                console.error('[Setup] Error checking installation status:', error);
            }
        },

        // Update installation status display
        updateInstallStatus(status) {
            console.log('[Setup] updateInstallStatus called with:', status);
            
            // Call the global function with multiple retries to ensure DOM is ready
            let retryCount = 0;
            const maxRetries = 20;
            const retryDelay = 100;
            
            const tryUpdate = () => {
                retryCount++;
                console.log(`[Setup] Attempting to update installation status (attempt ${retryCount}/${maxRetries})`);
                
                const pythonStatus = document.getElementById('pythonStatus');
                const ffmpegStatus = document.getElementById('ffmpegStatus');
                const spotdlStatus = document.getElementById('spotdlStatus');
                const whisperStatus = document.getElementById('whisperStatus');
                
                if (pythonStatus && ffmpegStatus && spotdlStatus && whisperStatus) {
                    console.log('[Setup] All DOM elements found, updating status');
                    updateInstallationStatusElements(status);
                    
                    // Show/hide Import Settings card based on SpotDL installation
                    this.updateImportSettingsCardVisibility(status);
                } else if (retryCount < maxRetries) {
                    console.log('[Setup] DOM elements not ready, retrying...', {
                        pythonStatus: !!pythonStatus,
                        ffmpegStatus: !!ffmpegStatus,
                        spotdlStatus: !!spotdlStatus,
                        whisperStatus: !!whisperStatus
                    });
                    setTimeout(tryUpdate, retryDelay);
                } else {
                    console.error('[Setup] Failed to find DOM elements after maximum retries');
                }
            };
            
            // Start immediately, then retry if needed
            tryUpdate();
        },
        
        // Update Import Settings section visibility based on SpotDL installation
        updateImportSettingsCardVisibility(status) {
            console.log('[Setup] updateImportSettingsCardVisibility called with:', status);
            
            // Find the Import Settings section by its ID
            const importSettingsSection = document.getElementById('importSettingsSection');
            console.log('[Setup] Import Settings section found:', !!importSettingsSection);
            console.log('[Setup] SpotDL installed status:', status.spotdlInstalled);
            
            if (importSettingsSection) {
                if (status.spotdlInstalled) {
                    // SpotDL is installed - show Import Settings section
                    importSettingsSection.style.display = 'block';
                    console.log('[Setup] Import Settings section shown - SpotDL is installed');
                } else {
                    // SpotDL not installed - hide Import Settings section
                    importSettingsSection.style.display = 'none';
                    console.log('[Setup] Import Settings section hidden - SpotDL not installed');
                }
            } else {
                console.log('[Setup] Import Settings section not found');
            }
        },

        // Complete setup
        async completeSetup() {
            try {
                console.log('Starting setup completion...');
                this.setLoading(true, 'Completing setup...');
                
                const params = new URLSearchParams();
                params.append('musicLibraryPath', this.musicLibraryPath);
                if (this.videoLibraryPath) {
                    params.append('videoLibraryPath', this.videoLibraryPath);
                }
                params.append('installImportFeatures', this.installImportFeatures);
                params.append('outputFormat', this.outputFormat);
                params.append('downloadThreads', this.downloadThreads.toString());
                params.append('searchThreads', this.searchThreads.toString());
                params.append('runAsService', this.runAsService);
                
                console.log('Sending setup data:', {
                    musicLibraryPath: this.musicLibraryPath,
                    videoLibraryPath: this.videoLibraryPath,
                    installImportFeatures: this.installImportFeatures,
                    outputFormat: this.outputFormat,
                    downloadThreads: this.downloadThreads,
                    searchThreads: this.searchThreads
                });
                
                const response = await fetch('/api/setup/complete', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded',
                    },
                    body: params
                });
                
                console.log('Setup response status:', response.status);
                const result = await response.json();
                console.log('Setup response data:', result);
                console.log('Setup response data.data:', result.data);
                
                if (result.data && result.data.success) {
                    console.log('Setup completed successfully');
                    // Setup completed successfully
                    if (this.installImportFeatures) {
                        console.log('Installing requirements...');
                        this.setLoading(true, 'Installing dependencies... This may take a few minutes...');
                        await this.installRequirements();
                    }
                    
                    console.log('Redirecting to main app...');
                    // Redirect to main app
                    window.location.href = '/';
                } else {
                    console.error('Setup failed:', result.data ? result.data.message : 'Unknown error');
                    this.showError('Failed to complete setup: ' + (result.data ? result.data.message : 'Unknown error'));
                }
            } catch (error) {
                console.error('Error completing setup:', error);
                this.showError('Failed to complete setup: ' + error.message);
            } finally {
                this.setLoading(false);
            }
        },

        // Install requirements
        async installRequirements() {
            try {
                const response = await fetch(`/api/settings/${globalActiveProfileId}/install-requirements`, {
                    method: 'POST'
                });
                
                const result = await response.json();
                
                if (result.success) {
                    // Start polling for installation status
                    this.pollInstallationStatus();
                } else {
                    this.showError('Failed to start installation');
                }
            } catch (error) {
                console.error('Error installing requirements:', error);
                this.showError('Failed to install requirements');
            }
        },

        // Poll installation status
        pollInstallationStatus() {
            const poll = async () => {
                try {
                    const response = await fetch(`/api/settings/${globalActiveProfileId}/install-status`);
                    const result = await response.json();
                    
                    if (result.success) {
                        this.updateInstallStatus(result.data);
                        
                        if (result.data.allInstalled || result.data.isAllInstalled) {
                            this.setLoading(false);
                            return;
                        }
                    }
                    
                    // Continue polling
                    setTimeout(poll, 2000);
                } catch (error) {
                    console.error('Error polling installation status:', error);
                    setTimeout(poll, 5000);
                }
            };
            
            poll();
        },

        // Set loading state
        setLoading(loading, message = '') {
            this.loading = loading;
            this.loadingMessage = message;
        },

        // Show error message
        showError(message) {
            const errorDiv = document.getElementById('validationError');
            const messagesDiv = document.getElementById('validationMessages');
            
            if (errorDiv && messagesDiv) {
                errorDiv.textContent = message;
                messagesDiv.classList.remove('hidden');
                
                // Auto-hide after 5 seconds
                setTimeout(() => {
                    messagesDiv.classList.add('hidden');
                }, 5000);
            }
        }
    });
});

// Global function for install button
// Global function to update installation status elements (outside Alpine store scope)
function updateInstallationStatusElements(status) {
    console.log('[Setup] DOM update attempt with status:', status);
    
    // Update individual status elements
    const pythonStatus = document.getElementById('pythonStatus');
    const ffmpegStatus = document.getElementById('ffmpegStatus');
    const spotdlStatus = document.getElementById('spotdlStatus');
    const whisperStatus = document.getElementById('whisperStatus');
    
    console.log('[Setup] Found elements:', {
        pythonStatus: !!pythonStatus,
        ffmpegStatus: !!ffmpegStatus,
        spotdlStatus: !!spotdlStatus,
        whisperStatus: !!whisperStatus
    });

    // If any elements are missing, don't proceed
    if (!pythonStatus || !ffmpegStatus || !spotdlStatus || !whisperStatus) {
        console.log('[Setup] Some elements not found, skipping update');
        return;
    }
    
    if (pythonStatus) {
        const isInstalled = Boolean(status.pythonInstalled);
        console.log('[Setup] Updating Python status:', isInstalled, '(raw:', status.pythonInstalled, ')');
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        
        pythonStatus.textContent = newText;
        pythonStatus.className = newClass;
    }
    
    if (ffmpegStatus) {
        const isInstalled = Boolean(status.ffmpegInstalled);
        console.log('[Setup] Updating FFmpeg status:', isInstalled, '(raw:', status.ffmpegInstalled, ')');
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        
        ffmpegStatus.textContent = newText;
        ffmpegStatus.className = newClass;
    }
    
    if (spotdlStatus) {
        const isInstalled = Boolean(status.spotdlInstalled);
        console.log('[Setup] Updating SpotDL status:', isInstalled, '(raw:', status.spotdlInstalled, ')');
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        
        spotdlStatus.textContent = newText;
        spotdlStatus.className = newClass;
    }
    
    if (whisperStatus) {
        const isInstalled = Boolean(status.whisperInstalled);
        console.log('[Setup] Updating Whisper status:', isInstalled, '(raw:', status.whisperInstalled, ')');
        const newText = isInstalled ? '✅ Installed' : '❌ Not installed';
        const newClass = isInstalled ? 'help is-size-7 mt-2 has-text-success' : 'help is-size-7 mt-2 has-text-danger';
        
        whisperStatus.textContent = newText;
        whisperStatus.className = newClass;
    }
    
    // Update button states (setup only shows Install, not Remove)
    const installPythonBtn = document.getElementById('installPythonBtn');
    const installFfmpegBtn = document.getElementById('installFfmpegBtn');
    const installSpotdlBtn = document.getElementById('installSpotdlBtn');
    const installWhisperBtn = document.getElementById('installWhisperBtn');
    
    if (installPythonBtn) {
        if (status.pythonInstalled) {
            installPythonBtn.textContent = '✅ Python Installed';
            installPythonBtn.className = 'button is-success is-rounded is-small';
            installPythonBtn.disabled = true;
        } else {
            installPythonBtn.textContent = 'Install Python';
            installPythonBtn.className = 'button is-success is-rounded is-small';
            installPythonBtn.disabled = false;
        }
    }
    
    if (installFfmpegBtn) {
        if (status.ffmpegInstalled) {
            installFfmpegBtn.textContent = '✅ FFmpeg Installed';
            installFfmpegBtn.className = 'button is-success is-rounded is-small';
            installFfmpegBtn.disabled = true;
        } else {
            installFfmpegBtn.textContent = 'Install FFmpeg';
            installFfmpegBtn.className = 'button is-success is-rounded is-small';
            installFfmpegBtn.disabled = false;
        }
    }
    
    if (installSpotdlBtn) {
        if (status.spotdlInstalled) {
            installSpotdlBtn.textContent = '✅ SpotDL Installed';
            installSpotdlBtn.className = 'button is-success is-rounded is-small';
            installSpotdlBtn.disabled = true;
        } else {
            installSpotdlBtn.textContent = 'Install SpotDL';
            installSpotdlBtn.className = 'button is-success is-rounded is-small';
            installSpotdlBtn.disabled = false;
        }
    }
    
    if (installWhisperBtn) {
        if (status.whisperInstalled) {
            installWhisperBtn.textContent = '✅ Whisper Installed';
            installWhisperBtn.className = 'button is-success is-rounded is-small';
            installWhisperBtn.disabled = true;
        } else {
            installWhisperBtn.textContent = 'Install Whisper';
            installWhisperBtn.className = 'button is-success is-rounded is-small';
            installWhisperBtn.disabled = false;
        }
    } 
    

    
    console.log('[Setup] Updated individual status elements');
}

function installRequirements() {
    Alpine.store('setup').installRequirements();
}

// Helper function to get current step from Alpine
function getCurrentStep() {
    return Alpine.store('setup').currentStep || 1;
}