package API.Rest;

import API.ApiResponse;
import Models.Session;
import Models.User;
import Services.AuthService;
import Services.SessionService;
import Services.RateLimitService;
import Utils.IpResolutionUtils;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.time.Instant;
import java.util.Map;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
public class EnhancedAuthAPI {
    
    @Inject
    AuthService authService;
    
    @Inject
    SessionService sessionService;
    
    @Inject
    RateLimitService rateLimitService;
    
    @Inject
    HttpServerRequest vertxRequest;
    
    @Inject
    Controllers.SetupController setupController;

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response login(LoginRequest loginRequest, @Context UriInfo uriInfo, @Context ContainerRequestContext requestContext) {
        if (loginRequest == null || loginRequest.username == null || loginRequest.password == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Username and password are required"))
                    .build();
        }
        
        String ipAddress = IpResolutionUtils.getClientIp(requestContext, vertxRequest);
        
        // 1. Check rate limit before authentication
        if (rateLimitService.isBlocked(ipAddress, loginRequest.username)) {
            return Response.status(429)
                    .entity(ApiResponse.error("Too many failed login attempts. Try again later."))
                    .build();
        }
        
        var authResult = authService.authenticate(loginRequest.username, loginRequest.password);
        
        if (authResult.isPresent()) {
            User user = authResult.get();
            
            // Clear rate limit on success
            rateLimitService.recordSuccessfulLogin(ipAddress, user.getUsername());
            
            // Create session
            Session session = sessionService.createSession(String.valueOf(user.id), user.getUsername(), ipAddress);
            
            // Local network check for secure cookie bypass
            boolean isLocalNetwork = isLocalNetwork(ipAddress);
            System.out.println("[DEBUG] Login from IP: " + ipAddress + ", isLocalNetwork: " + isLocalNetwork);
            
            // Create secure session cookie (7 days = 604800 seconds)
            // Skip secure flag on local network (192.168.100.*, 10.50.0.*)
            NewCookie.Builder cookieBuilder = new NewCookie.Builder("JMEDIA_SESSION")
                    .value(session.sessionId)
                    .path("/")
                    .maxAge(604800)
                    .httpOnly(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .comment("JMedia authentication session");
            
            if (!isLocalNetwork) {
                cookieBuilder.secure(true);
            }
            
            NewCookie sessionCookie = cookieBuilder.build();
            
            boolean isAdmin = "admin".equals(user.getGroupName());
            boolean needsSetup = isAdmin && setupController.isFirstTimeSetup();
            
            return Response.ok()
                    .entity(ApiResponse.success(Map.of(
                        "message", "Login successful",
                        "needsSetup", needsSetup
                    )))
                    .cookie(sessionCookie)
                    .build();
        }
        
        // 2. Record failed attempt
        rateLimitService.recordFailedAttempt(ipAddress, loginRequest.username);
        logFailedLoginAttempt(loginRequest.username, ipAddress, "Invalid credentials");
        
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("Invalid username or password"))
                .build();
    }
    
    @POST
    @Path("/logout")
    public Response logout(@Context HttpHeaders headers) {
        // Get session cookie from headers
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        
        if (sessionId != null) {
            // Invalidate session
            sessionService.invalidateSession(sessionId);
        }
        
        // Clear session cookie
        NewCookie clearCookie = new NewCookie.Builder("JMEDIA_SESSION")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
        
        return Response.ok()
                .entity("{\"data\":{\"message\":\"Logout successful\"}}")
                .type("application/json")
                .cookie(clearCookie)
                .build();
    }
    
    @GET
    @Path("/status")
    public Response status(@Context HttpHeaders headers, @Context ContainerRequestContext requestContext) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        
        if (sessionId == null) {
            return Response.ok()
                    .entity(ApiResponse.error("No active session"))
                    .build();
        }
        
        String ipAddress = IpResolutionUtils.getClientIp(requestContext, vertxRequest);
        
        if (sessionService.validateSession(sessionId, ipAddress)) {
            Session session = Session.findBySessionId(sessionId);
            return Response.ok()
                    .entity(ApiResponse.success(session))
                    .build();
        }
        
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("Invalid or expired session"))
                .build();
    }
    
    @GET
    @Path("/current-user")
    public Response getCurrentUser(@Context HttpHeaders headers) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        
        if (sessionId == null) {
            return Response.ok()
                    .entity(ApiResponse.success(Map.of("loggedIn", false)))
                    .build();
        }
        
        Session session = Session.findBySessionId(sessionId);
        if (session == null || !session.active) {
            return Response.ok()
                    .entity(ApiResponse.success(Map.of("loggedIn", false)))
                    .build();
        }
        
        // Get user info from database
        User user = User.find("username", session.username).firstResult();
        if (user == null) {
            return Response.ok()
                    .entity(ApiResponse.success(Map.of("loggedIn", false)))
                    .build();
        }
        
        boolean isAdmin = "admin".equals(user.getGroupName());
        
        return Response.ok()
                .entity(ApiResponse.success(Map.of(
                    "loggedIn", true,
                    "username", user.getUsername(),
                    "isAdmin", isAdmin,
                    "groupName", user.getGroupName() != null ? user.getGroupName() : ""
                )))
                .build();
    }
    
    @GET
    @Path("/is-admin")
    public Response isAdmin(@Context HttpHeaders headers) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        
        if (sessionId == null) {
            return Response.ok()
                    .entity(ApiResponse.success(Map.of("isAdmin", false)))
                    .build();
        }
        
        Session session = Session.findBySessionId(sessionId);
        
        if (session == null || !session.active) {
            return Response.ok()
                    .entity(ApiResponse.success(Map.of("isAdmin", false)))
                    .build();
        }
        
        User user = User.find("username", session.username).firstResult();
        
        if (user == null) {
            return Response.ok()
                    .entity(ApiResponse.success(Map.of("isAdmin", false)))
                    .build();
        }
        
        boolean isAdmin = "admin".equals(user.getGroupName());
        
        return Response.ok()
                .entity(ApiResponse.success(Map.of("isAdmin", isAdmin)))
                .build();
    }
    
    private void logFailedLoginAttempt(String username, String ipAddress, String reason) {
        System.out.println("Failed login attempt for user '" + username + "' from IP " + ipAddress + " - " + reason);
    }
    
    private boolean isLocalNetwork(String ipAddress) {
        if (ipAddress == null) return false;
        return ipAddress.startsWith("192.168.100.") || 
               ipAddress.startsWith("10.50.0.") ||
               ipAddress.equals("127.0.0.1");
    }
    
    public static class LoginRequest {
        public String username;
        public String password;
        public String redirectUrl;
    }
    
    public static class SessionInfo {
        public String sessionId;
        public String username;
        public String ipAddress;
        public Instant createdAt;
        public Instant lastActivity;
        public boolean active;
    }
}