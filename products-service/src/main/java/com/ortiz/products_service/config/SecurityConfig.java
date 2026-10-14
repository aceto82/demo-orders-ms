package com.ortiz.products_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorizeRequests ->
                        authorizeRequests.requestMatchers( request ->
                                        request.getRequestURI().contains("/actuator/products")).permitAll()
                                .anyRequest().authenticated())
                .oauth2ResourceServer(configurer ->
                        configurer.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())
                        ));
        return http.build();
    }

    private Converter<Jwt,? extends AbstractAuthenticationToken> jwtAuthConverter() {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(new KeyCloakRealmRoleConverter());
        return jwtAuthenticationConverter;
    }


}

class KeyCloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt source) {
        Object raw = source.getClaims().get("realm_access");
        if (!(raw instanceof Map<?, ?> realmAccess)) return List.of();
        Object roles = realmAccess.get("roles");
        if (!(roles instanceof Collection<?> roleNames)) return List.of();
        return roleNames.stream()
                .filter(String.class::isInstance).map(String.class::cast)
                .map(role -> "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
