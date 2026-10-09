package cl.duoc.eft.services.mobile;
import cl.duoc.eft.services.channels.ChannelService;
import cl.duoc.eft.services.shared.Models.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"eft.outbound.enabled=false","INTERNAL_SECRET=test-not-a-real-secret-123456"})
@AutoConfigureMockMvc
class ChannelSecurityTest {
    @Autowired MockMvc http;
    @MockBean ChannelService channels;
    @MockBean RestClient client;
    @Test void requiresTokenAndChannelScope() throws Exception {
        http.perform(get("/api/movil/resumen")).andExpect(status().isUnauthorized());
        http.perform(get("/api/movil/resumen").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other.read")))).andExpect(status().isForbidden());
        verifyNoInteractions(channels);
    }
    @Test void returnsOnlyTheFieldsRequiredByTheChannel() throws Exception {
        UUID id=UUID.randomUUID();
        var customer=new Cliente(id,"DOC","Ana","ana@example.test",0);
        var account=new Cuenta(UUID.randomUUID(),id,TipoCuenta.CORRIENTE,"Principal","CLP","ACTIVA",new BigDecimal("100000"),0);
        when(channels.profile(any())).thenReturn(customer);when(channels.accounts(any())).thenReturn(List.of(account));
        http.perform(get("/api/movil/resumen").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_movil.read"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Ana"))
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.cliente").doesNotExist())
                .andExpect(jsonPath("$.cuentas[0].clienteId").doesNotExist()).andExpect(jsonPath("$.cuentas[0].version").doesNotExist());
    }
    @Test void readerCannotWithdrawOrSendPayment() throws Exception {
        String path="/api/movil/pagos";
        String body="{\"id\":\"00000000-0000-0000-0000-000000000002\",\"tipo\":\"DEPOSITO\",\"destino\":\"00000000-0000-0000-0000-000000000001\",\"monto\":1000,\"concepto\":\"Prueba\"}";
        http.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_movil.read"))).contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(channels);
    }
}
