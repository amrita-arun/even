package dev.even.conversations;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * In-process channel. It never calls Photon.
 */
@Component
public class SimulatedMessageChannel implements MessageChannel {

  private final ConversationService conversations;

  public SimulatedMessageChannel(ConversationService conversations) {
    this.conversations = conversations;
  }

  @Override
  public ConversationService.Turn deliver(UUID userId, String text, String externalId) {
    return conversations.receive(userId, text, externalId);
  }
}
