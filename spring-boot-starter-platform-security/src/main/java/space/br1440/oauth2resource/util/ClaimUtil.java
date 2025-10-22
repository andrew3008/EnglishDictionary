package space.br1440.oauth2resource.util;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;

public class ClaimUtil {

        private ClaimUtil() {
        }

        public static final String PREFERRED_USERNAME_CLAIM = "preferred_username";
        public static final String EMAIL_CLAIM = "email";
        public static final String FULL_NAME_CLAIM = "name";
        public static final String GIVEN_NAME_CLAIM = "given_name";
        public static final String FAMILY_NAME_CLAIM = "family_name";

        public static String getClaim(final String claim) {
            final var auth = SecurityContextHolder.getContext().getAuthentication();
            if (Objects.nonNull(auth) && auth.getPrincipal() instanceof Jwt jwt) {
                return jwt.getClaim(claim);
            }
            throw new IllegalStateException("Principal is not jwt type.");
        }

        public static String getPreferredUsername() {
            return getClaim(PREFERRED_USERNAME_CLAIM);
        }

        public static String getEmail() {
            return getClaim(EMAIL_CLAIM);
        }

        public static String getFullName() {
            return getClaim(FULL_NAME_CLAIM);
        }

        public static String getGivenName() {
            return getClaim(GIVEN_NAME_CLAIM);
        }

        public static String getFamilyName() {
            return getClaim(FAMILY_NAME_CLAIM);
        }

}
