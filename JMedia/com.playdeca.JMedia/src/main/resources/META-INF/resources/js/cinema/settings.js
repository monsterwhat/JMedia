// Cinema view: Settings modal, auto-skip toggles, account panel, profile switching.
// Split out of cinema-mode.js. Loaded by views/video.html in a fixed order;
// these are classic scripts, so they share one global scope.
function openSettingsModal() {
  document.getElementById('settings-modal-backdrop').classList.add('active');
  document.getElementById('settings-modal').classList.add('active');
  document.body.style.overflow = 'hidden';
  loadSettings();
}

function closeSettingsModal() {
  document.getElementById('settings-modal-backdrop').classList.remove('active');
  document.getElementById('settings-modal').classList.remove('active');
  document.body.style.overflow = '';
}

function switchSettingsTab(tabName) {
  document.querySelectorAll('.cinema-settings-tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.cinema-settings-panel').forEach(p => p.classList.remove('active'));
  const tab = document.querySelector(`.cinema-settings-tab[data-tab="${tabName}"]`);
  const panel = document.getElementById(`settings-panel-${tabName}`);
  if (tab) tab.classList.add('active');
  if (panel) panel.classList.add('active');
  if (tabName === 'account') {
    loadAccountPanel();
  }
}


function updateCrossfadeDisplay(value) {
  const display = document.getElementById('crossfade-value');
  if (display) display.textContent = `${value}s`;
  localStorage.setItem('video-crossfade', value);
  saveSettingsDebounced();
}

function toggleAutoSkip(type, el) {
  el.classList.toggle('active');
  saveSettingsDebounced();
}

var _saveSettingsTimer;
function saveSettingsDebounced() {
  clearTimeout(_saveSettingsTimer);
  _saveSettingsTimer = setTimeout(saveSettings, 500);
}

function saveSettings() {
  const skipIntro = document.getElementById('skip-intro-toggle')?.classList.contains('active') || false;
  const skipRecap = document.getElementById('skip-recap-toggle')?.classList.contains('active') || false;
  const skipOutro = document.getElementById('skip-outro-toggle')?.classList.contains('active') || false;
  const profileId = localStorage.getItem('activeProfileId');

  fetch(`/api/settings/${profileId}/auto-skip`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      autoSkipIntro: skipIntro,
      autoSkipRecap: skipRecap,
      autoSkipOutro: skipOutro
    })
  }).catch((e) => console.warn('[cinema] auto-skip preference save failed', e));
}

async function loadSettings() {
  try {
    const profileId = localStorage.getItem('activeProfileId');
    const playbackData = await fetchJSON(`/api/settings/${profileId}`);
    if (playbackData) {
      const toggles = { autoSkipIntro: 'skip-intro-toggle', autoSkipRecap: 'skip-recap-toggle', autoSkipOutro: 'skip-outro-toggle' };
      for (const [key, id] of Object.entries(toggles)) {
        const toggle = document.getElementById(id);
        if (toggle && playbackData[key] !== undefined) {
          toggle.classList.toggle('active', !!playbackData[key]);
        }
      }
    }
  } catch (e) {
    console.warn('[cinema] failed to load playback settings; keeping UI defaults', e);
  }

  const savedCrossfade = localStorage.getItem('video-crossfade');
  if (savedCrossfade !== null) {
    const slider = document.getElementById('crossfade-slider');
    if (slider) {
      slider.value = savedCrossfade;
      updateCrossfadeDisplay(parseInt(savedCrossfade, 10));
    }
  }

  try {
    const verData = await fetchJSON('/api/update/latest');
    const verEl = document.getElementById('cinema-version');
    if (verEl) {
      verEl.textContent = verData?.version || 'unknown';
    }
  } catch (e) {
    const verEl = document.getElementById('cinema-version');
    if (verEl) verEl.textContent = 'unknown';
  }
}

// ============================================================
// Profile Management
// ============================================================
async function loadAccountPanel() {
  const list = document.getElementById('profile-list');
  if (!list) return;
  try {
    const res = await fetch('/api/profiles');
    const profiles = await res.json();
    const curRes = await fetch('/api/profiles/current');
    const cur = await curRes.json();
    list.innerHTML = profiles.map(p => `
      <div class="account-profile-item${cur.id === p.id ? ' active' : ''}" onclick="switchProfile(${p.id})">
        <span class="profile-name">${p.name}</span>
        ${p.isMainProfile ? '<span class="profile-badge main">Main</span>' : ''}
        ${cur.id === p.id ? '<span class="profile-badge current">Current</span>' : ''}
      </div>
    `).join('');
  } catch (e) {
    list.innerHTML = '<span style="color:var(--cinema-text-dim);font-size:0.75rem;">Failed to load profiles</span>';
  }
}

function switchProfile(profileId) {
  localStorage.setItem('activeProfileId', profileId);
  window.globalActiveProfileId = profileId;
  fetch(`/api/profiles/switch/${profileId}`, { method: 'POST' })
    .then(() => {
      document.body.dispatchEvent(new Event('profileSwitched'));
      window.location.reload();
    })
    .catch(e => console.error('Error switching profile:', e));
}

function createProfile() {
  const input = document.getElementById('create-profile-input');
  if (!input) return;
  const name = input.value.trim();
  if (!name) return;
  fetch('/api/profiles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: name })
  })
  .then(response => {
    if (response.ok) {
      input.value = '';
      loadAccountPanel();
    }
  })
  .catch(e => console.error('Error creating profile:', e));
}

