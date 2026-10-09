package cl.duoc.eft.batch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.Chunk;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootTest(properties={"eft.run.enabled=false", "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "eft.chunk-size=4", "eft.retry-limit=2", "eft.retry-delay-ms=1", "eft.restart-delay-ms=1", "logging.level.root=ERROR"})
class BatchIntegrationTest {
    @Autowired Map<String, Job> jobs;
    @Autowired JobLauncher launcher;
    @Autowired JobCoordinator coordinator;
    @Autowired JobExplorer explorer;
    @Autowired JdbcTemplate jdbc;
    @Autowired ThreadPoolTaskExecutor executor;
    @SpyBean LegacyWriter writer;
    @TempDir Path temporary;

    private JobParameters parameters(Path root, Kind kind, String replay) throws Exception {
        String policy = kind == Kind.INTEREST ? "rate=0.05000000|period=TRIMESTRAL-DEMO" : "validacion-v1";
        return new JobParametersBuilder()
                .addString("dataset", InputManifest.fingerprint(root,kind,policy))
                .addString("kind",kind.name()).addString("input",root.toAbsolutePath().normalize().toString())
                .addString("output",temporary.resolve("output").toString(),false)
                .addString("policy",policy).addString("rate",kind == Kind.INTEREST ? "0.05000000" : "")
                .addString("period",kind == Kind.INTEREST ? "TRIMESTRAL-DEMO" : "")
                .addString("replay",replay).toJobParameters();
    }

    @Test void originalFilesReconcileAndSequentialReplayMatchesParallelResults() throws Exception {
        Path root = Path.of("../data").toAbsolutePath().normalize();
        int[] expected = {1020,1016,1018};
        for (Kind kind : Kind.values()) {
            var parameters = parameters(root,kind,UUID.randomUUID().toString());
            assertThat(launcher.run(jobs.get(kind.jobName),parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            String dataset = parameters.getString("dataset");
            assertThat(count(dataset)).isEqualTo(expected[kind.ordinal()]);
            var before = jdbc.queryForList("SELECT * FROM legacy_row WHERE dataset=? ORDER BY record_key",dataset);
            Path folder = temporary.resolve("output").resolve(kind.name().toLowerCase()).resolve(dataset);
            byte[] csvBefore = Files.readAllBytes(folder.resolve("procesados.csv"));
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            try {
                assertThat(launcher.run(jobs.get(kind.jobName),parameters(root,kind,UUID.randomUUID().toString())).getStatus())
                        .isEqualTo(BatchStatus.COMPLETED);
            } finally { executor.setMaxPoolSize(3); executor.setCorePoolSize(3); }
            assertThat(count(dataset)).isEqualTo(expected[kind.ordinal()]);
            assertThat(jdbc.queryForList("SELECT * FROM legacy_row WHERE dataset=? ORDER BY record_key",dataset)).isEqualTo(before);
            assertThat(Files.readAllBytes(folder.resolve("procesados.csv"))).isEqualTo(csvBefore);
            int total = Files.readAllLines(folder.resolve("control.csv")).stream().skip(1).mapToInt(line -> {
                String[] values = line.split(",");
                int read = Integer.parseInt(values[1]);
                assertThat(read).isEqualTo(Integer.parseInt(values[2]) + Integer.parseInt(values[3]) + Integer.parseInt(values[4]));
                return read;
            }).sum();
            assertThat(total).isEqualTo(expected[kind.ordinal()]);
        }
    }

    @Test void transientFailureAfterDatabaseWritesRollsBackAndRetriesWithoutLosingRows() throws Exception {
        Path input = fixture("retry");
        var parameters = parameters(input,Kind.DAILY,"original");
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (calls.getAndIncrement() == 0) throw new TransientDataAccessResourceException("simulated after write");
            return null;
        }).when(writer).write(any());
        try {
            var execution = launcher.run(jobs.get(Kind.DAILY.jobName),parameters);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(count(parameters.getString("dataset"))).isEqualTo(8);
            assertThat(execution.getStepExecutions().stream().filter(s -> s.getStepName().startsWith("legacyWorker:"))
                    .mapToLong(StepExecution::getRollbackCount).sum()).isPositive();
        } finally { reset(writer); }
    }

    @Test void weekThreeSingleFilesHaveFourRangesAndEveryPhysicalLineExactlyOnce() throws Exception {
        Path root = Path.of("../data/semana_3").toAbsolutePath().normalize();
        for (Kind kind : Kind.values()) {
            var params = parameters(root, kind, UUID.randomUUID().toString());
            var execution = launcher.run(jobs.get(kind.jobName), params);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            var workers = execution.getStepExecutions().stream()
                    .filter(s -> s.getStepName().startsWith("legacyWorker:")).toList();
            assertThat(workers).hasSize(4);
            assertThat(workers).allSatisfy(s -> assertThat(s.getReadCount()).isEqualTo(250));
            String dataset = params.getString("dataset");
            assertThat(count(dataset)).isEqualTo(1000);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT line_number) FROM legacy_row WHERE dataset=?", Integer.class, dataset)).isEqualTo(1000);
            assertThat(jdbc.queryForObject("SELECT MIN(line_number) FROM legacy_row WHERE dataset=?", Integer.class, dataset)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT MAX(line_number) FROM legacy_row WHERE dataset=?", Integer.class, dataset)).isEqualTo(1001);
            var before = jdbc.queryForList("SELECT * FROM legacy_row WHERE dataset=? ORDER BY record_key", dataset);
            executor.setCorePoolSize(1); executor.setMaxPoolSize(1);
            try {
                assertThat(launcher.run(jobs.get(kind.jobName), parameters(root, kind, UUID.randomUUID().toString())).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            } finally { executor.setMaxPoolSize(3); executor.setCorePoolSize(3); }
            assertThat(jdbc.queryForList("SELECT * FROM legacy_row WHERE dataset=? ORDER BY record_key", dataset)).isEqualTo(before);
        }
    }

    @Test void automaticJobRestartResumesCheckpointAfterRetryExhaustion() throws Exception {
        Path input = fixture("restart");
        var parameters = parameters(input,Kind.DAILY,"original");
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger committedAtFailure = new AtomicInteger();
        doAnswer(invocation -> {
            Chunk<ProcessedRow> chunk = invocation.getArgument(0);
            invocation.callRealMethod();
            if (fail.get() && chunk.getItems().stream().anyMatch(r -> r.source().line() >= 6))
                throw new TransientDataAccessResourceException("simulated sustained failure");
            return null;
        }).when(writer).write(any());
        Job job = jobs.get(Kind.DAILY.jobName);
        ((AbstractJob) job).registerJobExecutionListener(new JobExecutionListener() {
            @Override public void afterJob(JobExecution execution) {
                if (execution.getJobParameters().equals(parameters) && execution.getStatus() == BatchStatus.FAILED) {
                    committedAtFailure.set(count(parameters.getString("dataset")));
                    fail.set(false);
                }
            }
        });
        try {
            coordinator.run(job,parameters);
            JobInstance instance = explorer.getJobInstance(job.getName(),parameters);
            assertThat(explorer.getJobExecutions(instance)).extracting(JobExecution::getStatus)
                    .containsExactlyInAnyOrder(BatchStatus.FAILED,BatchStatus.COMPLETED);
            assertThat(committedAtFailure.get()).isEqualTo(4);
            assertThat(count(parameters.getString("dataset"))).isEqualTo(8);
        } finally { reset(writer); }
    }

    @Test void manuallyCalculatedFixtureExcludesInvalidAndDuplicateRowsFromTotals() throws Exception {
        Path input = temporary.resolve("known/semana_1");
        Files.createDirectories(input);
        Files.writeString(input.resolve(Kind.DAILY.filename),Kind.DAILY.header + "\n"
                + "1,2024-01-01,100,credito\n1,2024/01/01,100.00,credito\n"
                + "2,2024-01-01,40,debito\n3,2024-01-01,-10,debito\n");
        var parameters = parameters(input.getParent(),Kind.DAILY,"original");
        assertThat(launcher.run(jobs.get(Kind.DAILY.jobName),parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var rows = jdbc.queryForList(ReportService.UNIQUE_ROWS + "SELECT transaction_type,SUM(amount) AS total FROM ranked WHERE rn=1 GROUP BY transaction_type ORDER BY transaction_type",
                parameters.getString("dataset"),Kind.DAILY.name());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("total").toString()).isEqualTo("100.00");
        assertThat(rows.get(1).get("total").toString()).isEqualTo("40.00");
    }

    @Test void wrongHeaderFailsWithoutPublishingReports() throws Exception {
        Path input = temporary.resolve("bad/semana_1");
        Files.createDirectories(input);
        Files.writeString(input.resolve(Kind.DAILY.filename),"id,fecha,monto,wrong\n1,2024-01-01,10,credito\n");
        var parameters = parameters(input.getParent(),Kind.DAILY,"original");
        assertThat(launcher.run(jobs.get(Kind.DAILY.jobName),parameters).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count(parameters.getString("dataset"))).isZero();
        assertThat(temporary.resolve("output/daily").resolve(parameters.getString("dataset"))).doesNotExist();
    }

    private int count(String dataset) { return jdbc.queryForObject("SELECT COUNT(*) FROM legacy_row WHERE dataset=?",Integer.class,dataset); }
    private Path fixture(String name) throws Exception {
        Path folder = temporary.resolve(name).resolve("semana_1");
        Files.createDirectories(folder);
        StringBuilder csv = new StringBuilder(Kind.DAILY.header).append('\n');
        for (int i=1;i<=8;i++) csv.append(i).append(",2024-01-01,100,credito\n");
        Files.writeString(folder.resolve(Kind.DAILY.filename),csv);
        return folder.getParent();
    }
}
