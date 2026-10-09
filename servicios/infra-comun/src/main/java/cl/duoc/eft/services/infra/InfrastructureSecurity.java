package cl.duoc.eft.services.infra;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class InfrastructureSecurity {
    @Bean UserDetailsService users(@Value("${eft.infra.password}") String password) {
        if(password.length()<24) throw new IllegalArgumentException("Secreto de infraestructura insuficiente");
        return new InMemoryUserDetailsManager(User.withUsername("infra").password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("INFRA").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/actuator/health","/actuator/health/**").permitAll().anyRequest().hasRole("INFRA"))
                .httpBasic(Customizer.withDefaults()).formLogin(f->f.disable()).build();
    }
}
