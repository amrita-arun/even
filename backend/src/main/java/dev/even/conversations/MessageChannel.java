package dev.even.conversations;

import java.util.UUID;

/**
 * Delivers one inbound text into the conversation state machine.
 * The simulated channel is what tests and the iMessage page use.
 * The Spectrum cloud bridge is a separate Node process.
 */
public interface MessageChannel {

  ConversationService.Turn deliver(UUID userId, String text, String externalId);
}
