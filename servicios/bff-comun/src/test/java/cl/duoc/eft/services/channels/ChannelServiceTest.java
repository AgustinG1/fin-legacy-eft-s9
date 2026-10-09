package cl.duoc.eft.services.channels;
import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class ChannelServiceTest {
    UUID customer=UUID.randomUUID(),account=UUID.randomUUID();
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    MockRestServiceServer server;
    ChannelService channels;
    Jwt token() { return Jwt.withTokenValue("test").header("alg","RS256").claim("sub","canal-web").claim("cliente_id",customer.toString()).build(); }
    @BeforeEach void setup() {
        var builder=RestClient.builder(); server=MockRestServiceServer.bindTo(builder).build();
        channels=new ChannelService(builder.build(),new DependencyProtection(json,4,10),new BigDecimal("200000"),new BigDecimal("1000"));
    }
    Cuenta account(UUID owner) { return new Cuenta(account,owner,TipoCuenta.CORRIENTE,"Prueba","CLP","ACTIVA",new BigDecimal("100000"),0); }
    @Test void rejectsForeignAccountBeforeCallingPayments() throws Exception {
        server.expect(requestTo("http://cuentas/api/cuentas/"+account)).andRespond(withSuccess(json.writeValueAsString(account(UUID.randomUUID())),MediaType.APPLICATION_JSON));
        var order=new OrdenPago(UUID.randomUUID(),TipoPago.RETIRO,account,null,new BigDecimal("1000"),"Prueba");
        assertThatThrownBy(()->channels.pay(token(),order)).isInstanceOf(BusinessException.class)
                .satisfies(ex->assertThat(((BusinessException)ex).status).isEqualTo(HttpStatus.FORBIDDEN));
        server.verify();
    }
    @Test void doesNotTrustCustomerIdSentInAPathOrRequest() throws Exception {
        server.expect(requestTo("http://cuentas/api/cuentas?clienteId="+customer)).andRespond(withSuccess("[]",MediaType.APPLICATION_JSON));
        assertThat(channels.accounts(token())).isEmpty(); server.verify();
        var withoutIdentity=Jwt.withTokenValue("test").header("alg","RS256").claim("sub","unknown").build();
        assertThatThrownBy(()->channels.customer(withoutIdentity)).isInstanceOf(BusinessException.class);
    }
    @Test void atmRejectsLimitAndDenominationWithoutCallingBusinessServices() {
        for(String invalid:List.of("0","1500","201000","-1000"))
            assertThatThrownBy(()->channels.withdraw(token(),account,UUID.randomUUID(),new BigDecimal(invalid))).isInstanceOf(BusinessException.class);
        server.verify();
    }
    @Test void atmRetainsOperationIdAndAmount() throws Exception {
        UUID operation=UUID.randomUUID();
        server.expect(requestTo("http://cuentas/api/cuentas/"+account)).andRespond(withSuccess(json.writeValueAsString(account(customer)),MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pagos/api/pagos")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.id").value(operation.toString())).andExpect(jsonPath("$.tipo").value("RETIRO"))
                .andExpect(jsonPath("$.monto").value(10000))
                .andRespond(withSuccess("{\"id\":\""+operation+"\",\"estado\":\"COMPLETADO\"}",MediaType.APPLICATION_JSON));
        assertThat(channels.withdraw(token(),account,operation,new BigDecimal("10000")).id()).isEqualTo(operation); server.verify();
    }
}
