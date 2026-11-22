package Controllers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Serves the password-change page. Reached two ways: automatically when an
 * account still carries its bootstrap password (JMediaAuthFilter redirects
 * here), and deliberately from the UI by any signed-in user.
 */
@Path("/change-password.html")
public class ChangePasswordController {

    @GET
    public Response serve() {
        return Response.ok()
                .type("text/html")
                .entity(PAGE)
                .build();
    }

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>JMedia - Change password</title>
                <link rel="stylesheet" href="/css/bulma.min.css">
                <link rel="stylesheet" href="/css/jmedia-unified.css">
                <link rel="icon" type="image/x-icon" href="logo.png">
            </head>
            <body class="has-background-light">
                <div class="hero is-fullheight">
                    <div class="hero-body">
                        <div class="container">
                            <div class="columns is-centered">
                                <div class="column is-one-third">
                                    <div class="box">
                                        <div class="has-text-centered mb-5">
                                            <h1 class="title is-4">Choose a new password</h1>
                                            <p class="subtitle is-6">
                                                This account is still using a generated password.
                                                Set your own to continue.
                                            </p>
                                        </div>

                                        <form id="changeForm" onsubmit="submitChange(event)">
                                            <div class="field">
                                                <label class="label" for="currentPassword">Current password</label>
                                                <div class="control">
                                                    <input class="input" type="password" id="currentPassword"
                                                           autocomplete="current-password" required>
                                                </div>
                                            </div>

                                            <div class="field">
                                                <label class="label" for="newPassword">New password</label>
                                                <div class="control">
                                                    <input class="input" type="password" id="newPassword"
                                                           autocomplete="new-password" minlength="12" required>
                                                </div>
                                                <p class="help">At least 12 characters.</p>
                                            </div>

                                            <div class="field">
                                                <label class="label" for="confirmPassword">Repeat new password</label>
                                                <div class="control">
                                                    <input class="input" type="password" id="confirmPassword"
                                                           autocomplete="new-password" minlength="12" required>
                                                </div>
                                            </div>

                                            <div class="field">
                                                <div class="control">
                                                    <button class="button is-primary is-fullwidth" type="submit"
                                                            id="submitButton">Save password</button>
                                                </div>
                                            </div>

                                            <div id="errorMessage" class="notification is-danger is-hidden"></div>
                                            <div id="successMessage" class="notification is-success is-hidden"></div>
                                        </form>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <script>
                    function submitChange(event) {
                        event.preventDefault();

                        var current = document.getElementById('currentPassword').value;
                        var next = document.getElementById('newPassword').value;
                        var confirm = document.getElementById('confirmPassword').value;
                        var button = document.getElementById('submitButton');
                        var error = document.getElementById('errorMessage');
                        var success = document.getElementById('successMessage');

                        error.classList.add('is-hidden');
                        success.classList.add('is-hidden');

                        if (next !== confirm) {
                            error.textContent = 'The two new passwords do not match.';
                            error.classList.remove('is-hidden');
                            return;
                        }

                        button.disabled = true;
                        button.classList.add('is-loading');

                        fetch('/api/auth/change-password', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ currentPassword: current, newPassword: next })
                        })
                        .then(function (response) {
                            return response.json().then(function (body) {
                                return { ok: response.ok, body: body };
                            });
                        })
                        .then(function (result) {
                            if (!result.ok) {
                                error.textContent = (result.body && (result.body.error || result.body.message))
                                    || 'Could not change the password.';
                                error.classList.remove('is-hidden');
                                return;
                            }
                            success.textContent = 'Password updated. Taking you to the app...';
                            success.classList.remove('is-hidden');
                            setTimeout(function () { window.location.href = '/'; }, 1200);
                        })
                        .catch(function (err) {
                            console.error('[change-password] request failed', err);
                            error.textContent = 'Network error while changing the password.';
                            error.classList.remove('is-hidden');
                        })
                        .finally(function () {
                            button.disabled = false;
                            button.classList.remove('is-loading');
                        });
                    }
                </script>
            </body>
            </html>
            """;
}
