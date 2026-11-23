package com.surya.fooddelivery.auth;

import com.surya.fooddelivery.security.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * Ownership rules that {@code @PreAuthorize} expressions can call by bean name, e.g.
 * {@code @PreAuthorize("hasRole('ADMIN') or @accountAccess.isSelf(#id)")}.
 *
 * <p>Roles alone cannot express "you may read this record because it is yours" — every customer
 * holds the same role, so a role check would either expose all accounts or none. Putting the rule
 * in a bean keeps it testable as ordinary Java and stops the same comparison being retyped (and
 * eventually mistyped) in a dozen annotations.
 */
@Component("accountAccess")
public class AccountAccess {

    public boolean isSelf(Long accountId) {
        return CurrentUser.id().filter(id -> id.equals(accountId)).isPresent();
    }
}
