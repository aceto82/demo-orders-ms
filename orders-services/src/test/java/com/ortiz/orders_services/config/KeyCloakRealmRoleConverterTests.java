package com.ortiz.orders_services.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeyCloakRealmRoleConverterTests {
    private final KeyCloakRealmRoleConverter converter = new KeyCloakRealmRoleConverter();

    private Jwt jwt(Map<String, Object> claims) {
        // Jwt requires at least one claim, so seed a non-relevant one to exercise the
        // "no realm_access" path with an otherwise-empty claims map.
        return Jwt.withTokenValue("t").header("alg", "none")
                .claims(c -> {
                    c.putAll(claims);
                    c.putIfAbsent("sub", "t");
                }).build();
    }

    @Test
    void mapsRealmRoles() {
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN"))))))
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void missingRolesIsNotAnError() {
        assertThat(converter.convert(jwt(Map.of()))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of())))).isEmpty();
    }
}
