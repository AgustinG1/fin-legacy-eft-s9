package cl.duoc.eft.batch;

import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="eft.run.enabled", havingValue="true", matchIfMissing=true)
public class BatchRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);
    private final Environment environment;
    private final Map<String, Job> jobs;
    private final JobCoordinator coordinator;

    public BatchRunner(Environment environment, Map<String, Job> jobs, JobCoordinator coordinator) {
        this.environment = environment;
        this.jobs = jobs;
        this.coordinator = coordinator;
    }

    @Override
    public void run(ApplicationArguments ignored) throws Exception {
        Path input = Path.of(environment.getRequiredProperty("eft.input-directory")).toAbsolutePath().normalize();
        Path output = Path.of(environment.getRequiredProperty("eft.output-directory")).toAbsolutePath().normalize();
        String selected = environment.getProperty("eft.run.jobs", "all");
        if (!selected.equals("all") && !jobs.containsKey(selected)) throw new IllegalArgumentException("Job desconocido: " + selected);
        String rate = environment.getProperty("eft.interest-rate", "");
        String period = environment.getProperty("eft.interest-period", "");
        if (selected.equals("all") || selected.equals(Kind.INTEREST.jobName)) {
            rate = LegacyProcessor.checkedRate(rate).toPlainString();
            if (period.isBlank() || period.length() > 40) throw new IllegalArgumentException("Indique eft.interest-period (maximo 40 caracteres)");
            log.info("REGLA_INTERESES tasaDelPeriodo={} periodo={}", rate, period);
        }
        for (Kind kind : Kind.values()) {
            if (!selected.equals("all") && !selected.equals(kind.jobName)) continue;
            String policy = kind == Kind.INTEREST ? "rate=" + rate + "|period=" + period : "validacion-v1";
            var parameters = new JobParametersBuilder()
                    .addString("dataset", InputManifest.fingerprint(input, kind, policy))
                    .addString("kind", kind.name())
                    .addLong("partitionRows", environment.getProperty("eft.partition-rows", Long.class, 250L))
                    .addString("input", input.toString())
                    .addString("output", output.toString(), false)
                    .addString("policy", policy)
                    .addString("rate", kind == Kind.INTEREST ? rate : "")
                    .addString("period", kind == Kind.INTEREST ? period : "")
                    .addString("replay", environment.getProperty("eft.replay", "original"))
                    .toJobParameters();
            coordinator.run(jobs.get(kind.jobName), parameters);
        }
    }
}
