(function(window) {
    'use strict';

    const JMedia = window.JMedia = window.JMedia || {};

    JMedia.ProfileManager = {};

    document.addEventListener('DOMContentLoaded', () => {
        const profileModal = document.getElementById('profileModal');
        const openProfileModalBtn = document.getElementById('openProfileModalBtn');
        const currentProfileNameSpan = document.getElementById('currentProfileName');
        const modalCurrentProfileNameSpan = document.getElementById('modalCurrentProfileName');
        const profileListDiv = document.getElementById('profileList');
        const newProfileNameInput = document.getElementById('newProfileNameInput');
        const createProfileBtn = document.getElementById('createProfileBtn');
        const deleteCurrentProfileBtn = document.getElementById('deleteCurrentProfileBtn');

        let allProfiles = [];
        let currentProfile = null;

        function getActiveProfileId() {
            return localStorage.getItem('activeProfileId') || null;
        }

        function setActiveProfileId(profileId) {
            localStorage.setItem('activeProfileId', profileId);
        }

        function fetchProfiles() {
            fetch('/api/profiles')
                .then(response => response.json())
                .then(profilesData => {
                    allProfiles = profilesData;
                    renderProfileList();
                })
                .catch(error => console.error('Error fetching profiles:', error));
        }

        function fetchCurrentProfile() {
            const storedProfileId = getActiveProfileId();
            return fetch('/api/profiles/current')
                .then(response => response.json())
                .then(profileData => {
                    currentProfile = profileData;
                    if (storedProfileId && storedProfileId !== profileData.id.toString()) {
                        console.log('[ProfileManager] Stored profile ID differs, fetching specific profile');
                        return fetch(`/api/profiles/${storedProfileId}`)
                            .then(response => {
                                if (response.ok) {
                                    return response.json();
                                }
                                console.log('[ProfileManager] Stored profile invalid, using current');
                                return profileData;
                            })
                            .then(specificProfileData => {
                                currentProfile = specificProfileData;
                                setActiveProfileId(currentProfile.id);
                                window.globalActiveProfileId = currentProfile.id;
                                updateProfileDisplay();
                                renderProfileList();
                                return Promise.resolve();
                            });
                    } else {
                        setActiveProfileId(currentProfile.id);
                        window.globalActiveProfileId = currentProfile.id;
                        updateProfileDisplay();
                        renderProfileList();
                        return Promise.resolve();
                    }
                })
                .catch(error => {
                    console.error('Error fetching current profile:', error);
                    currentProfile = null;
                    updateProfileDisplay();
                    renderProfileList();
                    return Promise.resolve();
                });
        }

        function updateProfileDisplay() {
            if (currentProfileNameSpan) {
                currentProfileNameSpan.textContent = currentProfile ? currentProfile.name : 'Loading...';
            }
            if (modalCurrentProfileNameSpan) {
                modalCurrentProfileNameSpan.textContent = currentProfile ? currentProfile.name : 'Loading...';
            }
            if (Alpine.store('profile')) {
                Alpine.store('profile').currentProfile = currentProfile;
            }
        }

        function renderProfileList() {
            if (!profileListDiv) return;
            profileListDiv.innerHTML = '';
            allProfiles.forEach(profile => {
                const profileItem = document.createElement('div');
                profileItem.className = 'tag is-medium is-rounded is-clickable';
                profileItem.style.marginRight = '0.5rem';
                profileItem.style.marginBottom = '0.5rem';
                profileItem.style.fontWeight = 'bold';
                profileItem.style.textTransform = 'uppercase';
                profileItem.style.position = 'relative';
                profileItem.style.cursor = 'pointer';
                const displayChar = profile.name && profile.name.trim().length > 0 ? profile.name.trim().charAt(0) : '?';
                profileItem.textContent = displayChar;
                profileItem.title = profile.name;
                if (currentProfile && profile.id === currentProfile.id) {
                    profileItem.classList.add('is-primary');
                    profileItem.style.color = 'white';
                    const checkIcon = document.createElement('span');
                    checkIcon.className = 'icon is-small';
                    checkIcon.style.position = 'absolute';
                    checkIcon.style.top = '0';
                    checkIcon.style.right = '0';
                    checkIcon.style.transform = 'translate(50%, -50%)';
                    checkIcon.style.color = 'hsl(141, 53%, 53%)';
                    checkIcon.style.backgroundColor = 'white';
                    checkIcon.style.borderRadius = '50%';
                    checkIcon.style.padding = '2px';
                    checkIcon.innerHTML = '<i class="pi pi-check is-size-7"></i>';
                    profileItem.appendChild(checkIcon);
                } else {
                     profileItem.classList.add('is-light');
                     profileItem.style.color = 'hsl(0, 0%, 21%)';
                }
                profileItem.onclick = () => switchProfile(profile.id);
                profileListDiv.appendChild(profileItem);
            });
            if (deleteCurrentProfileBtn) {
                if (currentProfile && currentProfile.isMainProfile) {
                    deleteCurrentProfileBtn.style.display = 'none';
                } else {
                    deleteCurrentProfileBtn.style.display = '';
                }
            }
        }

        function switchProfile(profileId) {
            setActiveProfileId(profileId);
            if (typeof window !== 'undefined') {
                window.globalActiveProfileId = profileId;
            }
            fetch(`/api/profiles/switch/${profileId}`, { method: 'POST' })
                .then(() => {
                    const event = new Event('profileSwitched');
                    document.body.dispatchEvent(event);
                    location.reload();
                })
                .catch(error => console.error('Error switching profile:', error));
        }

        function createProfile() {
            if (!newProfileNameInput) return;
            const name = newProfileNameInput.value.trim();
            if (!name) {
                JMedia.ToastSystem ? JMedia.ToastSystem.warning('Please enter a profile name.') : Toast.warning('Please enter a profile name.');
                return;
            }
            fetch('/api/profiles', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name })
            })
            .then(response => {
                if (response.ok) {
                    newProfileNameInput.value = '';
                    fetchProfiles();
                } else {
                    response.text().then(text => Toast.error(`Error: ${text}`));
                }
            })
            .catch(error => console.error('Error creating profile:', error));
        }

        function deleteCurrentProfile() {
            if (!currentProfile || currentProfile.isMainProfile) {
                JMedia.ToastSystem ? JMedia.ToastSystem.warning("Cannot delete the main profile.") : Toast.warning("Cannot delete the main profile.");
                return;
            }
            if (confirm(`Are you sure you want to delete the profile "${currentProfile.name}"? Playlists and history will be moved to the Main profile.`)) {
                fetch(`/api/profiles/${currentProfile.id}`, { method: 'DELETE' })
                    .then(response => {
                        if (response.ok) {
                            fetchProfiles();
                            const storedProfileId = getActiveProfileId();
                            if (!storedProfileId || storedProfileId === currentProfile.id.toString()) {
                                let mainProfile = allProfiles.find(p => p.isMainProfile);
                                if (mainProfile) {
                                    switchProfile(mainProfile.id);
                                } else {
                                    localStorage.removeItem('activeProfileId');
                                    location.reload();
                                }
                            } else {
                                fetchCurrentProfile();
                                location.reload();
                            }
                        } else {
                            response.text().then(text => Toast.error(`Error: ${text}`));
                        }
                    })
                    .catch(error => console.error('Error deleting profile:', error));
            }
        }

        if (openProfileModalBtn && profileModal) {
            openProfileModalBtn.onclick = () => {
                profileModal.classList.add('is-active');
                fetchProfiles();
                updateProfileDisplay();
                if (typeof loadHiddenPlaylists === 'function') {
                    loadHiddenPlaylists();
                }
            };
        }
        if (createProfileBtn) {
            createProfileBtn.onclick = createProfile;
        }
        if (deleteCurrentProfileBtn) {
            deleteCurrentProfileBtn.onclick = deleteCurrentProfile;
        }
        if (profileModal) {
            const modalBackground = profileModal.querySelector('.modal-background');
            const modalCloseButton = profileModal.querySelector('.delete');
            const footerButtons = profileModal.querySelectorAll('.modal-card-foot .button');
            if (modalBackground) {
                modalBackground.onclick = () => profileModal.classList.remove('is-active');
            }
            if (modalCloseButton) {
                modalCloseButton.onclick = () => profileModal.classList.remove('is-active');
            }
            footerButtons.forEach(btn => {
                if (btn.id !== 'logoutBtn' && !btn.classList.contains('is-danger')) {
                    btn.onclick = () => profileModal.classList.remove('is-active');
                }
            });
        }

        const storedProfileId = getActiveProfileId();
        if (storedProfileId) {
            window.globalActiveProfileId = storedProfileId;
        }

        window.profileInitialized = false;

        document.body.addEventListener('profileReady', (e) => {
            window.profileInitialized = true;
        });

        Promise.all([
            fetchProfiles(),
            fetchCurrentProfile()
        ]).then(() => {
            window.profileInitialized = true;
            document.body.dispatchEvent(new CustomEvent('profileReady', {
                detail: { profileId: window.globalActiveProfileId }
            }));
        }).catch(error => {
            console.error('[ProfileManager] Profile initialization failed:', error);
            window.profileInitialized = true;
            document.body.dispatchEvent(new CustomEvent('profileReady', {
                detail: { profileId: window.globalActiveProfileId }
            }));
        });
    });

    // Backward-compatible aliases
    JMedia.ProfileManager.createProfile = function(name) {
        // exposed as window.createProfile from settings.js
    };
    JMedia.ProfileManager.deleteProfile = function(id) {
        // exposed as window.deleteProfile from settings.js
    };

})(window);
