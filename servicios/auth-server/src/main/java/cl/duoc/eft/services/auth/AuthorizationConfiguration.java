package cl.duoc.eft.services.auth;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AuthorizationConfiguration {
    @Bean @Order(1)
    SecurityFilterChain authorization(HttpSecurity http) throws Exception {
        var server=OAuth2AuthorizationServerConfigurer.authorizationServer();
        return http.securityMatcher(server.getEndpointsMatcher()).with(server,s->{})
                .authorizeHttpRequests(a->a.anyRequest().authenticated()).build();
    }
    @Bean @Order(2)
    SecurityFilterChain remaining(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll()
                .anyRequest().denyAll()).build();
    }
    @Bean
    RegisteredClientRepository clients(Environment env) {
        List<RegisteredClient> registered=new ArrayList<>(List.of(
                client("eft-operador",env.getRequiredProperty("eft.clients.operator-secret"),
                        "clientes.read","clientes.write","cuentas.read","cuentas.write","pagos.read","pagos.write","auditoria.read","reportes.read","web.migracion"),
                client("eft-consulta",env.getRequiredProperty("eft.clients.reader-secret"),"clientes.read","cuentas.read","pagos.read","auditoria.read","reportes.read"),
                client("cuentas-interno",env.getRequiredProperty("eft.clients.accounts-secret"),"clientes.read"),
                client("pagos-interno",env.getRequiredProperty("eft.clients.payments-secret"),"ledger.write")));
        if(env.getProperty("eft.channels.enabled",Boolean.class,false)) {
            UUID.fromString(env.getRequiredProperty("eft.channels.customer-id"));
            for(String channel:List.of("web","movil","cajero")) {
                registered.add(client("canal-"+channel,env.getRequiredProperty("eft.channels."+channel+"-secret"),channel+".read",channel+".write"));
                registered.add(channel.equals("web")?
                        client("bff-web-interno",env.getRequiredProperty("eft.channels.web-internal-secret"),"clientes.read","cuentas.read","pagos.write","reportes.read"):
                        channel.equals("cajero")?
                        client("bff-cajero-interno",env.getRequiredProperty("eft.channels.cajero-internal-secret"),"cuentas.read","pagos.write"):
                        client("bff-"+channel+"-interno",env.getRequiredProperty("eft.channels."+channel+"-internal-secret"),"clientes.read","cuentas.read","pagos.write"));
            }
        }
        return new InMemoryRegisteredClientRepository(registered);
    }
    private RegisteredClient client(String id,String secret,String... scopes) {
        if(secret.length()<24) throw new IllegalArgumentException("El secreto OAuth2 debe tener al menos 24 caracteres");
        return RegisteredClient.withId(id).clientId(id).clientSecret("{bcrypt}"+new BCryptPasswordEncoder().encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scopes(s->s.addAll(List.of(scopes)))
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(10)).build()).build();
    }
    @Bean
    AuthorizationServerSettings settings(@Value("${eft.oauth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }
    @Bean
    JWKSource<SecurityContext> keys(@Value("${eft.oauth.key-file}") String file) throws Exception {
        Path path=Path.of(file).toAbsolutePath();
        Files.createDirectories(path.getParent());
        RSAKey key;
        if(Files.exists(path)) {
            key=RSAKey.parse(Files.readString(path));
            if(!key.isPrivate()) throw new IllegalStateException("Falta la clave privada de firma");
        } else {
            key=new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            Files.writeString(path,key.toJSONString(),StandardOpenOption.CREATE_NEW);
        }
        return new ImmutableJWKSet<>(new JWKSet(key));
    }
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> audience(Environment env) {
        return context->{
            Set<String> audiences=new TreeSet<>();
            for(String scope:context.getAuthorizedScopes()) {
                if(scope.startsWith("clientes.")) audiences.add("clientes");
                if(scope.startsWith("cuentas.") || scope.equals("ledger.write")) audiences.add("cuentas");
                if(scope.startsWith("pagos.")) audiences.add("pagos");
                if(scope.startsWith("auditoria.")) audiences.add("auditoria");
                if(scope.startsWith("reportes.")) audiences.add("reportes");
                for(String channel:List.of("web","movil","cajero")) if(scope.startsWith(channel+".")) audiences.add("bff-"+channel);
            }
            context.getClaims().audience(new ArrayList<>(audiences));
            if(context.getRegisteredClient().getClientId().startsWith("canal-"))
                context.getClaims().claim("cliente_id",env.getRequiredProperty("eft.channels.customer-id"));
        };
    }
}
