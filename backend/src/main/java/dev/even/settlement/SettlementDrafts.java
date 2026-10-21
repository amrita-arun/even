package dev.even.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds Venmo deep links and Zelle reminder text. Does not send money. */
public final class SettlementDrafts {

  private SettlementDrafts() {}

  public static String dollars(long amountCents) {
    return BigDecimal.valueOf(amountCents, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  /**
   * {@code recipients} is a Venmo username. A display name or first name must not
   * be substituted: Venmo will attach a random account with that name.
   * The iMessage agent must collect first name, last name, and the Venmo username
   * before it drafts a request.
   */
  public static Draft venmo(String handle, String displayName, long amountCents, String note) {
    String amount = dollars(amountCents);
    String who = displayName == null || displayName.isBlank() ? "them" : displayName.trim();
    String safeNote = note == null || note.isBlank() ? who : note.trim();
    String recipient = handle == null || handle.isBlank()
        ? null
        : handle.trim().replaceFirst("^@", "");
    String url = "https://venmo.com/?txn=charge"
        + "&amount=" + encode(amount)
        + "&note=" + encode(safeNote);
    if (recipient != null) {
      url += "&recipients=" + encode(recipient);
    }
    String text = recipient == null
        ? "Venmo request for $" + amount + ". No username saved for " + who
            + ", so pick them yourself — a first name matches the wrong person."
        : "Venmo request for $" + amount + " to @" + recipient + " — " + safeNote;
    return new Draft("venmo", url, text);
  }

  public static Draft zelle(String handle, String displayName, long amountCents, String note) {
    String target = handle == null || handle.isBlank() ? displayName : handle.trim();
    String amount = dollars(amountCents);
    String safeNote = note == null || note.isBlank() ? "dinner" : note.trim();
    String text = "Hi " + displayName + " — could you Zelle me $" + amount
        + " for " + safeNote + "? (" + target + ")";
    return new Draft("zelle", null, text);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public record Draft(String channel, String url, String text) {}
}
