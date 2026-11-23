package com.surya.fooddelivery.user;

import com.surya.fooddelivery.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository repository;

    public UserController(UserRepository repository) {
        this.repository = repository;
    }

    /** The whole customer list is exactly the bulk personal data only staff should see. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<User> all() {
        return repository.findAll();
    }

    /**
     * Readable by any authenticated caller, because order-service needs it: when a customer places
     * an order, their token is relayed here to confirm the account exists. Narrowing this to the
     * owner alone would break that call, and the record holds no credentials.
     */
    @GetMapping("/{id}")
    public User byId(@PathVariable Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User " + id + " not found"));
    }

    /** Convenience for a signed-in user: their own profile, without needing to know their id. */
    @GetMapping("/me")
    public User me() {
        Long id = currentAccountId();
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No profile yet for account " + id + " — create one with POST /api/users"));
    }

    /**
     * Creates the caller's own profile, keyed by their account id from the token.
     *
     * <p>The id is taken from the verified token and never from the request body. A body field
     * would be a self-assigned identity: anyone could create or overwrite the profile of account 1
     * by typing "1". An admin can still act for someone else, but has to say so explicitly through
     * {@code accountId} — and holds the role that permits it.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public User create(@Valid @RequestBody CreateUserRequest request) {
        Long accountId = currentAccountId();
        if (request.accountId() != null && !request.accountId().equals(accountId)) {
            if (!CurrentUser.isAdmin()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You can only create a profile for your own account");
            }
            accountId = request.accountId();
        }
        if (repository.existsById(accountId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Account " + accountId + " already has a profile");
        }

        User user = new User();
        user.setId(accountId);
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setAddress(request.address());
        return repository.save(user);
    }

    private Long currentAccountId() {
        return CurrentUser.id().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }

    /**
     * @param accountId optional, and only honoured for admins — see {@link #create}.
     */
    public record CreateUserRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            String phone,
            String address,
            Long accountId
    ) {
    }
}
