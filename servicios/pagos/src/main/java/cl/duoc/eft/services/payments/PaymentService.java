package cl.duoc.eft.services.payments;
import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private final PaymentStore store;
    private final AccountGateway accounts;
    public PaymentService(PaymentStore store,AccountGateway accounts) { this.store=store; this.accounts=accounts; }
    public Pago execute(OrdenPago request,String caller) {
        PaymentRules.validate(request);
        Pago state=store.reserve(request,caller);
        if(state.estado().equals("COMPLETADO")) return state;
        if(state.estado().equals("RECHAZADO")) throw BusinessException.conflict(state.motivo());
        Comprobante receipt;
        try { receipt=accounts.apply(request); }
        catch(BusinessException rejected) { store.reject(request.id(),rejected.getMessage()); throw rejected; }
        // Network/timeout errors leave PENDIENTE: the account transaction might already have committed.
        // Repeating the same ID recovers its receipt without applying the movement again.
        return store.complete(request.id(),receipt);
    }
}
