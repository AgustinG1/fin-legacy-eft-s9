package cl.duoc.eft.batch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.dao.TransientDataAccessResourceException;

class JobCoordinatorTest {
    @Test void automaticallyRestartsRecoverableFailedExecutionWithIdenticalParameters() throws Exception {
        JobLauncher launcher = mock(JobLauncher.class);
        Job job = mock(Job.class);
        JobParameters parameters = new JobParametersBuilder().addString("dataset", "fixture").toJobParameters();
        JobExecution failed = new JobExecution(new JobInstance(1L,"fixture"),1L,parameters);
        failed.setStatus(BatchStatus.FAILED);
        failed.addFailureException(new TransientDataAccessResourceException("temporary"));
        JobExecution completed = new JobExecution(new JobInstance(1L,"fixture"),2L,parameters);
        completed.setStatus(BatchStatus.COMPLETED);
        when(launcher.run(job,parameters)).thenReturn(failed,completed);
        new JobCoordinator(launcher,2,0).run(job,parameters);
        verify(launcher,times(2)).run(same(job),same(parameters));
    }

    @Test void permanentFailureDoesNotLoopAndExhaustedRetriesFailExplicitly() throws Exception {
        JobLauncher launcher = mock(JobLauncher.class);
        Job job = mock(Job.class);
        JobParameters parameters = new JobParameters();
        JobExecution failed = new JobExecution(new JobInstance(1L,"fixture"),1L,parameters);
        failed.setStatus(BatchStatus.FAILED);
        failed.addFailureException(new IllegalArgumentException("wrong header"));
        when(launcher.run(job,parameters)).thenReturn(failed);
        assertThatThrownBy(() -> new JobCoordinator(launcher,2,0).run(job,parameters)).isInstanceOf(IllegalStateException.class);
        verify(launcher,times(1)).run(job,parameters);
        failed.addFailureException(new TransientDataAccessResourceException("offline"));
        clearInvocations(launcher);
        assertThatThrownBy(() -> new JobCoordinator(launcher,2,0).run(job,parameters)).isInstanceOf(IllegalStateException.class);
        verify(launcher,times(3)).run(job,parameters);
    }
}
