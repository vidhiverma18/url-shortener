package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final MacAlgorithm JWS_ALGORITHM = MacAlgorithm.HS256;
    private static final int MINIMUM_SECRET_BYTES = 32;

    /** Matches the short-code charset so the public rule cannot be widened by accident. */
    private static final String REDIRECT_PATTERN = "/{code:[A-Za-z0-9_-]{3,32}}";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ProblemDetailAuthenticationHandler problemHandler,
                                           JwtDecoder jwtDecoder) throws Exception {
        http
                // No cookies and no server-side session, so there is no ambient authority
                // for a forged cross-site request to ride on. CSRF protection defends
                // against exactly that, and enabling it here would only break API clients.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Spring Security filters the ERROR dispatch too. Without this,
                        // the RFC 9457 body produced for an anonymous request is itself
                        // blocked and the caller gets an opaque 403 instead.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        // The redirect is the product. Putting authentication in front of
                        // it would break every link already shared with the world.
                        .requestMatchers(HttpMethod.GET, REDIRECT_PATTERN).permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // Metrics expose traffic shape and internal names; operators only.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        .requestMatchers("/api/**").authenticated()

                        // Default deny. Anything not named above is refused rather than
                        // quietly inheriting whatever the last rule happened to be.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));

        return http.build();
    }

    /**
     * Maps the {@code roles} claim onto Spring's authorities. Without an explicit
     * converter, Spring reads {@code scope}/{@code scp} and every token silently arrives
     * with no authorities at all — which fails closed, but fails confusingly.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public JwtDecoder jwtDecoder(ShortenerProperties properties) {
        return NimbusJwtDecoder.withSecretKey(signingKey(properties))
                // Pinning the algorithm closes the "alg" confusion class of attack, where a
                // token arrives asking to be verified with something weaker.
                .macAlgorithm(JWS_ALGORITHM)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(ShortenerProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    private SecretKeySpec signingKey(ShortenerProperties properties) {
        byte[] secret = properties.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "shortener.security.jwt-secret must be at least " + MINIMUM_SECRET_BYTES
                            + " bytes for " + JWS_ALGORITHM.getName() + "; refusing to start with a weak key");
        }
        return new SecretKeySpec(secret, JWS_ALGORITHM.getName());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost 10 is the Spring default: roughly 50-100ms per verification, which is slow
        // enough to make offline cracking expensive and fast enough to serve logins.
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Runs the hash comparison even when the user does not exist, so response timing
        // does not reveal which usernames are real.
        provider.setHideUserNotFoundExceptions(true);
        return provider::authenticate;
    }
}
