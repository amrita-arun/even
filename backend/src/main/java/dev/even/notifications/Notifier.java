package dev.even.notifications;

import java.util.UUID;

public interface Notifier {
  void largePurchaseFlagged(UUID userId, UUID transactionId, UUID flagId, String ruleName);
}
