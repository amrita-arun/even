package dev.even.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobPoller {

  private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

  private final JobService jobs;
  private final TransactionProcessor processor;

  public JobPoller(JobService jobs, TransactionProcessor processor) {
    this.jobs = jobs;
    this.processor = processor;
  }

  @Scheduled(fixedDelayString = "${even.jobs.poll-delay-ms:2000}")
  public void poll() {
    // Drain a batch so imports of full statements don't take minutes.
    for (int i = 0; i < 50; i++) {
      var next = jobs.claimNext();
      if (next.isEmpty()) {
        return;
      }
      var job = next.get();
      try {
        processor.processJob(job);
        jobs.markDone(job.id());
      } catch (Exception e) {
        log.warn("job {} failed: {}", job.id(), e.getMessage());
        jobs.markFailed(job.id(), e.getMessage());
      }
    }
  }
}
