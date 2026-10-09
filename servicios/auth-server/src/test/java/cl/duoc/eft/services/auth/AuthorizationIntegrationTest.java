package cl.duoc.eft.services.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"eft.clients.operator-secret=test-operator-secret-123456789","eft.clients.reader-secret=test-reader-secret-123456789",
        "eft.clients.accounts-secret=test-accounts-secret-123456789","eft.clients.payments-secret=test-payments-secret-123456789"})
@AutoConfigureMockMvc
class AuthorizationIntegrationTest {
    static final Path KEY;
    static { try { KEY=Files.createTempDirectory("eft-auth-test").resolve("key.json"); }
        catch(Exception ex) { throw new ExceptionInInitializerError(ex); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("eft.oauth.key-file",KEY::toString); }
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Test void issuesSignedScopedTokenAndOnlyExposesPublicKey() throws Exception {
        var response=http.perform(post("/oauth2/token").with(httpBasic("pagos-interno","test-payments-secret-123456789"))
                .param("grant_type","client_credentials").param("scope","ledger.write")).andExpect(status().isOk()).andReturn();
        var jwt=SignedJWT.parse(json.readTree(response.getResponse().getContentAsString()).get("access_token").asText());
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("cuentas");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:9100");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("pagos-interno");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().getTime()-jwt.getJWTClaimsSet().getIssueTime().getTime()).isEqualTo(600_000);
        var keys=http.perform(get("/oauth2/jwks")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var publicKey=(RSAKey)JWKSet.parse(keys).getKeys().get(0);
        assertThat(publicKey.isPrivate()).isFalse();
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
    }
    @Test void readerCannotRequestWriteScopesAndBadSecretCannotIssueToken() throws Exception {
        http.perform(post("/oauth2/token").with(httpBasic("eft-consulta","test-reader-secret-123456789"))
                .param("grant_type","client_credentials").param("scope","pagos.write"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
        http.perform(post("/oauth2/token").with(httpBasic("eft-operador","incorrecto"))
                .param("grant_type","client_credentials"))
                .andExpect(status().isUnauthorized());
    }
}
