package me.prexorjustin.prexorcloud.controller.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.auth.AuthManager.LoginOutcome;
import me.prexorjustin.prexorcloud.controller.config.LockoutConfig;
import me.prexorjustin.prexorcloud.security.jwt.JwtManager;
import me.prexorjustin.prexorcloud.security.password.PasswordHasher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("AuthManager")
@ExtendWith(MockitoExtension.class)
class AuthManagerTest {

    @Mock
    private UserConfigLoader userConfigLoader;

    @Mock
    private JwtManager jwtManager;

    private AuthManager authManager;

    @BeforeEach
    void setUp() {
        authManager = new AuthManager(userConfigLoader, jwtManager);
    }

    @Nested
    @DisplayName("Admin user creation")
    class AdminUserCreation {

        @Test
        @DisplayName("Creates admin user when no users exist with configured password")
        void createsAdminWithConfiguredPassword() throws IOException {
            when(userConfigLoader.loadAll()).thenReturn(List.of());
            when(userConfigLoader.create(eq("admin"), anyString(), eq(Role.ADMIN)))
                    .thenReturn(new User("admin", "hash", Role.ADMIN, null, null, null, "2026-01-01"));

            authManager.ensureAdminUser("myConfiguredPassword");

            verify(userConfigLoader)
                    .create(eq("admin"), argThat(hash -> hash.startsWith("$argon2id$")), eq(Role.ADMIN));
        }

        @Test
        @DisplayName("Creates admin user with random password when none configured")
        void createsAdminWithRandomPassword() throws IOException {
            when(userConfigLoader.loadAll()).thenReturn(List.of());
            when(userConfigLoader.create(eq("admin"), anyString(), eq(Role.ADMIN)))
                    .thenReturn(new User("admin", "hash", Role.ADMIN, null, null, null, "2026-01-01"));

            authManager.ensureAdminUser(null);

            verify(userConfigLoader)
                    .create(eq("admin"), argThat(hash -> hash.startsWith("$argon2id$")), eq(Role.ADMIN));
        }

        @Test
        @DisplayName("Creates admin with random password when blank string configured")
        void createsAdminWithBlankPassword() throws IOException {
            when(userConfigLoader.loadAll()).thenReturn(List.of());
            when(userConfigLoader.create(eq("admin"), anyString(), eq(Role.ADMIN)))
                    .thenReturn(new User("admin", "hash", Role.ADMIN, null, null, null, "2026-01-01"));

            authManager.ensureAdminUser("   ");

            verify(userConfigLoader).create(eq("admin"), anyString(), eq(Role.ADMIN));
        }

        @Test
        @DisplayName("Does not create admin when users already exist")
        void doesNotCreateWhenUsersExist() throws IOException {
            var existingUser = new User("admin", "hash", Role.ADMIN, null, null, null, "2026-01-01");
            when(userConfigLoader.loadAll()).thenReturn(List.of(existingUser));

            authManager.ensureAdminUser("password");

            verify(userConfigLoader, never()).create(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Login")
    class Login {

        @Test
        @DisplayName("Successful login returns JWT and user")
        void successfulLogin() throws IOException {
            String hash = PasswordHasher.hash("secret");
            var user = new User("admin", hash, Role.ADMIN, null, null, null, "2026-01-01");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtManager.issue("admin", Role.ADMIN)).thenReturn("jwt-token");

            var outcome = authManager.login("admin", "secret");

            assertTrue(outcome instanceof LoginOutcome.Success);
            var success = (LoginOutcome.Success) outcome;
            assertEquals("jwt-token", success.token());
            assertEquals("admin", success.user().username());
        }

        @Test
        @DisplayName("Login with wrong password returns InvalidCredentials")
        void wrongPassword() throws IOException {
            String hash = PasswordHasher.hash("correct");
            var user = new User("admin", hash, Role.ADMIN, null, null, null, "2026-01-01");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(user));

            var outcome = authManager.login("admin", "wrong");

            assertTrue(outcome instanceof LoginOutcome.InvalidCredentials);
        }

        @Test
        @DisplayName("Login with non-existent user returns InvalidCredentials")
        void nonExistentUser() throws IOException {
            when(userConfigLoader.getByUsername("unknown")).thenReturn(Optional.empty());

            var outcome = authManager.login("unknown", "password");

            assertTrue(outcome instanceof LoginOutcome.InvalidCredentials);
        }

        @Test
        @DisplayName("Login normalizes username to lowercase")
        void normalizesUsername() throws IOException {
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.empty());

            authManager.login("Admin", "pass");

            verify(userConfigLoader).getByUsername("admin");
        }
    }

    @Nested
    @DisplayName("Account lockout")
    class AccountLockout {

        private AuthManager lockingManager;
        private InMemoryLoginAttemptStore attemptStore;

        @org.junit.jupiter.api.BeforeEach
        void setupLockingManager() {
            attemptStore = new InMemoryLoginAttemptStore();
            // 3 attempts within 60s, lock for 60s
            lockingManager = new AuthManager(
                    userConfigLoader, jwtManager, attemptStore, new LockoutConfig(Boolean.TRUE, 3, 60, 60));
        }

        @Test
        @DisplayName("Locks account after maxAttempts failed logins")
        void locksAfterThreshold() throws IOException {
            String hash = PasswordHasher.hash("correct");
            var user = new User("admin", hash, Role.ADMIN, null, null, null, "2026-01-01");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(user));

            assertTrue(lockingManager.login("admin", "x") instanceof LoginOutcome.InvalidCredentials);
            assertTrue(lockingManager.login("admin", "x") instanceof LoginOutcome.InvalidCredentials);
            var third = lockingManager.login("admin", "x");

            assertTrue(third instanceof LoginOutcome.Locked, "third failure should lock");
            Instant lockedUntil = ((LoginOutcome.Locked) third).lockedUntil();
            assertTrue(lockedUntil.isAfter(Instant.now()), "lockedUntil must be in the future");
        }

        @Test
        @DisplayName("Locked account rejects even correct password while locked")
        void lockedRejectsCorrectPassword() throws IOException {
            String hash = PasswordHasher.hash("correct");
            var user = new User("admin", hash, Role.ADMIN, null, null, null, "2026-01-01");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(user));

            // Trigger lock
            lockingManager.login("admin", "x");
            lockingManager.login("admin", "x");
            lockingManager.login("admin", "x");

            // Correct password but still locked
            var outcome = lockingManager.login("admin", "correct");
            assertTrue(outcome instanceof LoginOutcome.Locked);
        }

        @Test
        @DisplayName("Successful login clears the failure counter")
        void successClearsCounter() throws IOException {
            String hash = PasswordHasher.hash("correct");
            var user = new User("admin", hash, Role.ADMIN, null, null, null, "2026-01-01");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtManager.issue("admin", Role.ADMIN)).thenReturn("jwt");

            lockingManager.login("admin", "x");
            lockingManager.login("admin", "x");
            // 2 failures so far, still under threshold
            assertTrue(lockingManager.login("admin", "correct") instanceof LoginOutcome.Success);
            // After success, two more failures should NOT lock yet
            assertTrue(lockingManager.login("admin", "x") instanceof LoginOutcome.InvalidCredentials);
            assertTrue(lockingManager.login("admin", "x") instanceof LoginOutcome.InvalidCredentials);
        }

        @Test
        @DisplayName("Operator unlock clears the lock")
        void unlockClearsLock() throws IOException {
            String hash = PasswordHasher.hash("correct");
            var user = new User("admin", hash, Role.ADMIN, null, null, null, "2026-01-01");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(user));
            when(jwtManager.issue("admin", Role.ADMIN)).thenReturn("jwt");

            lockingManager.login("admin", "x");
            lockingManager.login("admin", "x");
            lockingManager.login("admin", "x");
            // Now locked
            assertTrue(lockingManager.login("admin", "correct") instanceof LoginOutcome.Locked);

            lockingManager.unlockUser("admin");

            assertTrue(lockingManager.login("admin", "correct") instanceof LoginOutcome.Success);
        }

        @Test
        @DisplayName("Disabled lockout policy never locks")
        void disabledPolicyNeverLocks() throws IOException {
            var disabled = new AuthManager(
                    userConfigLoader, jwtManager, attemptStore, new LockoutConfig(Boolean.FALSE, 1, 60, 60));
            String hash = PasswordHasher.hash("correct");
            var user = new User("admin", hash, Role.ADMIN, null, null, null, "2026-01-01");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(user));

            for (int i = 0; i < 10; i++) {
                assertTrue(disabled.login("admin", "x") instanceof LoginOutcome.InvalidCredentials);
            }
        }
    }

    @Nested
    @DisplayName("Refresh token")
    class RefreshToken {

        @Test
        @DisplayName("Refresh issues a new JWT")
        void refreshIssuesNewToken() throws IOException {
            when(userConfigLoader.getByUsername("admin"))
                    .thenReturn(Optional.of(new User("admin", "hash", Role.ADMIN, null, null, null, "2026-01-01")));
            when(jwtManager.issue("admin", Role.ADMIN)).thenReturn("new-jwt");

            var result = authManager.refresh("admin", Role.ADMIN);

            assertTrue(result.isPresent());
            assertEquals("new-jwt", result.get());
        }
    }

    @Nested
    @DisplayName("User CRUD")
    class UserCrud {

        @Test
        @DisplayName("Creates user with hashed password and normalized username")
        void createUser() throws IOException {
            when(userConfigLoader.getByUsername("john")).thenReturn(Optional.empty());
            var created = new User("john", "hash", Role.OPERATOR, null, null, null, "2026-01-01");
            when(userConfigLoader.create(eq("john"), anyString(), eq(Role.OPERATOR)))
                    .thenReturn(created);

            var user = authManager.createUser("John", "password", Role.OPERATOR);

            assertEquals("john", user.username());
            verify(userConfigLoader).create(eq("john"), argThat(h -> h.startsWith("$argon2id$")), eq(Role.OPERATOR));
        }

        @Test
        @DisplayName("Creating user with duplicate username throws")
        void duplicateUsername() throws IOException {
            var existing = new User("john", "hash", Role.VIEWER, null, null, null, "2026-01-01");
            when(userConfigLoader.getByUsername("john")).thenReturn(Optional.of(existing));

            assertThrows(IllegalArgumentException.class, () -> authManager.createUser("John", "pass", Role.VIEWER));
        }

        @Test
        @DisplayName("Creating user with invalid role throws")
        void invalidRole() {
            assertThrows(IllegalArgumentException.class, () -> authManager.createUser("john", "pass", "SUPERADMIN"));
        }

        @Test
        @DisplayName("Creating user with blank username throws")
        void blankUsername() {
            assertThrows(IllegalArgumentException.class, () -> authManager.createUser("", "pass", Role.VIEWER));
        }

        @Test
        @DisplayName("Creating user with too-long username throws")
        void tooLongUsername() {
            String longName = "a".repeat(33);
            assertThrows(IllegalArgumentException.class, () -> authManager.createUser(longName, "pass", Role.VIEWER));
        }

        @Test
        @DisplayName("Creating user with special characters in username throws")
        void invalidUsernameChars() {
            assertThrows(
                    IllegalArgumentException.class, () -> authManager.createUser("user name", "pass", Role.VIEWER));
        }

        @Test
        @DisplayName("Valid username with dots and dashes is accepted")
        void validUsernameWithSpecialChars() throws IOException {
            when(userConfigLoader.getByUsername("my-user.name")).thenReturn(Optional.empty());
            when(userConfigLoader.create(anyString(), anyString(), anyString()))
                    .thenReturn(new User("my-user.name", "hash", Role.VIEWER, null, null, null, ""));

            assertDoesNotThrow(() -> authManager.createUser("my-user.name", "pass", Role.VIEWER));
        }

        @Test
        @DisplayName("getUser delegates to UserConfigLoader")
        void getUser() throws IOException {
            var user = new User("admin", "hash", Role.ADMIN, null, null, null, "");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(user));

            var result = authManager.getUser("admin");
            assertTrue(result.isPresent());
            assertEquals("admin", result.get().username());
        }

        @Test
        @DisplayName("getAllUsers delegates to UserConfigLoader")
        void getAllUsers() throws IOException {
            when(userConfigLoader.loadAll())
                    .thenReturn(List.of(
                            new User("a", "h", Role.ADMIN, null, null, null, ""),
                            new User("b", "h", Role.VIEWER, null, null, null, "")));

            assertEquals(2, authManager.getAllUsers().size());
        }

        @Test
        @DisplayName("deleteUser delegates to UserConfigLoader")
        void deleteUser() throws IOException {
            authManager.deleteUser("john");
            verify(userConfigLoader).delete("john");
        }
    }

    @Nested
    @DisplayName("Update user")
    class UpdateUser {

        @Test
        @DisplayName("Updates username, role, and password")
        void fullUpdate() throws IOException {
            when(userConfigLoader.getByUsername("newname")).thenReturn(Optional.empty());

            authManager.updateUser("admin", "newname", Role.OPERATOR, "newpass");

            verify(userConfigLoader)
                    .update(eq("admin"), eq("newname"), eq(Role.OPERATOR), argThat(h -> h.startsWith("$argon2id$")));
        }

        @Test
        @DisplayName("Updates with null password passes null hash")
        void nullPassword() throws IOException {
            when(userConfigLoader.getByUsername("user")).thenReturn(Optional.empty());

            authManager.updateUser("admin", "user", Role.VIEWER, null);

            verify(userConfigLoader).update("admin", "user", Role.VIEWER, null);
        }

        @Test
        @DisplayName("Rejects blank username")
        void rejectsBlankUsername() {
            assertThrows(IllegalArgumentException.class, () -> authManager.updateUser("admin", "", Role.VIEWER, null));
        }

        @Test
        @DisplayName("Rejects username already taken by different user")
        void rejectsDuplicateUsername() throws IOException {
            var other = new User("taken", "hash", Role.VIEWER, null, null, null, "");
            when(userConfigLoader.getByUsername("taken")).thenReturn(Optional.of(other));

            assertThrows(
                    IllegalArgumentException.class, () -> authManager.updateUser("admin", "taken", Role.VIEWER, null));
        }

        @Test
        @DisplayName("Allows updating own username to same value")
        void allowsSameUsername() throws IOException {
            var self = new User("admin", "hash", Role.ADMIN, null, null, null, "");
            when(userConfigLoader.getByUsername("admin")).thenReturn(Optional.of(self));

            assertDoesNotThrow(() -> authManager.updateUser("admin", "admin", Role.ADMIN, null));
        }

        @Test
        @DisplayName("Rejects invalid role on update")
        void rejectsInvalidRole() {
            assertThrows(IllegalArgumentException.class, () -> authManager.updateUser("admin", null, "FAKE", null));
        }
    }
}
