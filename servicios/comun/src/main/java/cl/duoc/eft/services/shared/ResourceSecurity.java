package cl.duoc.eft.services.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableMethodSecurity
public class ResourceSecurity {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/actuator/health","/actuator/health/**").permitAll()
                        .requestMatchers("/api/**","/interno/**").authenticated().anyRequest().denyAll())
                .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults()))
                .httpBasic(b->b.disable()).formLogin(f->f.disable()).build();
    }
    @Bean
    JwtDecoder decoder(@Value("${eft.oauth.issuer}") String issuer, @Value("${eft.oauth.jwks}") String jwks,
                       @Value("${eft.oauth.audience}") String audience) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000); factory.setReadTimeout(2000);
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(new RestTemplate(factory)).build();
        OAuth2TokenValidator<Jwt> expectedAudience = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Audiencia incorrecta",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),expectedAudience));
        return decoder;
    }
}
