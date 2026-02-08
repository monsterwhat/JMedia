package Controllers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;

@Path("/login.html")
public class AuthController {
    
    @GET
    public Response serveLoginPage(@Context UriInfo uriInfo) {
        // For now, serve a simple HTML page
        // Later this could be enhanced to use a template
        String loginHtml = generateLoginPage(uriInfo);
        
        return Response.ok()
                .type("text/html")
                .entity(loginHtml)
                .build();
    }
    
    private String generateLoginPage(UriInfo uriInfo) {
        String redirectUrl = uriInfo.getRequestUri().getQuery();
        if (redirectUrl != null && redirectUrl.startsWith("redirect=")) {
            redirectUrl = redirectUrl.substring(9);
        } else {
            redirectUrl = "/";
        }
        
        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>JMedia - Login</title>\n" +
                "    <link rel=\"stylesheet\" href=\"/css/bulma.min.css\">\n" +
                "    <link rel=\"stylesheet\" href=\"https://unpkg.com/primeicons@7.0.0/primeicons.css\">\n" +
                "    <link rel=\"stylesheet\" href=\"/css/custom.css\">\n" +
                "    <link rel=\"icon\" type=\"image/x-icon\" href=\"logo.png\">\n" +
                "</head>\n" +
                "<body class=\"has-background-light\">\n" +
                "    <div class=\"hero is-fullheight\">\n" +
                "        <div class=\"hero-body\">\n" +
                "            <div class=\"container\">\n" +
                "                <div class=\"columns is-centered\">\n" +
                "                    <div class=\"column is-one-third\">\n" +
                "                        <div class=\"box\">\n" +
                "                            <div class=\"has-text-centered mb-6\">\n" +
                "                                <h1 class=\"title is-3\">JMedia</h1>\n" +
                "                                <p class=\"subtitle is-6\">Please sign in to continue</p>\n" +
                "                            </div>\n" +
                "                            \n" +
                "                            <form id=\"loginForm\" onsubmit=\"handleLogin(event)\">\n" +
                "                                <div class=\"field\">\n" +
                "                                    <label class=\"label\">Username</label>\n" +
                "                                    <div class=\"control has-icons-left\">\n" +
                "                                        <input class=\"input\" type=\"text\" id=\"username\" name=\"username\" placeholder=\"Enter username\" required>\n" +
                "                                        <span class=\"icon is-left\">\n" +
                "                                            <i class=\"pi pi-user\"></i>\n" +
                "                                        </span>\n" +
                "                                    </div>\n" +
                "                                </div>\n" +
                "                                \n" +
                "                                <div class=\"field\">\n" +
                "                                    <label class=\"label\">Password</label>\n" +
                "                                    <div class=\"control has-icons-left\">\n" +
                "                                        <input class=\"input\" type=\"password\" id=\"password\" name=\"password\" placeholder=\"Enter password\" required>\n" +
                "                                        <span class=\"icon is-left\">\n" +
                "                                            <i class=\"pi pi-lock\"></i>\n" +
                "                                        </span>\n" +
                "                                    </div>\n" +
                "                                </div>\n" +
                "                                \n" +
                "                                <div class=\"field\">\n" +
                "                                    <div class=\"control\">\n" +
                "                                        <button class=\"button is-primary is-fullwidth\" type=\"submit\" id=\"loginButton\">\n" +
                "                                            <span class=\"icon\"><i class=\"pi pi-sign-in\"></i></span>\n" +
                "                                            <span>Sign In</span>\n" +
                "                                        </button>\n" +
                "                                    </div>\n" +
                "                                </div>\n" +
                                "                                \n" +
                "                                <div id=\"errorMessage\" class=\"notification is-danger is-hidden mt-4\"></div>\n" +
                "                                <div id=\"successMessage\" class=\"notification is-success is-hidden mt-4\"></div>\n" +
                "                            </form>\n" +
                "                            \n" +
                "                            <input type=\"hidden\" id=\"redirectUrl\" value=\"" + redirectUrl + "\">\n" +
                "                        </div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <script>\n" +
                "        function handleLogin(event) {\n" +
                "            event.preventDefault();\n" +
                "            \n" +
                "            const username = document.getElementById('username').value;\n" +
                "            const password = document.getElementById('password').value;\n" +
                "            const redirectUrl = document.getElementById('redirectUrl').value;\n" +
                "            const loginButton = document.getElementById('loginButton');\n" +
                "            const errorMessage = document.getElementById('errorMessage');\n" +
                "            const successMessage = document.getElementById('successMessage');\n" +
                "            \n" +
                "            // Hide previous messages\n" +
                "            errorMessage.classList.add('is-hidden');\n" +
                "            successMessage.classList.add('is-hidden');\n" +
                "            \n" +
                "            // Show loading state\n" +
                "            loginButton.disabled = true;\n" +
                "            loginButton.innerHTML = '<span class=\"icon\"><i class=\"pi pi-spinner pi-spin\"></i></span><span>Signing in...</span>';\n" +
                "            \n" +
                "            const loginData = {\n" +
                "                username: username,\n" +
                "                password: password,\n" +
                "                redirectUrl: redirectUrl\n" +
                "            };\n" +
                "            \n" +
                "            fetch('/api/auth/login', {\n" +
                "                method: 'POST',\n" +
                "                headers: {\n" +
                "                    'Content-Type': 'application/json'\n" +
                "                },\n" +
                "                body: JSON.stringify(loginData)\n" +
                "            })\n" +
                "            .then(response => response.json())\n" +
                "            .then(data => {\n" +
                "                if (!data.error) {\n" +
                "                    successMessage.textContent = 'Login successful! Redirecting...';\n" +
                "                    successMessage.classList.remove('is-hidden');\n" +
                "                    setTimeout(() => {\n" +
                "                        window.location.href = redirectUrl;\n" +
                "                    }, 1000);\n" +
                "                } else {\n" +
                "                    errorMessage.textContent = data.error || 'Login failed';\n" +
                "                    errorMessage.classList.remove('is-hidden');\n" +
                "                }\n" +
                "            })\n" +
                "            .catch(error => {\n" +
                "                console.error('Login error:', error);\n" +
                "                errorMessage.textContent = 'An error occurred during login';\n" +
                "                errorMessage.classList.remove('is-hidden');\n" +
                "            })\n" +
                "            .finally(() => {\n" +
                "                loginButton.disabled = false;\n" +
                "                loginButton.innerHTML = '<span class=\"icon\"><i class=\"pi pi-sign-in\"></i></span><span>Sign In</span>';\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        // Auto-focus username field on load\n" +
                "        document.addEventListener('DOMContentLoaded', function() {\n" +
                "            document.getElementById('username').focus();\n" +
                "        });\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}