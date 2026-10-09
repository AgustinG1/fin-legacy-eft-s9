package cl.duoc.eft.services.reports;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/reportes") @PreAuthorize("hasAuthority('SCOPE_reportes.read')")
public class ReportController {
    private final ReportStore store;
    public ReportController(ReportStore store){this.store=store;}
    @GetMapping("/resumen") public List<ReportStore.Summary> summaries(){return store.summaries();}
    @GetMapping("/{dataset}/{kind}") public ReportStore.Page page(@PathVariable String dataset,@PathVariable String kind,
            @RequestParam(defaultValue="0") int pagina,@RequestParam(defaultValue="20") int tamano){return store.page(dataset,kind,pagina,tamano);}
}
