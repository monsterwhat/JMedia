/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package Utils;

import Models.User;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity; 
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest; 
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped; 
import java.util.Arrays;
import java.util.Set; 

@ApplicationScoped
public class PanacheIdentityProvider
        implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            UsernamePasswordAuthenticationRequest request,
            AuthenticationRequestContext context) {

        return context.runBlocking(() -> {
            User user = User.find("username", request.getUsername())
                    .firstResult();

            if (user == null) {
                return null;
            }

            if (!org.mindrot.jbcrypt.BCrypt.checkpw(
                    new String(request.getPassword().getPassword()),
                    user.getPasswordHash())) {
                return null;
            }

            return QuarkusSecurityIdentity.builder() 
                    .setPrincipal(() -> user.getUsername())
                    .addRoles(parseRoles(user.getGroupName()))
                    .build();
        });
    }

    private Set<String> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(Arrays.asList(roles.split(",")));
    }
}
