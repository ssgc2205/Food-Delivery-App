package com.surya.fooddelivery.auth;

import com.surya.fooddelivery.security.CurrentUser;
import com.surya.fooddelivery.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;
    private final AuthUserRepository repository;

    public AuthController(AuthService authService, TokenService tokenService, AuthUserRepository repository) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.repository = repository;
    }

    /** Public. Creates an account and returns a token, so a new user is logged in immediately. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenService.IssuedToken register(@Valid @RequestBody RegisterRequest request) {
        AuthUser user = authService.register(request.username(), request.password(), request.roles());
        return tokenService.issue(user);
    }

    /** Public. Exchanges credentials for a token. */
    @PostMapping("/login")
    public TokenService.IssuedToken login(@Valid @RequestBody LoginRequest request) {
        AuthUser user = authService.authenticate(request.username(), request.password());
        return tokenService.issue(user);
    }

    /**
     * Who the bearer of this token is, according to the token itself. Handy for debugging and for
     * a UI that needs to know which navigation to show.
     */
    @GetMapping("/me")
    public AccountResponse me() {
        Long id = CurrentUser.id()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated principal"));
        return repository.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account no longer exists"));
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AccountResponse> all() {
        return repository.findAll().stream().map(AccountResponse::from).toList();
    }

    /**
     * The only way to grant {@link Role#ADMIN} — and it already requires being one, which is why
     * an empty database seeds the first admin at startup instead.
     */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createWithRoles(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(
                authService.create(request.username(), request.password(), request.roles()));
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountResponse replaceRoles(@PathVariable Long id, @Valid @RequestBody RolesRequest request) {
        return AccountResponse.from(authService.replaceRoles(id, request.roles()));
    }

    /**
     * Lets other services confirm an account exists. {@code order-service} does not use this —
     * it reads the id straight out of the verified token — but an admin console would.
     */
    @GetMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN') or @accountAccess.isSelf(#id)")
    public AccountResponse byId(@PathVariable Long id) {
        return repository.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account " + id + " not found"));
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 40)
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "may contain only letters, digits, dot, underscore or hyphen")
            String username,

            // A length floor is the single most effective password rule; complexity requirements
            // mostly push people toward predictable substitutions.
            @NotBlank @Size(min = 8, max = 100, message = "must be at least 8 characters")
            String password,

            Set<Role> roles
    ) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record CreateAccountRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotEmpty Set<Role> roles
    ) {
    }

    public record RolesRequest(@NotEmpty Set<Role> roles) {
    }

    /** Everything about an account that is safe to hand back — note the absence of the hash. */
    public record AccountResponse(Long id, String username, Set<Role> roles, boolean enabled, Instant createdAt) {

        static AccountResponse from(AuthUser user) {
            return new AccountResponse(user.getId(), user.getUsername(), user.getRoles(),
                    user.isEnabled(), user.getCreatedAt());
        }
    }
}
