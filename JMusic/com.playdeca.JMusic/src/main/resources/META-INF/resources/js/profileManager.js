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
    let currentProfile = null; // Represents the currently active profile object

    // Function to get the current active profile ID from localStorage
    function getActiveProfileId() {
        return localStorage.getItem('activeProfileId') || null;
    }

    // Function to set the active profile ID in localStorage
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

        // If a profile ID is stored in localStorage, try to use it first
        if (storedProfileId) {
            fetch(`/api/profiles/${storedProfileId}`) // Fetch the specific profile
                .then(response => {
                    if (response.ok) {
                        return response.json();
                    }
                    // If stored profile ID is invalid/not found, fetch the backend's current profile
                    return fetch('/api/profiles/current').then(res => res.json());
                })
                .then(profileData => {
                    currentProfile = profileData;
                    // Ensure localStorage reflects the actual current profile from backend
                    setActiveProfileId(currentProfile.id);
                    // Update global variable
                    window.globalActiveProfileId = currentProfile.id;
                    updateProfileDisplay();
                    renderProfileList();
                })
                .catch(error => {
                    console.error('Error fetching current profile (from stored ID or backend):', error);
                    // Fallback if anything goes wrong
                    currentProfile = null;
                    updateProfileDisplay();
                    renderProfileList();
                });
        } else {
            // No profile ID in localStorage, fetch current from backend
            fetch('/api/profiles/current')
                .then(response => response.json())
                .then(profileData => {
                    currentProfile = profileData;
                    setActiveProfileId(currentProfile.id); // Store it for next time
                    // Update global variable
                    window.globalActiveProfileId = currentProfile.id;
                    updateProfileDisplay();
                    renderProfileList();
                })
                .catch(error => console.error('Error fetching current profile:', error));
        }
    }

    function updateProfileDisplay() {
        if (currentProfileNameSpan) {
            currentProfileNameSpan.textContent = currentProfile ? currentProfile.name : 'Loading...';
        }
        if (modalCurrentProfileNameSpan) {
            modalCurrentProfileNameSpan.textContent = currentProfile ? currentProfile.name : 'Loading...';
        }
        // Also update the global Alpine store
        if (Alpine.store('profile')) {
            Alpine.store('profile').currentProfile = currentProfile;
        }
    }

    function renderProfileList() {
        if (!profileListDiv) return;

        profileListDiv.innerHTML = ''; // Clear previous list
        allProfiles.forEach(profile => {
            const profileItem = document.createElement('div'); // Changed from 'a' to 'div'
            profileItem.className = 'tag is-medium is-rounded is-clickable'; // Bulma classes for circular button
            profileItem.style.marginRight = '0.5rem'; // Spacing between circles
            profileItem.style.marginBottom = '0.5rem'; // For multiline support
            profileItem.style.fontWeight = 'bold';
            profileItem.style.textTransform = 'uppercase';
            profileItem.style.position = 'relative'; // For positioning the checkmark
            profileItem.style.cursor = 'pointer'; // Indicate clickability

            // Display first letter of profile name
            // Ensure name is trimmed and not empty before getting charAt(0)
            const displayChar = profile.name && profile.name.trim().length > 0 ? profile.name.trim().charAt(0) : '?';
            profileItem.textContent = displayChar;
            profileItem.title = profile.name; // Tooltip for full name

            if (currentProfile && profile.id === currentProfile.id) {
                profileItem.classList.add('is-primary'); // Highlight active profile
                profileItem.style.color = 'white'; // Explicitly set text color
                // Green checkmark
                const checkIcon = document.createElement('span');
                checkIcon.className = 'icon is-small';
                checkIcon.style.position = 'absolute';
                checkIcon.style.top = '0';
                checkIcon.style.right = '0';
                checkIcon.style.transform = 'translate(50%, -50%)'; // Offset checkmark
                checkIcon.style.color = 'hsl(141, 53%, 53%)'; // Bulma green
                checkIcon.style.backgroundColor = 'white';
                checkIcon.style.borderRadius = '50%';
                checkIcon.style.padding = '2px';
                checkIcon.innerHTML = '<i class="pi pi-check is-size-7"></i>'; // Smaller icon
                profileItem.appendChild(checkIcon);
            } else {
                 profileItem.classList.add('is-light'); // Non-active profiles
                 profileItem.style.color = 'hsl(0, 0%, 21%)'; // Explicitly set dark text color for light tag
            }

            profileItem.onclick = () => switchProfile(profile.id);
            profileListDiv.appendChild(profileItem);
        });
        // Update delete button state based on currentProfile
        if (deleteCurrentProfileBtn) {
            if (currentProfile && currentProfile.isMainProfile) {
                deleteCurrentProfileBtn.style.display = 'none'; // Hide the button
            } else {
                deleteCurrentProfileBtn.style.display = ''; // Show the button
            }
        }
    }

function switchProfile(profileId) {
        // Update localStorage immediately
        setActiveProfileId(profileId);
        // Update global variable immediately for any code that might access it before reload
        if (typeof window !== 'undefined') {
            window.globalActiveProfileId = profileId;
        }
        // Then call the backend to actually switch to profile (important for server-side state consistency)
        fetch(`/api/profiles/switch/${profileId}`, { method: 'POST' })
            .then(() => {
                // Dispatch custom event after successful profile switch
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
            alert('Please enter a profile name.');
            return;
        }
        fetch('/api/profiles', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' }, // Changed content type
            body: name // Send as plain text
        })
        .then(response => {
            if (response.ok) {
                newProfileNameInput.value = '';
                fetchProfiles();
            } else {
                response.text().then(text => alert(`Error: ${text}`));
            }
        })
        .catch(error => console.error('Error creating profile:', error));
    }

    function deleteCurrentProfile() {
        if (!currentProfile || currentProfile.isMainProfile) {
            alert("Cannot delete the main profile.");
            return;
        }
        if (confirm(`Are you sure you want to delete the profile "${currentProfile.name}"? Playlists and history will be moved to the Main profile.`)) {
            fetch(`/api/profiles/${currentProfile.id}`, { method: 'DELETE' })
                .then(response => {
                    if (response.ok) {
                        fetchProfiles();
                        // After deleting, ensure the active profile is still valid, or switch to main
                        const storedProfileId = getActiveProfileId();
                        if (!storedProfileId || storedProfileId === currentProfile.id.toString()) {
                            // If the deleted profile was active, switch to main
                            let mainProfile = allProfiles.find(p => p.isMainProfile);
                            if (mainProfile) {
                                switchProfile(mainProfile.id); // This will reload the page
                            } else {
                                // Should not happen, but as fallback, reload to clear state
                                localStorage.removeItem('activeProfileId');
                                location.reload();
                            }
                        } else {
                            // Another profile was active, just refresh current profile data
                            fetchCurrentProfile();
                            location.reload(); // Reload to reflect changes in other parts of UI
                        }
                    } else {
                        response.text().then(text => alert(`Error: ${text}`));
                    }
                })
                .catch(error => console.error('Error deleting profile:', error));
        }
    }

    // Event Listeners
    if (openProfileModalBtn && profileModal) {
        openProfileModalBtn.onclick = () => {
            profileModal.classList.add('is-active');
            fetchProfiles(); // Refresh list every time modal opens
            // fetchCurrentProfile(); // This is called on DOMContentLoaded, just update display
            updateProfileDisplay(); // Ensure modal display is current
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
        const modalFooterCloseButton = profileModal.querySelector('.modal-card-foot .button');

        if (modalBackground) {
            modalBackground.onclick = () => profileModal.classList.remove('is-active');
        }
        if (modalCloseButton) {
            modalCloseButton.onclick = () => profileModal.classList.remove('is-active');
        }
        if (modalFooterCloseButton) {
            modalFooterCloseButton.onclick = () => profileModal.classList.remove('is-active');
        }
    }


    // Initialize globalActiveProfileId from localStorage
    const storedProfileId = getActiveProfileId();
    if (storedProfileId) {
        window.globalActiveProfileId = storedProfileId;
    }

    // Initial fetches (modified to respect localStorage)
    fetchProfiles();
    fetchCurrentProfile();
});
