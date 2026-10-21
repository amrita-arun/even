package dev.even.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.even.enrichment.EnrichmentService;
import dev.even.proposals.ProposalService;
import dev.even.rules.RuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransactionProcessor {

  private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);

  private final EnrichmentService enrichment;
  private final RuleService rules;
  private final ProposalService proposals;
  private final ObjectMapper mapper;

  public TransactionProcessor(
      EnrichmentService enrichment,
      RuleService rules,
      ProposalService proposals,
      ObjectMapper mapper) {
    this.enrichment = enrichment;
    this.rules = rules;
    this.proposals = proposals;
    this.mapper = mapper;
  }

  @Transactional
  public void process(UUID userId, UUID transactionId) {
    enrichment.enrich(userId, transactionId);
    int flags = rules.evaluateTransaction(userId, transactionId);
    proposals.consider(userId, transactionId);
    log.debug("processed transaction {} flags={}", transactionId, flags);
  }

  public void processJob(JobService.JobRecord job) throws Exception {
    JsonNode payload = mapper.readTree(job.payloadJson());
    UUID transactionId = UUID.fromString(payload.get("transactionId").asText());
    process(job.userId(), transactionId);
  }
}
