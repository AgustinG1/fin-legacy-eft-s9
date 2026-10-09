package cl.duoc.eft.services.web;
import cl.duoc.eft.services.shared.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
@RestController @RequestMapping("/api/web/migracion") @PreAuthorize("hasAuthority('SCOPE_web.migracion')")
public class MigrationController {
    private final RestClient http;private final DependencyProtection protection;
    public MigrationController(RestClient http,DependencyProtection protection){this.http=http;this.protection=protection;}
    @GetMapping("/resumen") public Object summaries(){return get("/api/reportes/resumen");}
    @GetMapping("/{dataset}/{kind}") public Object page(@PathVariable String dataset,@PathVariable String kind,
            @RequestParam(defaultValue="0") int pagina,@RequestParam(defaultValue="20") int tamano){
        if(!dataset.matches("[a-f0-9]{64}") || !java.util.Set.of("DAILY","INTEREST","ANNUAL").contains(kind)) throw BusinessException.invalid("Dataset o proceso invalido");
        return get("/api/reportes/"+dataset+"/"+kind+"?pagina="+pagina+"&tamano="+tamano);
    }
    private Object get(String path){return protection.call("reportes",()->http.get().uri("http://reportes"+path).retrieve().body(Object.class));}
}
