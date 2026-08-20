package com.example.shortener.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.time.Duration;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    static final MacAlgorithm JWS_ALGORITHM = MacAlgorithm.HS256;

    /** Matches the short-code charset so the public rule cannot be widened by accident. */
    private static final String REDIRECT_PATTERN = "/{code:[A-Za-z0-9_-]{3,32}}";

    static final String ISSUER = "url-shortener";

    /**
     * The service returns JSON and redirects, so almost nothing needs to load. {@code 'self'}
     * for scripts and styles exists only because Swagger UI is served from the same origin;
     * without it the documentation page renders blank, which is the usual reason a strict
     * policy gets weakened later in a hurry and much further than necessary.
     *
     * <p>{@code frame-ancestors 'none'} is the load-bearing directive: it stops the redirect
     * endpoint being framed by a hostile page that wants the click without the address bar.
     */
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
                    + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private static final String PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), "
                    + "microphone=(), payment=(), usb=()";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ProblemDetailAuthenticationHandler problemHandler,
                                           JwtDecoder jwtDecoder) throws Exception {
        http
                .headers(headers -> headers
                        // Only emitted over HTTPS, which behind a TLS-terminating proxy means
                        // the app must be told the original scheme. server.forward-headers-strategy
                        // in application.yml is what makes this header appear in production;
                        // without it HSTS is configured here and silently never sent.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds()))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        // Destinations still receive the short domain as referrer, which is how
                        // link attribution works, but never the full short URL over plain HTTP.
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .frameOptions(frame -> frame.deny())
                        .permissionsPolicy(permissions -> permissions.policy(PERMISSIONS_POLICY)))
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

                        // The demo console, served from this application so it shares an
                        // origin with the API — no CORS to configure and nothing for the
                        // strict CSP to refuse.
                        //
                        // Named individually rather than as /** because default-deny is only
                        // worth having if it is not quietly widened. Every filename here
                        // contains a dot, which also keeps it outside the redirect pattern's
                        // character class: /console.js cannot be mistaken for a short code,
                        // whereas a route like /dashboard would be swallowed by it.
                        .requestMatchers(HttpMethod.GET,
                                "/", "/index.html", "/console.js", "/console.css", "/favicon.svg").permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // Metrics expose traffic shape and internal names; operators only.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // The audit trail and the blocklist are operator tools. Stated here as
                        // well as on the controller so neither check is the only thing standing
                        // between an edit and an exposed audit log.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

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

    /**
     * Verifies against every key in the ring, selecting by the token's {@code kid}, so a key
     * can be rotated without invalidating tokens signed by its predecessor.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtKeyRing keyRing, TokenRevocationService revocations) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        // Pinning the algorithm closes the "alg" confusion class of attack, where a token
        // arrives asking to be verified with something weaker than it was signed with.
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.HS256, keyRing.jwkSource()));
        // Claims are checked by the Spring validators below instead, so Nimbus must be told
        // not to apply its own defaults on top and reject for reasons nothing reports.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new RevocationValidator(revocations)));
        return decoder;
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtKeyRing keyRing) {
        return new NimbusJwtEncoder(keyRing.jwkSource());
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
