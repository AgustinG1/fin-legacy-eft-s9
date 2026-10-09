package cl.duoc.eft.services.reports;
import cl.duoc.eft.services.shared.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:reports;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.datasource.hikari.read-only=false"})
class ReportIntegrationTest {
    @Autowired ReportStore store;@Autowired JdbcTemplate jdbc;
    private final String dataset="a".repeat(64);
    @BeforeEach void setup(){
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE batch_job_instance(job_instance_id BIGINT PRIMARY KEY,job_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE batch_job_execution(job_execution_id BIGINT PRIMARY KEY,job_instance_id BIGINT,status VARCHAR(30))");
        jdbc.execute("CREATE TABLE batch_job_execution_params(job_execution_id BIGINT,parameter_name VARCHAR(100),parameter_value VARCHAR(100))");
        jdbc.execute("CREATE TABLE legacy_row(dataset VARCHAR(64),kind VARCHAR(20),status VARCHAR(16),business_key VARCHAR(64),source_file VARCHAR(255),line_number INT,legacy_id BIGINT,account_id BIGINT,event_date DATE,transaction_type VARCHAR(30),amount DECIMAL(19,2),holder_name VARCHAR(255),age INT,description VARCHAR(1000),rate DECIMAL(11,8),interest DECIMAL(19,2),final_balance DECIMAL(19,2),period_label VARCHAR(40))");
        jdbc.update("INSERT INTO batch_job_instance VALUES(1,'movimientosJob')");execution(1,"COMPLETED");
        row("ACCEPTED","one","a.csv",2,10);row("ACCEPTED","one","b.csv",2,10);
        row("ACCEPTED","two","b.csv",3,20);row("REJECTED",null,"a.csv",3,0);
    }
    void execution(long id,String status){
        jdbc.update("INSERT INTO batch_job_execution VALUES(?,1,?)",id,status);
        jdbc.update("INSERT INTO batch_job_execution_params VALUES(?,'dataset',?)",id,dataset);
        jdbc.update("INSERT INTO batch_job_execution_params VALUES(?,'kind','DAILY')",id);
    }
    void row(String status,String key,String file,int line,int amount){jdbc.update("INSERT INTO legacy_row(dataset,kind,status,business_key,source_file,line_number,amount) VALUES(?,'DAILY',?,?,?,?,?)",dataset,status,key,file,line,amount);}
    @Test void summaryAndPagedUniqueRowsAgreeWithoutReturningRejects(){
        var summary=store.summaries().get(0);
        assertThat(summary.entrada()).isEqualTo(4);assertThat(summary.validosUnicos()).isEqualTo(2);
        assertThat(summary.rechazados()).isEqualTo(1);assertThat(summary.duplicadosValidos()).isEqualTo(1);
        assertThat(store.page(dataset,"DAILY",0,1).filas().get(0).get("source_file")).isEqualTo("a.csv");
        assertThat(store.page(dataset,"DAILY",1,1).filas().get(0).get("line_number")).isEqualTo(3);
        assertThat(store.page(dataset,"DAILY",2,1).filas()).isEmpty();
    }
    @Test void latestIncompleteExecutionHidesOldCompletedResult(){
        execution(2,"STARTED");assertThat(store.summaries()).isEmpty();
        assertThatThrownBy(()->store.page(dataset,"DAILY",0,20)).isInstanceOf(BusinessException.class).hasMessageContaining("no esta completada");
    }
    @Test void untrackedRowsAndInvalidPaginationCannotBeExposed(){
        assertThatThrownBy(()->store.page("b".repeat(64),"DAILY",0,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->store.page(dataset,"DAILY",-1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->store.page(dataset,"DAILY",0,101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->store.page("'; DROP TABLE legacy_row","DAILY",0,20)).isInstanceOf(BusinessException.class);
    }
}
