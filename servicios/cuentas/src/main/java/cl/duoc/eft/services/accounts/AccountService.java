package cl.duoc.eft.services.accounts;

import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final CustomerGateway customers;
    private final ObjectMapper json;
    private static final RowMapper<Cuenta> MAP=(rs,n)->new Cuenta(UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("customer_id")),TipoCuenta.valueOf(rs.getString("type")),rs.getString("alias"),
            rs.getString("currency"),rs.getString("status"),rs.getBigDecimal("balance"),rs.getLong("version"));
    public AccountService(JdbcTemplate jdbc,CustomerGateway customers,ObjectMapper json) { this.jdbc=jdbc; this.customers=customers; this.json=json; }
    public Cuenta get(UUID id) { return load(id,false); }
    public List<Cuenta> list(UUID customer) {
        return customer==null ? jdbc.query("SELECT * FROM account ORDER BY id LIMIT 100",MAP)
                : jdbc.query("SELECT * FROM account WHERE customer_id=? ORDER BY id LIMIT 100",MAP,customer.toString());
    }
    private Cuenta load(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM account WHERE id=?"+(lock?" FOR UPDATE":""),MAP,id.toString());
        if(rows.isEmpty()) throw BusinessException.missing("Cuenta inexistente");
        return rows.get(0);
    }
    @Transactional
    public Cuenta open(AbrirCuenta request) {
        var previous=jdbc.query("SELECT * FROM account WHERE id=?",MAP,request.id().toString());
        if(!previous.isEmpty()) {
            Cuenta value=previous.get(0);
            if(!value.clienteId().equals(request.clienteId()) || value.tipo()!=request.tipo() || !value.alias().equals(request.alias().trim()))
                throw BusinessException.conflict("Identificador de cuenta reutilizado con otros datos");
            return value;
        }
        customers.requireCustomer(request.clienteId());
        jdbc.update("INSERT INTO account(id,customer_id,type,alias,currency,status,balance,version) VALUES(?,?,?,?,'CLP','ACTIVA',0,0)",
                request.id().toString(),request.clienteId().toString(),request.tipo().name(),request.alias().trim());
        return get(request.id());
    }
    @Transactional
    public Cuenta update(UUID id,EditarCuenta request) {
        Cuenta account=load(id,true);
        if(!account.estado().equals("ACTIVA") || account.version()!=request.version()) throw BusinessException.conflict("Cuenta cerrada o version desactualizada");
        jdbc.update("UPDATE account SET alias=?,version=version+1 WHERE id=?",request.alias().trim(),id.toString());
        return get(id);
    }
    @Transactional
    public Cuenta close(UUID id,long version) {
        Cuenta account=load(id,true);
        if(account.version()!=version) throw BusinessException.conflict("Version desactualizada");
        if(account.estado().equals("CERRADA")) return account;
        if(account.saldo().signum()!=0) throw BusinessException.conflict("La cuenta debe tener saldo cero para cerrar");
        jdbc.update("UPDATE account SET status='CERRADA',version=version+1 WHERE id=?",id.toString());
        return get(id);
    }
    @Transactional
    public Comprobante apply(OrdenPago order) {
        PaymentRules.validate(order);
        String fingerprint=PaymentRules.fingerprint(order);
        Comprobante previous=receipt(order.id(),fingerprint);
        if(previous!=null) return previous;
        // Lock all affected accounts in the same global order, including for opposite transfers.
        List<UUID> ids=new ArrayList<>();
        if(order.origen()!=null) ids.add(order.origen());
        if(order.destino()!=null) ids.add(order.destino());
        ids.sort(Comparator.comparing(UUID::toString));
        Map<UUID,Cuenta> locked=new HashMap<>();
        for(UUID id:ids) locked.put(id,load(id,true));
        previous=receipt(order.id(),fingerprint);
        if(previous!=null) return previous;
        for(Cuenta account:locked.values()) if(!account.estado().equals("ACTIVA")) throw BusinessException.conflict("Cuenta cerrada");
        BigDecimal amount=order.monto().setScale(2), from=null,to=null;
        if(order.origen()!=null) {
            from=locked.get(order.origen()).saldo().subtract(amount);
            if(from.signum()<0) throw BusinessException.conflict("Saldo insuficiente");
        }
        if(order.destino()!=null) {
            to=locked.get(order.destino()).saldo().add(amount);
            if(to.precision()>19) throw BusinessException.conflict("Limite de saldo excedido");
        }
        Comprobante result=new Comprobante(order.id(),order.tipo(),order.origen(),order.destino(),amount,from,to,Instant.now());
        jdbc.update("INSERT INTO ledger_operation(id,fingerprint,receipt) VALUES(?,?,?)",order.id().toString(),fingerprint,serialize(result));
        if(order.origen()!=null) post(order.id(),order.origen(),amount.negate(),from);
        if(order.destino()!=null) post(order.id(),order.destino(),amount,to);
        return result;
    }
    private void post(UUID operation,UUID account,BigDecimal delta,BigDecimal balance) {
        jdbc.update("UPDATE account SET balance=?,version=version+1 WHERE id=?",balance,account.toString());
        jdbc.update("INSERT INTO ledger_entry(operation_id,account_id,delta,balance_after) VALUES(?,?,?,?)",operation.toString(),account.toString(),delta,balance);
    }
    private Comprobante receipt(UUID id,String fingerprint) {
        var rows=jdbc.queryForList("SELECT fingerprint,receipt FROM ledger_operation WHERE id=?",id.toString());
        if(rows.isEmpty()) return null;
        if(!rows.get(0).get("fingerprint").equals(fingerprint)) throw BusinessException.conflict("Solicitud reutilizada con otros datos");
        try { return json.readValue(rows.get(0).get("receipt").toString(),Comprobante.class); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("Comprobante almacenado invalido",ex); }
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
