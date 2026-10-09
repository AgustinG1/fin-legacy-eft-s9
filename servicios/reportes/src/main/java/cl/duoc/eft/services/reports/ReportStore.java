package cl.duoc.eft.services.reports;
import cl.duoc.eft.services.shared.BusinessException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReportStore {
    private final JdbcTemplate jdbc;
    public ReportStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String EXECUTIONS="""
        WITH executions AS (
          SELECT e.job_execution_id,e.status,d.parameter_value dataset,k.parameter_value kind,
                 p.parameter_value period_label,r.parameter_value rate,
                 ROW_NUMBER() OVER(PARTITION BY d.parameter_value,k.parameter_value ORDER BY e.job_execution_id DESC) rn
          FROM batch_job_execution e JOIN batch_job_instance i ON i.job_instance_id=e.job_instance_id
          JOIN batch_job_execution_params d ON d.job_execution_id=e.job_execution_id AND d.parameter_name='dataset'
          JOIN batch_job_execution_params k ON k.job_execution_id=e.job_execution_id AND k.parameter_name='kind'
          LEFT JOIN batch_job_execution_params p ON p.job_execution_id=e.job_execution_id AND p.parameter_name='period'
          LEFT JOIN batch_job_execution_params r ON r.job_execution_id=e.job_execution_id AND r.parameter_name='rate'
          WHERE (i.job_name='movimientosJob' AND k.parameter_value='DAILY')
             OR (i.job_name='interesesJob' AND k.parameter_value='INTEREST')
             OR (i.job_name='estadosJob' AND k.parameter_value='ANNUAL')
        )
        """;
    public record Summary(String dataset,String proceso,long ejecucion,long entrada,long validosUnicos,long rechazados,long duplicadosValidos,String periodo,BigDecimal tasa){}
    public List<Summary> summaries(){
        return jdbc.query(EXECUTIONS+"SELECT * FROM executions WHERE rn=1 AND status='COMPLETED' ORDER BY kind,dataset LIMIT 100",
            (rs,n)->new Execution(rs.getString("dataset"),rs.getString("kind"),rs.getLong("job_execution_id"),rs.getString("status"),rs.getString("period_label"),rs.getString("rate")))
            .stream().map(this::summary).toList();
    }
    private record Execution(String dataset,String kind,long id,String status,String period,String rate){}
    private Execution certified(String dataset,String kind){
        if(dataset==null || !dataset.matches("[a-f0-9]{64}") || !Set.of("DAILY","INTEREST","ANNUAL").contains(kind)) throw BusinessException.invalid("Dataset o proceso invalido");
        var executions=jdbc.query(EXECUTIONS+"SELECT * FROM executions WHERE rn=1 AND dataset=? AND kind=?",
            (rs,n)->new Execution(dataset,kind,rs.getLong("job_execution_id"),rs.getString("status"),rs.getString("period_label"),rs.getString("rate")),dataset,kind);
        if(executions.isEmpty()) throw BusinessException.missing("Ejecucion Batch no encontrada");
        if(!"COMPLETED".equals(executions.get(0).status())) throw new BusinessException(HttpStatus.CONFLICT,"La ultima ejecucion Batch no esta completada");
        return executions.get(0);
    }
    private Summary summary(Execution e){
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_row WHERE dataset=? AND kind=?",Long.class,e.dataset(),e.kind());
        long accepted=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_row WHERE dataset=? AND kind=? AND status='ACCEPTED'",Long.class,e.dataset(),e.kind());
        long unique=jdbc.queryForObject("SELECT COUNT(DISTINCT business_key) FROM legacy_row WHERE dataset=? AND kind=? AND status='ACCEPTED'",Long.class,e.dataset(),e.kind());
        return new Summary(e.dataset(),e.kind(),e.id(),total,unique,total-accepted,accepted-unique,e.period(),e.rate()==null || e.rate().isBlank()?null:new BigDecimal(e.rate()));
    }
    public record Page(Summary resumen,int pagina,int tamano,List<Map<String,Object>> filas){}
    public Page page(String dataset,String kind,int page,int size){
        if(page<0 || size<1 || size>100) throw BusinessException.invalid("Pagina no negativa y tamano entre 1 y 100 requeridos");
        Execution execution=certified(dataset,kind);
        var rows=jdbc.query("""
            WITH ranked AS (
              SELECT r.*,ROW_NUMBER() OVER(PARTITION BY business_key ORDER BY source_file,line_number) rn
              FROM legacy_row r WHERE dataset=? AND kind=? AND status='ACCEPTED'
            ) SELECT source_file,line_number,legacy_id,account_id,event_date,transaction_type,amount,
                     holder_name,age,description,rate,interest,final_balance,period_label
              FROM ranked WHERE rn=1 ORDER BY source_file,line_number LIMIT ? OFFSET ?
            """,(rs,n)->{
                Map<String,Object> row=new LinkedHashMap<>();
                for(int column=1;column<=rs.getMetaData().getColumnCount();column++) {
                    Object value=rs.getObject(column);
                    if(value instanceof java.sql.Date date) value=date.toLocalDate();
                    row.put(rs.getMetaData().getColumnLabel(column).toLowerCase(Locale.ROOT),value);
                }
                return row;
            },dataset,kind,size,(long)page*size);
        return new Page(summary(execution),page,size,rows);
    }
}
