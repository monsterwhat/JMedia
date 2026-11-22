package Services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Models.Settings.User;
import org.junit.jupiter.api.Test;

/**
 * Password storage and the bootstrap flag. Offline: constructs the entity
 * directly, no database or CDI.
 */
class UserPasswordStorageTest {

    @Test
    void passwordIsStoredAsABcryptHashNeverPlaintext() {
        User user = new User();
        user.setPassword("correct horse battery staple");

        assertNotEquals("correct horse battery staple", user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2"), "not a bcrypt hash: " + user.getPasswordHash());
        assertFalse(user.getPasswordHash().contains("horse"));
    }

    @Test
    void storedPasswordVerifiesOnlyTheRightSecret() {
        User user = new User();
        user.setPassword("correct horse battery staple");

        assertTrue(user.checkPassword("correct horse battery staple"));
        assertFalse(user.checkPassword("correct horse battery stapl"));
        assertFalse(user.checkPassword(""));
    }

    @Test
    void accountWithNoPasswordNeverAuthenticates() {
        assertFalse(new User().checkPassword(""));
        assertFalse(new User().checkPassword("anything"));
    }

    @Test
    void missingFlagMeansNoChangeRequired() {
        User user = new User();
        assertFalse(user.isPasswordChangeRequired(), "a null flag must not lock an existing account");
    }

    @Test
    void bootstrapFlagIsHonouredAndCleared() {
        User user = new User();
        user.setMustChangePassword(true);
        assertTrue(user.isPasswordChangeRequired());

        user.setMustChangePassword(false);
        assertFalse(user.isPasswordChangeRequired());

        user.setMustChangePassword(null);
        assertFalse(user.isPasswordChangeRequired());
    }
}
