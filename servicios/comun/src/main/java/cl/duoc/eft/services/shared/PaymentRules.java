package cl.duoc.eft.services.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import cl.duoc.eft.services.shared.Models.*;

public final class PaymentRules {
    private PaymentRules() { }
    public static void validate(OrdenPago order) {
        if(order==null || order.id()==null || order.tipo()==null || order.monto()==null
                || order.monto().signum()<=0 || order.monto().compareTo(new BigDecimal("999999999999999.99"))>0
                || order.concepto()==null || order.concepto().isBlank() || order.concepto().length()>200)
            throw BusinessException.invalid("Orden de pago invalida");
        try { order.monto().setScale(2,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException ex) { throw BusinessException.invalid("Maximo dos decimales"); }
        boolean valid=switch(order.tipo()) {
            case DEPOSITO -> order.origen()==null && order.destino()!=null;
            case RETIRO -> order.origen()!=null && order.destino()==null;
            case PAGO,TRANSFERENCIA -> order.origen()!=null && order.destino()!=null && !order.origen().equals(order.destino());
        };
        if(!valid) throw BusinessException.invalid("Cuentas incompatibles con el tipo de operacion");
    }
    public static String fingerprint(OrdenPago order) {
        validate(order);
        String value=order.tipo()+"|"+order.origen()+"|"+order.destino()+"|"+order.monto().setScale(2)+"|"+order.concepto().trim();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
