package com.ortiz.orders_services.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JwtConfigTests {
    private final OAuth2TokenValidator<Jwt> validator =
            JwtValidators.createDefaultWithIssuer("http://localhost:8181/realms/demo-orders-serv-realm");

    private Jwt jwt(String issuer) {
        return Jwt.withTokenValue("t").header("alg", "none").issuer(issuer).subject("s")
                .expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    @Test
    void rejectsForeignIssuer() {
        assertThat(validator.validate(jwt("http://evil.example/realms/x")).hasErrors()).isTrue();
    }

    @Test
    void acceptsRealmIssuer() {
        assertThat(validator.validate(jwt("http://localhost:8181/realms/demo-orders-serv-realm")).hasErrors()).isFalse();
    }
}
