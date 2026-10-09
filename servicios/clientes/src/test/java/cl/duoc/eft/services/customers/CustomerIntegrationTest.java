package cl.duoc.eft.services.customers;

import cl.duoc.eft.services.shared.Models.*;
import cl.duoc.eft.services.shared.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:customers;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password="})
@AutoConfigureMockMvc
class CustomerIntegrationTest {
    @Autowired CustomerService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc http;
    @BeforeEach void clean() { jdbc.update("DELETE FROM customer"); }
    @Test void createsNormalizesAndProtectsUniqueDocument() {
        var request=new NuevoCliente(UUID.randomUUID()," doc-1 "," Ana ","ANA@example.test");
        var first=service.create(request);
        assertThat(first.documento()).isEqualTo("DOC-1");
        assertThat(first.email()).isEqualTo("ana@example.test");
        assertThat(service.create(request)).isEqualTo(first);
        assertThatThrownBy(()->service.create(new NuevoCliente(UUID.randomUUID(),"DOC-1","Otro","otro@example.test")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.list()).hasSize(1);
    }
    @Test void staleProfileCannotOverwriteNewerChanges() {
        var first=service.create(new NuevoCliente(UUID.randomUUID(),"A","Ana","ana@example.test"));
        var update=service.update(first.id(),new EditarCliente("Ana Garcia","ana@example.test",0L));
        assertThat(update.version()).isEqualTo(1);
        assertThatThrownBy(()->service.update(first.id(),new EditarCliente("Anterior","ana@example.test",0L)))
                .isInstanceOf(BusinessException.class);
        assertThat(service.get(first.id()).nombre()).isEqualTo("Ana Garcia");
    }
    @Test void enforcesAuthenticationScopesAndInputValidation() throws Exception {
        http.perform(get("/api/clientes")).andExpect(status().isUnauthorized());
        http.perform(get("/api/clientes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_clientes.read"))))
                .andExpect(status().isOk());
        http.perform(post("/api/clientes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_clientes.read")))
                .contentType("application/json").content("{\"id\":\""+UUID.randomUUID()+"\",\"documento\":\"A\",\"nombre\":\"Ana\",\"email\":\"ana@example.test\"}"))
                .andExpect(status().isForbidden());
        http.perform(post("/api/clientes").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_clientes.write")))
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
