package API.Rest;

import API.ApiResponse;
import Models.Session;
import Models.User;
import Services.ProfileService;
import Services.SessionService;
import Services.UserService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserManagementAPI {
    
    @Inject
    UserService userService;
    
    @Inject
    SessionService sessionService;
    
    @Inject
    ProfileService profileService;
    
    private boolean isAdmin(HttpHeaders headers) {
        String sessionId = getSessionId(headers);
        if (sessionId == null) {
            return false;
        }
        
        Session session = Session.findBySessionId(sessionId);
        if (session == null || !session.active) {
            return false;
        }
        
        User user = User.find("username", session.username).firstResult();
        if (user == null) {
            return false;
        }
        
        return "admin".equals(user.getGroupName());
    }
    
    private String getSessionId(HttpHeaders headers) {
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            return headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        return null;
    }
    
    @GET
    public Response listUsers(@Context HttpHeaders headers) {
        if (!isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }
        
        List<User> users = userService.listAll();
        List<Map<String, Object>> userList = new ArrayList<>();
        for (User u : users) {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("id", u.id);
            userMap.put("username", u.getUsername());
            userMap.put("groupName", u.getGroupName() != null ? u.getGroupName() : "user");
            userList.add(userMap);
        }
        
        return Response.ok()
                .entity(ApiResponse.success(userList))
                .build();
    }
    
    @POST
    @Transactional
    public Response createUser(UserRequest request, @Context HttpHeaders headers) {
        if (!isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }
        
        if (request.username == null || request.username.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Username is required"))
                    .build();
        }
        
        if (request.password == null || request.password.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Password is required"))
                    .build();
        }
        
        try {
            User user = userService.create(request.username, request.password, request.groupName);
            
            return Response.ok()
                    .entity(ApiResponse.success(Map.of(
                            "id", user.id,
                            "username", user.getUsername(),
                            "groupName", user.getGroupName()
                    )))
                    .build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();
        }
    }
    
    @PUT
    @Path("/{id}")
    public Response updateUser(@PathParam("id") Long id, UserRequest request, @Context HttpHeaders headers) {
        if (!isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }
        
        try {
            User user = userService.update(id, request.username, request.password, request.groupName);
            return Response.ok()
                    .entity(ApiResponse.success(Map.of(
                            "id", user.id,
                            "username", user.getUsername(),
                            "groupName", user.getGroupName()
                    )))
                    .build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();
        }
    }
    
    @DELETE
    @Path("/{id}")
    public Response deleteUser(@PathParam("id") Long id, @Context HttpHeaders headers) {
        if (!isAdmin(headers)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("Admin access required"))
                    .build();
        }
        
        try {
            profileService.deleteByUserId(id);
            userService.delete(id);
            return Response.ok()
                    .entity(ApiResponse.success(Map.of("message", "User deleted")))
                    .build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();
        }
    }
    
    public static class UserRequest {
        public String username;
        public String password;
        public String groupName;
    }
}
