package cl.duoc.eft.batch;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchConfiguration {
    private static final Logger log = LoggerFactory.getLogger(BatchConfiguration.class);

    @Bean
    public ThreadPoolTaskExecutor batchExecutor(@Value("${eft.workers}") int workers) {
        if (workers < 1 || workers > 6) throw new IllegalArgumentException("eft.workers debe estar entre 1 y 6");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setThreadNamePrefix("eft-partition-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Bean
    @StepScope
    public FlatFileItemReader<SourceRow> legacyReader(
            @Value("#{stepExecutionContext['file']}") String file,
            @Value("#{stepExecutionContext['source']}") String source,
            @Value("#{stepExecutionContext['kind']}") String kindName,
            @Value("#{stepExecutionContext['start'] ?: 0}") int start,
            @Value("#{stepExecutionContext['end'] ?: 2147483647}") int end,
            @Value("#{jobParameters['dataset']}") String dataset) {
        Kind kind = Kind.valueOf(kindName);
        return new FlatFileItemReaderBuilder<SourceRow>().name("legacyReader")
                .resource(new FileSystemResource(file)).encoding("UTF-8").strict(true)
                .comments(new String[0]).linesToSkip(1)
                .currentItemCount(start).maxItemCount(end)
                .skippedLinesCallback(header -> {
                    if (!header.replace("\uFEFF", "").trim().equals(kind.header))
                        throw new IllegalArgumentException("Cabecera inesperada: " + source);
                })
                .lineMapper((line, number) -> new SourceRow(dataset, kind, source, number, line)).build();
    }

    @Bean
    @StepScope
    public LegacyProcessor legacyProcessor(
            @Value("#{stepExecutionContext['kind']}") String kind,
            @Value("#{jobParameters['rate']}") String rate,
            @Value("#{jobParameters['period']}") String period) {
        return new LegacyProcessor(Kind.valueOf(kind), rate, period);
    }

    @Bean
    public Step legacyWorker(JobRepository repository, PlatformTransactionManager transactions,
                             FlatFileItemReader<SourceRow> reader, LegacyProcessor processor, LegacyWriter writer,
                             @Value("${eft.chunk-size}") int chunkSize,
                             @Value("${eft.retry-limit}") int retryLimit,
                             @Value("${eft.retry-delay-ms}") long retryDelay) {
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(Math.max(1, retryDelay));
        backoff.setMaxInterval(Math.max(2000, retryDelay));
        return new StepBuilder("legacyWorker", repository).<SourceRow, ProcessedRow>chunk(chunkSize, transactions)
                .reader(reader).processor(processor).writer(writer).faultTolerant()
                .retry(TransientDataAccessException.class).retry(RecoverableDataAccessException.class)
                .retry(CannotGetJdbcConnectionException.class).retryLimit(retryLimit).backOffPolicy(backoff)
                .build();
    }

    @Bean
    @StepScope
    public Partitioner inputPartitioner(
            @Value("#{jobParameters['input']}") String input,
            @Value("#{jobParameters['kind']}") String name,
            @Value("#{jobParameters['partitionRows'] ?: 250}") long partitionRows) {
        if (partitionRows < 1 || partitionRows > Integer.MAX_VALUE)
            throw new IllegalArgumentException("partitionRows debe estar entre 1 y 2147483647");
        return gridSize -> {
            var partitions = new LinkedHashMap<String, ExecutionContext>();
            Path root = Path.of(input);
            try {
                for (Path file : InputManifest.files(root, Kind.valueOf(name))) {
                    String source = InputManifest.relative(root, file);
                    long rows;
                    try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                        rows = Math.max(0, lines.count() - 1);
                    }
                    if (rows > Integer.MAX_VALUE) throw new IllegalArgumentException("Archivo demasiado grande para el lector: " + source);
                    // Fixed ranges do not change when the executor size changes on restart.
                    for (long start = 0; start < Math.max(1, rows); start += partitionRows) {
                        ExecutionContext context = new ExecutionContext();
                        context.putString("file", file.toAbsolutePath().toString());
                        context.putString("source", source);
                        context.putString("kind", name);
                        context.putInt("start", (int) start);
                        context.putInt("end", (int) Math.min(start + partitionRows, rows));
                        partitions.put(InputManifest.hash(source) + "-" + start, context);
                    }
                }
            } catch (java.io.IOException ex) { throw new IllegalStateException("No se pudieron leer los archivos", ex); }
            return partitions;
        };
    }

    @Bean
    public Job movimientosJob(JobRepository repository, PlatformTransactionManager transactions,
                               @Qualifier("legacyWorker") Step worker, Partitioner partitioner,
                               ThreadPoolTaskExecutor executor, ReportService reports) {
        return job(Kind.DAILY, repository, transactions, worker, partitioner, executor, reports);
    }

    @Bean
    public Job interesesJob(JobRepository repository, PlatformTransactionManager transactions,
                            @Qualifier("legacyWorker") Step worker, Partitioner partitioner,
                            ThreadPoolTaskExecutor executor, ReportService reports) {
        return job(Kind.INTEREST, repository, transactions, worker, partitioner, executor, reports);
    }

    @Bean
    public Job estadosJob(JobRepository repository, PlatformTransactionManager transactions,
                          @Qualifier("legacyWorker") Step worker, Partitioner partitioner,
                          ThreadPoolTaskExecutor executor, ReportService reports) {
        return job(Kind.ANNUAL, repository, transactions, worker, partitioner, executor, reports);
    }

    private Job job(Kind kind, JobRepository repository, PlatformTransactionManager transactions,
                    Step worker, Partitioner partitioner, ThreadPoolTaskExecutor executor, ReportService reports) {
        Step manager = new StepBuilder(kind.name() + "Partition", repository).partitioner(worker.getName(), partitioner)
                .step(worker).taskExecutor(executor).gridSize(executor.getMaxPoolSize()).build();
        Step export = new StepBuilder(kind.name() + "Report", repository).tasklet((contribution, context) -> {
            var parameters = contribution.getStepExecution().getJobParameters();
            String fingerprint = InputManifest.fingerprint(Path.of(parameters.getString("input")), kind,
                    parameters.getString("policy"));
            if (!fingerprint.equals(parameters.getString("dataset")))
                throw new IllegalStateException("Los archivos cambiaron durante la ejecucion; no se publican reportes");
            reports.export(fingerprint, kind, Path.of(parameters.getString("output")));
            return RepeatStatus.FINISHED;
        }, transactions).build();
        return new JobBuilder(kind.jobName, repository).start(manager).next(export)
                .listener(new JobExecutionListener() {
                    @Override public void afterJob(JobExecution execution) {
                        log.info("JOB_RESULTADO nombre={} estado={} ejecucion={} dataset={}", kind.jobName,
                                execution.getStatus(), execution.getId(), execution.getJobParameters().getString("dataset"));
                    }
                }).build();
    }
}
