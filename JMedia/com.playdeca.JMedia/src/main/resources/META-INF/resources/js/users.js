// User Management JavaScript

async function checkIsAdmin() {
    try {
        const response = await fetch('/api/auth/is-admin');
        const json = await response.json();
        return json.data && json.data.isAdmin;
    } catch (e) {
        console.error('Error checking admin status:', e);
        return false;
    }
}

async function loadUsers() {
    const tableBody = document.getElementById('usersTableBody');
    const loading = document.getElementById('usersLoading');
    const error = document.getElementById('usersError');
    
    if (!tableBody || !loading || !error) return;
    
    try {
        loading.style.display = 'block';
        tableBody.innerHTML = '';
        error.style.display = 'none';
        
        const response = await fetch('/api/users');
        const result = await response.json();
        
        loading.style.display = 'none';
        
        if (!response.ok) {
            error.textContent = result.error || 'Failed to load users';
            error.style.display = 'block';
            return;
        }
        
        const users = result.data || [];
        
        if (users.length === 0) {
            tableBody.innerHTML = '<tr><td colspan="3" class="has-text-centered">No users found</td></tr>';
            return;
        }
        
        users.forEach(user => {
            const isAdmin = user.groupName === 'admin';
            const row = document.createElement('tr');
            row.innerHTML = `
                <td>${escapeHtml(user.username)}</td>
                <td><span class="tag ${isAdmin ? 'is-danger' : 'is-info'}">${escapeHtml(user.groupName || 'user')}</span></td>
                <td>
                    <div class="buttons are-small">
                        <button class="button is-info is-small" onclick="openEditUserModal(${user.id}, '${escapeHtml(user.username)}', '${escapeHtml(user.groupName || 'user')}')">
                            <span class="icon"><i class="pi pi-pencil"></i></span>
                        </button>
                        <button class="button is-danger is-small" onclick="deleteUser(${user.id})" ${isAdmin ? 'disabled title="Cannot delete admin users"' : ''}>
                            <span class="icon"><i class="pi pi-trash"></i></span>
                        </button>
                    </div>
                </td>
            `;
            tableBody.appendChild(row);
        });
        
    } catch (e) {
        console.error('Error loading users:', e);
        loading.style.display = 'none';
        error.textContent = 'Error loading users';
        error.style.display = 'block';
    }
}

function openCreateUserModal() {
    document.getElementById('userModalTitle').textContent = 'Create User';
    document.getElementById('editUserId').value = '';
    document.getElementById('userUsername').value = '';
    document.getElementById('userPassword').value = '';
    document.getElementById('userGroup').value = 'user';
    document.getElementById('passwordHint').textContent = '(required)';
    document.getElementById('userModalError').style.display = 'none';
    document.getElementById('userModal').classList.add('is-active');
}

function openEditUserModal(id, username, groupName) {
    document.getElementById('userModalTitle').textContent = 'Edit User';
    document.getElementById('editUserId').value = id;
    document.getElementById('userUsername').value = username;
    document.getElementById('userPassword').value = '';
    document.getElementById('userGroup').value = groupName || 'user';
    document.getElementById('passwordHint').textContent = '(leave empty to keep current)';
    document.getElementById('userModalError').style.display = 'none';
    document.getElementById('userModal').classList.add('is-active');
}

function closeUserModal() {
    document.getElementById('userModal').classList.remove('is-active');
}

async function saveUser() {
    const userId = document.getElementById('editUserId').value;
    const username = document.getElementById('userUsername').value.trim();
    const password = document.getElementById('userPassword').value;
    const groupName = document.getElementById('userGroup').value;
    const errorDiv = document.getElementById('userModalError');
    
    errorDiv.style.display = 'none';
    
    if (!username) {
        errorDiv.textContent = 'Username is required';
        errorDiv.style.display = 'block';
        return;
    }
    
    if (!userId && !password) {
        errorDiv.textContent = 'Password is required';
        errorDiv.style.display = 'block';
        return;
    }
    
    const url = userId ? `/api/users/${userId}` : '/api/users';
    const method = userId ? 'PUT' : 'POST';
    
    const body = {};
    if (username) body.username = username;
    if (password) body.password = password;
    if (groupName) body.groupName = groupName;
    
    try {
        const response = await fetch(url, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        
        const result = await response.json();
        
        if (!response.ok) {
            errorDiv.textContent = result.error || 'Failed to save user';
            errorDiv.style.display = 'block';
            return;
        }
        
        closeUserModal();
        loadUsers();
        Toast.success(userId ? 'User updated successfully' : 'User created successfully');
        
    } catch (e) {
        console.error('Error saving user:', e);
        errorDiv.textContent = 'Error saving user';
        errorDiv.style.display = 'block';
    }
}

async function deleteUser(id) {
    if (!confirm('Are you sure you want to delete this user?')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/users/${id}`, {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (!response.ok) {
            Toast.error(result.error || 'Failed to delete user');
            return;
        }
        
        loadUsers();
        Toast.success('User deleted successfully');
        
    } catch (e) {
        console.error('Error deleting user:', e);
        Toast.error('Error deleting user');
    }
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

document.addEventListener('DOMContentLoaded', async () => {
    const isAdmin = await checkIsAdmin();
    
    if (!isAdmin) {
        const userTab = document.querySelector('li[data-tab="user-management"]');
        if (userTab) {
            userTab.style.display = 'none';
        }
        return;
    }
    
    const createUserBtn = document.getElementById('createUserBtn');
    if (createUserBtn) {
        createUserBtn.onclick = openCreateUserModal;
    }
    
    const saveUserBtn = document.getElementById('saveUserBtn');
    if (saveUserBtn) {
        saveUserBtn.onclick = saveUser;
    }
    
    const userTabButton = document.querySelector('li[data-tab="user-management"]');
    if (userTabButton) {
        userTabButton.addEventListener('click', function() {
            loadUsers();
        });
    }
    
    const userModal = document.getElementById('userModal');
    if (userModal) {
        const modalBg = userModal.querySelector('.modal-background');
        if (modalBg) {
            modalBg.onclick = closeUserModal;
        }
    }
});
