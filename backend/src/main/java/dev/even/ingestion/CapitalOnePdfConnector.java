package dev.even.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Capital One's browser "print transactions" PDF (Chrome/Skia export).
 * Purchases are positive dollars; payments are negative — matching Even's sign.
 */
@Component
public class CapitalOnePdfConnector {

  private static final Pattern DATE_LINE = Pattern.compile(
      "^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{1,2})\\b(.*)$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern AMOUNT_AT_END = Pattern.compile("(-?\\$[\\d,]+\\.\\d{2})\\s*$");
  private static final Pattern FOOTER_DATE = Pattern.compile(
      "(\\d{1,2})/(\\d{1,2})/(\\d{2}),");
  private static final Pattern MASKED_ID = Pattern.compile("\\.\\.\\.\\d{4}");
  private static final Pattern CATEGORY_AND_CARD = Pattern.compile(
      "\\s+(Dining|Grocery|Merchandise|Payment|Other Travel|Phone/Cable|Gas/Automotive|"
          + "Entertainment|Travel|Health Care|Professional Services|Fee/Interest Charge)\\s+"
          + ".+?\\.?\\s*$",
      Pattern.CASE_INSENSITIVE);
  private static final DateTimeFormatter MONTH_FMT =
      DateTimeFormatter.ofPattern("MMM", Locale.US);

  private static final Set<String> SKIP_EXACT = Set.of(
      "DATE DESCRIPTION CATEGORY CARD AMOUNT",
      "CAPITAL ONE");

  public SyncPage fetch(UUID accountId, InputStream pdf) {
    String text = extractText(pdf);
    int statementYear = inferStatementYear(text);
    List<String> lines = cleanLines(text);
    List<ProviderTransaction> added = parseTransactions(accountId, lines, statementYear);
    return SyncPage.ofAdded(added);
  }

  static String extractText(InputStream pdf) {
    try (PDDocument doc = Loader.loadPDF(pdf.readAllBytes())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      return stripper.getText(doc);
    } catch (IOException e) {
      throw new IllegalArgumentException("failed to read Capital One PDF", e);
    }
  }

  static int inferStatementYear(String text) {
    Matcher m = FOOTER_DATE.matcher(text);
    if (m.find()) {
      int twoDigit = Integer.parseInt(m.group(3));
      return 2000 + twoDigit;
    }
    return LocalDate.now().getYear();
  }

  static List<String> cleanLines(String text) {
    List<String> lines = new ArrayList<>();
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      if (SKIP_EXACT.contains(line.toUpperCase(Locale.ROOT))) {
        continue;
      }
      if (line.startsWith("Posted Transactions")) {
        continue;
      }
      if (line.startsWith("Total:")) {
        continue;
      }
      if (line.startsWith("https://")) {
        continue;
      }
      if (FOOTER_DATE.matcher(line).lookingAt()) {
        continue;
      }
      if (line.matches("\\d+/\\d+")) {
        continue;
      }
      // "10/19/26, 9:41 AM Capital One" already caught by FOOTER_DATE lookingAt
      if (line.equalsIgnoreCase("Capital One")) {
        continue;
      }
      lines.add(line);
    }
    return lines;
  }

  static List<ProviderTransaction> parseTransactions(
      UUID accountId, List<String> lines, int statementYear) {
    List<ProviderTransaction> out = new ArrayList<>();
    int i = 0;
    while (i < lines.size()) {
      Matcher dateMatch = DATE_LINE.matcher(lines.get(i));
      if (!dateMatch.matches()) {
        i++;
        continue;
      }
      String monthToken = dateMatch.group(1);
      int day = Integer.parseInt(dateMatch.group(2));
      List<String> body = new ArrayList<>();
      String sameLineRest = dateMatch.group(3).strip();
      if (!sameLineRest.isEmpty()) {
        body.add(sameLineRest);
      }
      i++;
      while (i < lines.size() && !DATE_LINE.matcher(lines.get(i)).matches()) {
        body.add(lines.get(i));
        i++;
      }

      String amountText = null;
      List<String> descriptionParts = new ArrayList<>();
      for (String part : body) {
        Matcher amountMatch = AMOUNT_AT_END.matcher(part.strip());
        if (amountMatch.find()) {
          amountText = amountMatch.group(1).replace(",", "");
          String before = part.strip().substring(0, amountMatch.start()).strip();
          if (!before.isEmpty() && !MASKED_ID.matcher(before).matches()) {
            descriptionParts.add(before);
          }
        } else if (MASKED_ID.matcher(part.strip()).matches()) {
          // card / funding account mask only
        } else {
          descriptionParts.add(part.strip());
        }
      }
      if (amountText == null) {
        throw new IllegalArgumentException(
            "Capital One PDF row starting " + monthToken + " " + day + " has no amount");
      }

      long amountCents = MoneyParser.dollarsToCents(amountText.replace("$", ""));
      String description = cleanDescription(String.join(" ", descriptionParts));
      LocalDate postedOn = resolveDate(monthToken, day, statementYear);
      String kind = amountCents < 0 ? "payment" : "purchase";
      String externalId = CsvConnector.contentHash(accountId, postedOn, amountCents, description);
      String rawJson = """
          {"source":"capital_one_pdf","posted_on":"%s","description":%s,"amount_cents":%d,"kind":"%s"}
          """.formatted(postedOn, jsonString(description), amountCents, kind).strip();

      out.add(new ProviderTransaction(
          externalId,
          Optional.empty(),
          amountCents,
          "USD",
          postedOn,
          "posted",
          description,
          kind,
          rawJson));
    }
    return out;
  }

  static String cleanDescription(String raw) {
    String desc = MASKED_ID.matcher(raw).replaceAll(" ").strip();
    desc = CATEGORY_AND_CARD.matcher(desc).replaceFirst("").strip();
    desc = desc.replaceAll("\\p{Co}+", "").strip();
    desc = desc.replaceAll("\\s{2,}", " ").strip();
    if (desc.isEmpty()) {
      throw new IllegalArgumentException("Capital One PDF row has empty description");
    }
    return desc;
  }

  static LocalDate resolveDate(String monthToken, int day, int statementYear) {
    Month month = Month.from(MONTH_FMT.parse(capitalize(monthToken)));
    return LocalDate.of(statementYear, month, day);
  }

  private static String capitalize(String month) {
    String lower = month.toLowerCase(Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
