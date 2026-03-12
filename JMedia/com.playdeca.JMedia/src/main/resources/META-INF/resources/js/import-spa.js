window.updateHmxUrls = function() {
    const searchSuggestionsDropdown = document.getElementById('searchSuggestionsDropdown');
    if (searchSuggestionsDropdown && window.globalActiveProfileId) {
        searchSuggestionsDropdown.setAttribute('hx-post', `/api/music/ui/search-suggestions/${window.globalActiveProfileId}`);
        // Re-process HTMX if needed, but usually attributes are enough if triggered later
        if (window.htmx) htmx.process(searchSuggestionsDropdown);
    }
};

window.uploadCookies = function() {
    const profileId = window.globalActiveProfileId;
    const cookiesContent = document.getElementById('cookiesContent').value.trim();

    if (!cookiesContent) {
        if(window.showToast) window.showToast('Please enter cookies content', 'warning');
        return;
    }

    const uploadBtn = document.getElementById('uploadCookiesBtn');
    const originalText = uploadBtn.innerHTML;
    uploadBtn.disabled = true;
    uploadBtn.innerHTML = '<span class="icon"><i class="pi pi-spin pi-spinner"></i></span> Saving...';

    fetch(`/api/settings/${profileId}/upload-cookies`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({cookiesContent: cookiesContent})
    })
            .then(response => response.json())
            .then(data => {
                if (data.error) {
                    if(window.showToast) window.showToast('Failed to save cookies: ' + data.error, 'error');
                } else {
                    if(window.showToast) window.showToast('Cookies file saved successfully', 'success');
                    document.getElementById('cookiesContent').value = '';
                }
            })
            .catch(error => {
                console.error('Error uploading cookies:', error);
                if(window.showToast) window.showToast('Error uploading cookies', 'error');
            })
            .finally(() => {
                uploadBtn.disabled = false;
                uploadBtn.innerHTML = originalText;
            });
};

window.deleteCookies = function() {
    if (!confirm('Are you sure you want to delete the cookies file?')) {
        return;
    }

    const profileId = window.globalActiveProfileId;
    const deleteBtn = document.getElementById('deleteCookiesBtn');
    const originalText = deleteBtn.innerHTML;
    deleteBtn.disabled = true;
    deleteBtn.innerHTML = '<span class="icon"><i class="pi pi-spin pi-spinner"></i></span> Deleting...';

    fetch(`/api/settings/${profileId}/cookies`, {
        method: 'DELETE'
    })
            .then(response => response.json())
            .then(data => {
                if (data.error) {
                    if(window.showToast) window.showToast('Failed to delete cookies: ' + data.error, 'error');
                } else {
                    if(window.showToast) window.showToast('Cookies file deleted successfully', 'success');
                }
            })
            .catch(error => {
                console.error('Error deleting cookies:', error);
                if(window.showToast) window.showToast('Error deleting cookies', 'error');
            })
            .finally(() => {
                deleteBtn.disabled = false;
                deleteBtn.innerHTML = originalText;
            });
};

window.uploadCookiesFile = function(file) {
    if (!file) {
        console.error('No file provided');
        return;
    }

    const profileId = window.globalActiveProfileId;
    console.log('Uploading cookie file:', file.name, 'for profile:', profileId);
    
    const reader = new FileReader();
    
    reader.onload = function(e) {
        const cookiesContent = e.target.result;
        console.log('File content length:', cookiesContent.length);
        
        fetch(`/api/settings/${profileId}/upload-cookies`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({cookiesContent: cookiesContent})
        })
        .then(response => {
            console.log('Response status:', response.status);
            return response.json();
        })
        .then(data => {
            console.log('Response data:', data);
            if (data.error) {
                if(window.showToast) window.showToast('Failed to upload cookies: ' + data.error, 'error');
            } else {
                if(window.showToast) window.showToast('Cookies file uploaded successfully', 'success');
            }
        })
        .catch(error => {
            console.error('Error uploading cookies file:', error);
            if(window.showToast) window.showToast('Error uploading cookies file', 'error');
        });
    };
    
    reader.onerror = function(e) {
        console.error('FileReader error:', e);
        if(window.showToast) window.showToast('Error reading file', 'error');
    };
    
    reader.readAsText(file);
};

window.initImportView = function() {
    console.log("Initializing Import View");
    
    // Admin check logic
    (async () => {
        try {
            console.log('[Admin Check] Starting...');
            const adminRes = await fetch('/api/auth/is-admin');
            const adminJson = await adminRes.json();

            const adminElements = document.querySelectorAll('.admin-only');
            
            if (!adminJson.data || !adminJson.data.isAdmin) {
                adminElements.forEach(el => el.style.display = 'none');
            } else {
                adminElements.forEach(el => {
                    el.style.display = el.classList.contains('nav-item') ? 'flex' : 'block';
                });
            }
        } catch (e) {
            console.error('[Admin Check] Error:', e);
            document.querySelectorAll('.admin-only').forEach(el => {
                el.style.display = 'none';
            });
        }
    })();

    // Warning dismissal
    const externalAppWarning = document.getElementById('externalAppWarning');
    const dismissWarningBtn = externalAppWarning ? externalAppWarning.querySelector('.delete') : null;
    const spotdlWarningShownKey = 'spotdlWarningShown';

    if (externalAppWarning) {
        if (localStorage.getItem(spotdlWarningShownKey) === 'true') {
            externalAppWarning.classList.add('is-hidden');
        } else {
            externalAppWarning.classList.remove('is-hidden');
        }

        if (dismissWarningBtn) {
            dismissWarningBtn.addEventListener('click', () => {
                externalAppWarning.classList.add('is-hidden');
                localStorage.setItem(spotdlWarningShownKey, 'true');
            });
        }
    }
    
    // Cookie Buttons
    const uploadBtn = document.getElementById('uploadCookiesBtn');
    const deleteBtn = document.getElementById('deleteCookiesBtn');
    const cookieFileInput = document.getElementById('cookieFileInput');

    if (uploadBtn) {
        uploadBtn.onclick = window.uploadCookies;
    }

    if (deleteBtn) {
        deleteBtn.onclick = window.deleteCookies;
    }

    if (cookieFileInput) {
        cookieFileInput.onchange = function(e) {
            const file = e.target.files[0];
            if (file) {
                document.getElementById('cookieFileName').textContent = file.name;
                window.uploadCookiesFile(file);
            }
        };
    }
    
    // Update HTMX URLs
    window.updateHmxUrls();
    
    // Global Search Logic (Similar to other pages, but might need re-attach)
    const globalSearchInput = document.getElementById('globalSearchInput');
    const globalSearchDropdown = document.getElementById('globalSearchDropdown');

    function showDropdown() {
        if (globalSearchInput && globalSearchInput.value.trim() !== '' && globalSearchDropdown && globalSearchDropdown.querySelector('.dropdown-item, .htmx-indicator')) {
            globalSearchDropdown.style.display = 'block';
        } else if (globalSearchDropdown) {
            globalSearchDropdown.style.display = 'none';
        }
    }

    function hideDropdown() {
        setTimeout(() => {
            if (globalSearchInput && !globalSearchInput.matches(':focus')) {
                if (globalSearchDropdown) globalSearchDropdown.style.display = 'none';
            }
        }, 100);
    }

    if (globalSearchInput && globalSearchDropdown) {
        globalSearchInput.addEventListener('focus', showDropdown);
        globalSearchInput.addEventListener('keyup', showDropdown);
        globalSearchInput.addEventListener('blur', hideDropdown);

        globalSearchDropdown.addEventListener('mousedown', (event) => {
            event.preventDefault();
        });
    }
    
    // Trigger HTMX if search query exists in URL
    const urlParams = new URLSearchParams(window.location.search);
    const searchQuery = urlParams.get('q');
    if (searchQuery && globalSearchInput) {
        globalSearchInput.value = decodeURIComponent(searchQuery);
        if (window.htmx) htmx.trigger(globalSearchInput, 'search');
        history.replaceState(null, '', window.location.pathname);
    }
};
