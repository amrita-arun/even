package dev.even.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LoggingNotifier implements Notifier {

  private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

  @Override
  public void largePurchaseFlagged(
      UUID userId, UUID transactionId, UUID flagId, String ruleName) {
    log.info(
        "LargePurchaseFlagged user={} transaction={} flag={} rule={}",
        userId,
        transactionId,
        flagId,
        ruleName);
  }
}
