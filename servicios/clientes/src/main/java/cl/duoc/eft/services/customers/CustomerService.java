package cl.duoc.eft.services.customers;

import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Cliente> MAP=(rs,n)->new Cliente(UUID.fromString(rs.getString("id")),
            rs.getString("document"),rs.getString("name"),rs.getString("email"),rs.getLong("version"));
    public CustomerService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Cliente get(UUID id) {
        var rows=jdbc.query("SELECT * FROM customer WHERE id=?",MAP,id.toString());
        if(rows.isEmpty()) throw BusinessException.missing("Cliente inexistente");
        return rows.get(0);
    }
    public List<Cliente> list() { return jdbc.query("SELECT * FROM customer ORDER BY id LIMIT 100",MAP); }
    @Transactional
    public Cliente create(NuevoCliente request) {
        String document=request.documento().trim().toUpperCase(Locale.ROOT);
        String name=request.nombre().trim(), email=request.email().trim().toLowerCase(Locale.ROOT);
        var previous=jdbc.query("SELECT * FROM customer WHERE id=?",MAP,request.id().toString());
        if(!previous.isEmpty()) {
            Cliente value=previous.get(0);
            if(!value.documento().equals(document) || !value.nombre().equals(name) || !value.email().equals(email))
                throw BusinessException.conflict("Identificador reutilizado con otros datos");
            return value;
        }
        jdbc.update("INSERT INTO customer(id,document,name,email,version) VALUES(?,?,?,?,0)",request.id().toString(),document,name,email);
        return get(request.id());
    }
    @Transactional
    public Cliente update(UUID id, EditarCliente request) {
        get(id);
        int changed=jdbc.update("UPDATE customer SET name=?,email=?,version=version+1 WHERE id=? AND version=?",
                request.nombre().trim(),request.email().trim().toLowerCase(Locale.ROOT),id.toString(),request.version());
        if(changed!=1) throw BusinessException.conflict("El cliente fue modificado; vuelva a consultar su version");
        return get(id);
    }
}
