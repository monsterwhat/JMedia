package API.Rest;

import API.ApiResponse;
import Models.User;
import Services.AuthService;
import Services.SessionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.time.Instant;
import java.util.Map; 

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
public class EnhancedAuthAPI {
    
    @Inject
    AuthService authService;
    
    @Inject
    SessionService sessionService;
    
    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response login(LoginRequest loginRequest, @Context UriInfo uriInfo) {
        if (loginRequest == null || loginRequest.username == null || loginRequest.password == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Username and password are required"))
                    .build();
        }
        
        var authResult = authService.authenticate(loginRequest.username, loginRequest.password);
        
        if (authResult.isPresent()) {
            User user = authResult.get();
            String ipAddress = getClientIp(uriInfo);
            
            // Create session
            SessionService.UserSession session = sessionService.createSession(String.valueOf(user.id), user.getUsername(), ipAddress);
            
            // Create secure session cookie (7 days = 604800 seconds)
            NewCookie sessionCookie = new NewCookie.Builder("JMEDIA_SESSION")
                    .value(session.sessionId)
                    .path("/")
                    .maxAge(604800) // 7 days
                    .httpOnly(true)
                    .secure(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .comment("JMedia authentication session")
                    .build();
            
            // Determine redirect URL
            String redirectUrl = "/";
            if (loginRequest.redirectUrl != null && !loginRequest.redirectUrl.trim().isEmpty()) {
                redirectUrl = loginRequest.redirectUrl;
            }
            
            return Response.ok()
                    .entity(ApiResponse.success(Map.of("message", "Login successful")))
                    .cookie(sessionCookie)
                    .build();
        }
        
        // Log failed login attempt
        logFailedLoginAttempt(loginRequest.username, getClientIp(uriInfo), "Invalid credentials");
        
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
    public Response status(@Context HttpHeaders headers) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        
        if (sessionId == null) {
            return Response.ok()
                    .entity(ApiResponse.error("No active session"))
                    .build();
        }
        
        if (sessionService.validateSession(sessionId, "localhost")) { // Simplified for now
            var session = sessionService.sessions.get(sessionId);
            return Response.ok()
                    .entity(ApiResponse.success(session))
                    .build();
        }
        
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("Invalid or expired session"))
                .build();
    }
    
    private String getClientIp(UriInfo uriInfo) {
        String clientIp = uriInfo.getRequestUri().getHost();
        if (clientIp == null || clientIp.equals("localhost") || clientIp.startsWith("127.") || clientIp.startsWith("192.168.") || clientIp.startsWith("10.")) {
            return "local";
        }
        return clientIp;
    }
    

    
    private void logFailedLoginAttempt(String username, String ipAddress, String reason) {
        // In a real implementation, you would log to database
        // For now, we'll log to console
        System.out.println("Failed login attempt for user '" + username + "' from IP " + ipAddress + " - " + reason);
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