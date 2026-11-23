package com.surya.fooddelivery.auth;

import com.surya.fooddelivery.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthUserRepository repository;

    private AuthService authService;

    /**
     * A real encoder, not a mock. Hashing behaviour is the thing under test in half these cases —
     * a mocked encoder would happily let a bug that stores the raw password pass.
     */
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @BeforeEach
    void setUp() {
        authService = new AuthService(repository, passwordEncoder);
    }

    private AuthUser existing(String username, String rawPassword, Role... roles) {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRoles(EnumSet.copyOf(Set.of(roles)));
        return user;
    }

    @Test
    @DisplayName("stores a bcrypt hash, never the password itself")
    void hashesPassword() {
        when(repository.existsByUsername("surya")).thenReturn(false);
        when(repository.save(any(AuthUser.class))).thenAnswer(call -> call.getArgument(0));

        authService.register("surya", "correct-horse", Set.of(Role.CUSTOMER));

        ArgumentCaptor<AuthUser> saved = ArgumentCaptor.forClass(AuthUser.class);
        verify(repository).save(saved.capture());

        String hash = saved.getValue().getPasswordHash();
        assertThat(hash).doesNotContain("correct-horse");
        assertThat(hash).startsWith("{bcrypt}$2a$");
        assertThat(passwordEncoder.matches("correct-horse", hash)).isTrue();
    }

    @Test
    @DisplayName("defaults a new account to CUSTOMER when no role is requested")
    void defaultsToCustomer() {
        when(repository.existsByUsername(anyString())).thenReturn(false);
        when(repository.save(any(AuthUser.class))).thenAnswer(call -> call.getArgument(0));

        AuthUser user = authService.register("surya", "correct-horse", null);

        assertThat(user.getRoles()).containsExactly(Role.CUSTOMER);
    }

    @Test
    @DisplayName("refuses to let a stranger self-register as ADMIN")
    void rejectsSelfAssignedAdmin() {
        assertThatThrownBy(() -> authService.register("sneaky", "correct-horse", Set.of(Role.ADMIN)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // The important half of the assertion: nothing was written.
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("still allows the self-service roles")
    void allowsSelfServiceRoles() {
        when(repository.existsByUsername(anyString())).thenReturn(false);
        when(repository.save(any(AuthUser.class))).thenAnswer(call -> call.getArgument(0));

        AuthUser user = authService.register("chef", "correct-horse", Set.of(Role.RESTAURANT_OWNER));

        assertThat(user.getRoles()).containsExactly(Role.RESTAURANT_OWNER);
    }

    @Test
    @DisplayName("rejects a duplicate username with 409 rather than overwriting an account")
    void rejectsDuplicateUsername() {
        when(repository.existsByUsername("surya")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("surya", "correct-horse", Set.of(Role.CUSTOMER)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("authenticates a correct password")
    void authenticatesValidCredentials() {
        when(repository.findByUsername("surya")).thenReturn(Optional.of(existing("surya", "correct-horse", Role.CUSTOMER)));

        AuthUser user = authService.authenticate("surya", "correct-horse");

        assertThat(user.getUsername()).isEqualTo("surya");
    }

    @Test
    @DisplayName("a wrong password and an unknown user fail identically, so login is not a username oracle")
    void failuresAreIndistinguishable() {
        when(repository.findByUsername("surya")).thenReturn(Optional.of(existing("surya", "correct-horse", Role.CUSTOMER)));
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());

        ResponseStatusException wrongPassword =
                catchResponseStatus(() -> authService.authenticate("surya", "wrong"));
        ResponseStatusException unknownUser =
                catchResponseStatus(() -> authService.authenticate("ghost", "wrong"));

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getReason()).isEqualTo(wrongPassword.getReason());
    }

    @Test
    @DisplayName("a disabled account cannot log in even with the right password")
    void rejectsDisabledAccount() {
        AuthUser disabled = existing("surya", "correct-horse", Role.CUSTOMER);
        disabled.setEnabled(false);
        when(repository.findByUsername("surya")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> authService.authenticate("surya", "correct-horse"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseStatusException catchResponseStatus(Runnable call) {
        try {
            call.run();
        } catch (ResponseStatusException e) {
            return e;
        }
        throw new AssertionError("expected a ResponseStatusException");
    }
}
