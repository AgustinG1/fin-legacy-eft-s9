package cl.duoc.eft.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Service;

@Service
public class JobCoordinator {
    private static final Logger log = LoggerFactory.getLogger(JobCoordinator.class);
    private final JobLauncher launcher;
    private final int restartLimit;
    private final long delay;

    public JobCoordinator(JobLauncher launcher, @Value("${eft.restart-limit}") int restartLimit,
                          @Value("${eft.restart-delay-ms}") long delay) {
        if (restartLimit < 0 || restartLimit > 10 || delay < 0) throw new IllegalArgumentException("Limites de reinicio invalidos");
        this.launcher = launcher;
        this.restartLimit = restartLimit;
        this.delay = delay;
    }

    public void run(Job job, JobParameters parameters) throws Exception {
        for (int attempt = 0; attempt <= restartLimit; attempt++) {
            JobExecution execution;
            try { execution = launcher.run(job, parameters); }
            catch (JobInstanceAlreadyCompleteException completed) {
                log.info("JOB_YA_COMPLETADO nombre={} dataset={}", job.getName(), parameters.getString("dataset"));
                return;
            }
            if (execution.getStatus() == BatchStatus.COMPLETED) return;
            boolean recoverable = execution.getAllFailureExceptions().stream().anyMatch(JobCoordinator::recoverable)
                    || execution.getStepExecutions().stream().flatMap(s -> s.getFailureExceptions().stream())
                    .anyMatch(JobCoordinator::recoverable);
            if (execution.getStatus() != BatchStatus.FAILED || !recoverable || attempt == restartLimit)
                throw new IllegalStateException("Job " + job.getName() + " termino " + execution.getStatus()
                        + "; revisar errores. No se declarara una entrega exitosa.");
            log.warn("JOB_REINICIO nombre={} intento={} mismaInstancia={}", job.getName(), attempt + 1, execution.getJobInstance().getInstanceId());
            Thread.sleep(delay);
        }
    }

    private static boolean recoverable(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TransientDataAccessException || current instanceof RecoverableDataAccessException
                    || current instanceof CannotGetJdbcConnectionException) return true;
            if (current == current.getCause()) break;
        }
        return false;
    }
}
