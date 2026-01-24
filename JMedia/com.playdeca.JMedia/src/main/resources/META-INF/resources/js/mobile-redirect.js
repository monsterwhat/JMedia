// Mobile Device Detection and Redirect
class MobileRedirect {
    constructor() {
        this.currentPath = window.location.pathname;
        this.currentHost = window.location.host;
        this.userAgent = navigator.userAgent;
        this.isMobile = this.detectMobileDevice();
        this.isTablet = this.detectTabletDevice();
        this.shouldRedirect = this.shouldRedirectToMobile();
        
        this.init();
    }
    
    init() {
        // Only redirect if we're not already on mobile page and device is mobile
        if (this.shouldRedirect && !this.isMobilePage()) {
            this.redirectToMobile();
        }
        
        // Add desktop view link for mobile users who want desktop
        this.addDesktopViewLink();
        
        // Store user preference
        this.handleUserPreference();
    }
    
    detectMobileDevice() {
        const mobileRegex = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile|mobile|CriOS/i;
        return mobileRegex.test(this.userAgent) || this.isTouchDevice();
    }
    
    detectTabletDevice() {
        const tabletRegex = /iPad|Android(?!.*Mobile)|Tablet|Kindle|Silk/i;
        return tabletRegex.test(this.userAgent);
    }
    
    isTouchDevice() {
        return (
            'ontouchstart' in window ||
            navigator.maxTouchPoints > 0 ||
            navigator.msMaxTouchPoints > 0
        );
    }
    
    shouldRedirectToMobile() {
        // Don't redirect if:
        // 1. User explicitly prefers desktop
        // 2. Already on mobile page
        // 3. Device is tablet (tablet users might prefer desktop)
        // 4. Screen width is large (> 1024px)
        
        const prefersDesktop = localStorage.getItem('preferDesktop') === 'true';
        const isLargeScreen = window.innerWidth > 1024;
        
        return this.isMobile && 
               !this.isTablet && 
               !prefersDesktop && 
               !isLargeScreen &&
               !this.isMobilePage();
    }
    
    isMobilePage() {
        return this.currentPath.includes('/mobile.html') || 
               this.currentPath.includes('/m/');
    }
    
    redirectToMobile() {
        const mobileUrl = this.buildMobileUrl();
        if (mobileUrl) {
            window.location.replace(mobileUrl);
        }
    }
    
    buildMobileUrl() {
        if (this.currentPath === '/' || this.currentPath === '/index.html') {
            return '/mobile.html';
        }
        
        // Handle specific pages with mobile equivalents
        const pageMappings = {
            '/settings.html': '/mobile.html#settings',
            '/import.html': '/mobile.html#import',
            '/player.html': '/mobile.html',
            '/video.html': '/mobile.html#video'
        };
        
        for (const [desktopPage, mobilePage] of Object.entries(pageMappings)) {
            if (this.currentPath === desktopPage || this.currentPath.endsWith(desktopPage)) {
                return mobilePage;
            }
        }
        
        // Default: redirect to mobile home
        return '/mobile.html';
    }
    
    addDesktopViewLink() {
        if (this.isMobile && !this.isMobilePage()) {
            // Add desktop view option to current page
            const desktopLink = document.createElement('div');
            desktopLink.innerHTML = `
                <a href="#" id="desktopViewLink" style="
                    position: fixed;
                    bottom: 20px;
                    right: 20px;
                    background: rgba(0, 0, 0, 0.8);
                    color: white;
                    padding: 8px 12px;
                    border-radius: 20px;
                    text-decoration: none;
                    font-size: 12px;
                    z-index: 9999;
                    transition: opacity 0.3s;
                ">Desktop View</a>
            `;
            
            document.body.appendChild(desktopLink);
            
            document.getElementById('desktopViewLink').addEventListener('click', (e) => {
                e.preventDefault();
                localStorage.setItem('preferDesktop', 'true');
                window.location.reload();
            });
        }
    }
    
    handleUserPreference() {
        // Check URL parameter for forcing mobile or desktop
        const urlParams = new URLSearchParams(window.location.search);
        const forceMobile = urlParams.get('mobile') === 'true';
        const forceDesktop = urlParams.get('desktop') === 'true';
        
        if (forceMobile) {
            localStorage.setItem('forceMobile', 'true');
            localStorage.removeItem('preferDesktop');
            if (!this.isMobilePage()) {
                this.redirectToMobile();
            }
        } else if (forceDesktop) {
            localStorage.setItem('preferDesktop', 'true');
            localStorage.removeItem('forceMobile');
            if (this.isMobilePage()) {
                this.redirectToDesktop();
            }
        }
        
        // Add mobile/desktop toggle links
        this.addViewToggleLinks();
    }
    
    addViewToggleLinks() {
        // Add view toggle links to navbar if it exists
        const navbarEnd = document.querySelector('.navbar-end, .mobile-header-right');
        if (navbarEnd && this.isMobile) {
            const toggleDiv = document.createElement('div');
            toggleDiv.className = 'view-toggle';
            toggleDiv.innerHTML = `
                <a href="?mobile=true" class="view-toggle-link">Mobile</a>
                <span class="view-toggle-separator">|</span>
                <a href="?desktop=true" class="view-toggle-link">Desktop</a>
            `;
            toggleDiv.style.cssText = `
                font-size: 12px;
                margin-left: 10px;
                opacity: 0.7;
            `;
            navbarEnd.appendChild(toggleDiv);
        }
    }
    
    redirectToDesktop() {
        const desktopUrl = this.buildDesktopUrl();
        if (desktopUrl) {
            window.location.replace(desktopUrl);
        }
    }
    
    buildDesktopUrl() {
        if (this.currentPath.includes('/mobile.html')) {
            // Reverse mapping from mobile to desktop
            const hash = window.location.hash.substring(1);
            
            const mobileToDesktop = {
                'settings': '/settings.html',
                'import': '/import.html',
                'video': '/video.html',
                '': '/index.html'
            };
            
            return mobileToDesktop[hash] || '/index.html';
        }
        
        return '/index.html';
    }
    
    // Public methods for manual control
    static forceMobile() {
        localStorage.setItem('forceMobile', 'true');
        localStorage.removeItem('preferDesktop');
        window.location.href = '/mobile.html';
    }
    
    static forceDesktop() {
        localStorage.setItem('preferDesktop', 'true');
        localStorage.removeItem('forceMobile');
        window.location.href = '/index.html';
    }
    
    static clearPreference() {
        localStorage.removeItem('preferDesktop');
        localStorage.removeItem('forceMobile');
        window.location.reload();
    }
}

// Initialize mobile redirect on page load
document.addEventListener('DOMContentLoaded', () => {
    // Only run redirect logic on non-mobile pages
    if (!window.location.pathname.includes('/mobile.html')) {
        new MobileRedirect();
    }
});

// Add global functions for manual switching
window.mobileRedirect = {
    forceMobile: () => MobileRedirect.forceMobile(),
    forceDesktop: () => MobileRedirect.forceDesktop(),
    clearPreference: () => MobileRedirect.clearPreference()
};

// Export for Node.js environments if needed
if (typeof module !== 'undefined' && module.exports) {
    module.exports = MobileRedirect;
}