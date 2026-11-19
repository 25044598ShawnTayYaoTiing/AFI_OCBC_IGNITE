package com.afi.resolve;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Shared canonical address normalisation and safe component comparison across OneMap and policy checks. */
final class AddressNormaliser {
  private static final Pattern POSTAL = Pattern.compile("\\b(?:SINGAPORE\\s+)?(\\d{6})\\b");
  private static final Pattern UNIT = Pattern.compile("#([A-Z0-9]+)-([A-Z0-9]+)");
  private static final Pattern BLOCK_LEADING = Pattern.compile("^(\\d+[A-Z]?)\\s+(.+)$");
  private static final Pattern BUILDING_THEN_BLOCK =
      Pattern.compile("^([A-Z0-9][A-Z0-9 '\\-&/@]{1,80}?)(?:[,\\n]+\\s*(?:BLOCK\\s+|BLK\\.?\\s+)?|\\s+(?:BLOCK|BLK)\\.?\\s*)(\\d+[A-Z]?)(?:,|\\s)\\s*(.+)$");
  private static final Set<String> STREET_TYPES =
      Set.of(
          "ROAD", "STREET", "AVENUE", "DRIVE", "WAY", "WALK", "LANE", "CRESCENT", "CLOSE",
          "PLACE", "TERRACE", "BOULEVARD", "QUAY", "LINK", "VIEW", "GREEN", "PARK", "RISE",
          "HILL", "VALE", "LOOP", "GARDENS", "SQUARE", "CIRCLE", "GROVE", "HEIGHTS", "RIDGE",
          "PARKWAY", "CENTRAL", "TURN", "FIELD", "BOW", "PLAINS", "VISTA", "PROMENADE",
          "MALL", "COURT", "GATE", "MEWS", "CONCOURSE");
  private static final Set<String> NON_BUILDING_QUALIFIERS =
      Set.of(
          "ROAD", "STREET", "AVENUE", "DRIVE", "WAY", "WALK", "LANE", "CRESCENT", "CLOSE",
          "BOULEVARD", "QUAY", "LINK", "LOOP", "PARKWAY", "CENTRAL", "TURN",
          "NORTH", "SOUTH", "EAST", "WEST", "UPPER", "LOWER", "NEW",
          "OLD", "EXTENSION", "EXT", "SECTOR", "PHASE", "RING", "SERVICE");

  record Parts(String block, String street, String unit, String postal, String normalised) {}

  private AddressNormaliser() {}

  static String clean(String value) {
    return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
        .replaceAll("\\s+", " ")
        .trim();
  }

  static String stripLeadingZeros(String token) {
    if (token.matches("^[A-Z]+0+\\d.*")) {
      String normalized = token.replaceFirst("^([A-Z]+)0+(?=\\d)", "$1");
      if (!normalized.isEmpty()) return normalized;
    }
    String stripped = token.replaceFirst("^0+(?=\\d)", "");
    return stripped.isEmpty() ? token : stripped;
  }

  static String extractPostal(Object value) {
    String work = canonicalUpper(Objects.toString(value, ""));
    var matcher = POSTAL.matcher(work);
    var found = new LinkedHashSet<String>();
    while (matcher.find()) found.add(matcher.group(1));
    return found.size() == 1 ? found.iterator().next() : "";
  }

  static String expandAbbreviationsUpper(String value) {
    return value
        .replaceAll("\\bRD\\b", "ROAD")
        .replaceAll("\\bAVE\\b", "AVENUE")
        .replaceAll("(?<!^\\d{1,5}[A-Z]?\\s)(?<!^)\\bST\\.?\\b", "STREET")
        .replaceAll("\\bDR\\b", "DRIVE")
        .replaceAll("\\bCRES\\b", "CRESCENT")
        .replaceAll("\\bCL\\b", "CLOSE")
        .replaceAll("\\bPL\\b", "PLACE")
        .replaceAll("\\bLN\\b", "LANE")
        .replaceAll("\\bTER\\b", "TERRACE")
        .replaceAll("\\bBLVD\\b", "BOULEVARD")
        .replaceAll("\\bJLN\\b", "JALAN")
        .replaceAll("\\bLOR\\b", "LORONG")
        .replaceAll("\\bBT\\b", "BUKIT")
        .replaceAll("\\bUPP\\b", "UPPER")
        .replaceAll("\\bNTH\\b", "NORTH")
        .replaceAll("\\bSTH\\b", "SOUTH")
        .replaceAll("\\bCTR\\b", "CENTRE")
        .replaceAll("\\bPKWY\\b", "PARKWAY")
        .replaceAll("\\bGDNS\\b", "GARDENS")
        .replaceAll("\\bHTS\\b", "HEIGHTS")
        .replaceAll("\\bCTRL\\b", "CENTRAL")
        .replaceAll("\\bPK\\b", "PARK")
        .replaceAll("\\bSQ\\b", "SQUARE");
  }

  static String canonicalUpper(String value) {
    String work =
        clean(value)
            .toUpperCase(Locale.ROOT)
            .replaceAll(
                "\\b(?:BUILDING\\s*/\\s*ESTATE|BLOCK\\s*/\\s*HOUSE\\s+NO\\.?|BUILDING\\s+NAME|ESTATE\\s+NAME|STREET\\s+NAME|ROAD\\s+NAME|BLOCK\\s+NO\\.?|HOUSE\\s+NO\\.?|POSTAL(?:\\s*CODE)?|POSTCODE)\\b",
                " ")
            .replaceAll("\\bS\\s*\\(?\\s*(\\d{6})\\s*\\)?\\b", "SINGAPORE $1")
            // Support unit numbers written without '#' (e.g. "109 Rivervale Walk 12-34"), while protecting "BLK 10-12" and leading block ranges.
            .replaceAll("(?<=[\\s,(])(?<!\\bBLK\\s)(?<!\\bBLOCK\\s)(?<!^)(?:NO\\.?\\s+)?((?:B\\d{1,2}|\\d{1,3}))\\s*[-/]\\s*((?:[A-Z]?\\d{1,5}[A-Z]?))(?=[\\s,.)]|$)", "#$1-$2")
            .replaceFirst("^BLOCK\\s*|^BLK\\.?\\s*", "")
            .replaceAll("\\b(?:BLOCK|BLK)\\.?\\s*(?=\\d)", "")
            .replaceAll("\\b(?:UNIT|APT|APARTMENT|SUITE|FLAT)\\s*(?:NO\\.?\\s*)?#?\\s*(?=[A-Z0-9]{1,4}\\s*[-/])", "#")
            .replaceAll("\\b(?:UNIT|APT|APARTMENT|SUITE|FLAT)\\s*(?:NO\\.?\\s*)?", "")
            .replaceAll("#\\s*", "#")
            .replaceAll("(#[A-Z0-9]+)\\s*[-/]\\s*([A-Z0-9]+)(?:\\s*/\\s*[A-Z0-9]+)*", "$1-$2");
    work = expandAbbreviationsUpper(work);
    work = work.replaceAll("[,.()/]", " ").replaceAll("\\s+", " ").trim();
    work = work.replaceAll("(.*?)\\b(SINGAPORE\\s+\\d{6})\\s+(#\\w+-\\S+)(.*)", "$1 $3 $2 $4");
    return work.replaceAll("\\s+", " ").trim();
  }

  static String canonicalPlainUpper(String value) {
    return expandAbbreviationsUpper(
            clean(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " "))
        .replaceAll("\\s+", " ")
        .trim();
  }

  static String oneMapBase(String address, String postal) {
    String p = Objects.toString(postal, "").trim();
    String stripped =
        canonicalUpper(address)
            .replaceAll("(?:\\bUNIT\\s*(?:NO\\.?\\s*)?)?(?:#|\\bUNIT\\s*(?:NO\\.?\\s*)?)\\s*[A-Z0-9]{1,4}\\s*[-/]\\s*[A-Z0-9]{1,6}", " ");
    if (!p.isEmpty()) {
      stripped = stripped.replaceAll("\\bSINGAPORE\\s+" + Pattern.quote(p) + "\\b", " ").replace(p, " ");
    }
    stripped = stripped.replaceAll("\\bSINGAPORE\\b", " ");
    return canonicalPlainUpper(stripped);
  }

  static boolean isClearStreetText(String base) {
    if (base == null || base.isBlank()) return false;
    String types = String.join("|", STREET_TYPES);
    return base.matches(
        "^\\d+[A-Z]? (?:(?:JALAN|LORONG) [A-Z0-9 ]+|.*\\b(?:" + types + ")\\b(?: \\d+)?)$");
  }

  static Parts parse(Object value, String fallbackPostal) {
    String rawClean =
        Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
            .replaceAll("[\\r\\t]+", " ")
            .replaceAll(" *\\n *", "\n")
            .replaceAll(" {2,}", " ")
            .trim()
            .toUpperCase(Locale.ROOT);
    String normUpper = canonicalUpper(Objects.toString(value, ""));
    String work = normUpper;
    String postal = "";
    var postalMatcher = POSTAL.matcher(work);
    while (postalMatcher.find()) postal = postalMatcher.group(1);
    if (!postal.isEmpty()) {
      work = work.replaceAll("\\b(?:SINGAPORE\\s+)?\\d{6}\\b", " ");
    } else if (fallbackPostal != null && fallbackPostal.trim().matches("\\d{6}")) {
      postal = fallbackPostal.trim();
    }
    work = work.replaceAll("\\bSINGAPORE\\b", " ");

    String unit = "";
    var unitMatcher = UNIT.matcher(work);
    if (unitMatcher.find()) {
      unit = "#" + stripLeadingZeros(unitMatcher.group(1)) + "-" + stripLeadingZeros(unitMatcher.group(2));
      work = work.substring(0, unitMatcher.start()) + " " + work.substring(unitMatcher.end());
    }
    work = work.replaceAll("\\s+", " ").trim();

    String block = "";
    String street = work;
    var blockMatcher = BLOCK_LEADING.matcher(work);
    if (blockMatcher.matches()) {
      block = blockMatcher.group(1);
      street = blockMatcher.group(2).trim();
    } else {
      // Handle leading building names before block number (e.g. "Rivervale Court, Blk 109 Rivervale Walk" or "Rivervale Court Blk 109 Rivervale Walk").
      String rawNoPostalUnit =
          rawClean
              .replaceAll("\\b(?:BUILDING\\s*/\\s*ESTATE|BLOCK\\s*/\\s*HOUSE\\s+NO\\.?|BUILDING\\s+NAME|ESTATE\\s+NAME|STREET\\s+NAME|ROAD\\s+NAME|BLOCK\\s+NO\\.?|HOUSE\\s+NO\\.?|POSTAL\\s*CODE|POSTCODE)\\b", " ")
              .replaceAll("(?:\\b(?:UNIT|APT|APARTMENT|SUITE|FLAT)\\s*(?:NO\\.?\\s*)?)?(?:#|\\b(?:UNIT|APT|APARTMENT|SUITE|FLAT)\\s*(?:NO\\.?\\s*)?)\\s*[A-Z0-9]{1,4}\\s*[-/]\\s*[A-Z0-9]{1,6}(?:\\s*/\\s*[A-Z0-9]+)*", " ")
              .replaceAll("(?<=[\\s,(])(?<!\\bBLK\\s)(?<!\\bBLOCK\\s)(?<!^)(?:NO\\.?\\s+)?(?:B\\d{1,2}|\\d{1,3})\\s*[-/]\\s*(?:[A-Z]?\\d{1,5}[A-Z]?)(?=[\\s,.)]|$)", " ")
              .replaceAll("\\bS\\s*\\(?\\s*\\d{6}\\s*\\)?\\b", " ")
              .replaceAll("\\b(?:SINGAPORE\\s+)?\\d{6}\\b", " ")
              .replaceAll("\\bSINGAPORE\\b", " ")
              .replaceAll(" *[,\\n]+ *", ", ")
              .replaceAll(" {2,}", " ")
              .trim();
      var leadMatcher = BUILDING_THEN_BLOCK.matcher(rawNoPostalUnit);
      if (leadMatcher.matches()) {
        String leadBuilding = canonicalPlainUpper(leadMatcher.group(1));
        block = leadMatcher.group(2);
        String tailStreet = canonicalPlainUpper(leadMatcher.group(3));
        street = (tailStreet + " " + leadBuilding).trim();
      }
    }
    return new Parts(
        block.toLowerCase(Locale.ROOT),
        street.toLowerCase(Locale.ROOT),
        unit.toLowerCase(Locale.ROOT),
        postal,
        normUpper.toLowerCase(Locale.ROOT));
  }

  static boolean matchesCandidate(Parts parts, OneMapClient.Candidate candidate) {
    if (parts == null || candidate == null || parts.block().isEmpty()) return false;
    if (!parts.postal().isEmpty() && !parts.postal().equals(candidate.postalCode())) return false;
    if (!parts.block().equalsIgnoreCase(candidate.block())) return false;
    String road = canonicalPlainUpper(candidate.road()).toLowerCase(Locale.ROOT);
    String building = canonicalPlainUpper(candidate.building()).toLowerCase(Locale.ROOT);
    if (road.isEmpty()) return false;
    if (parts.street().equals(road)) return true;
    return !building.isEmpty()
        && (parts.street().equals(road + " " + building) || parts.street().equals(building + " " + road));
  }

  static boolean sameStreetWithOptionalBuilding(String streetA, String streetB) {
    if (streetA == null || streetB == null || streetA.isBlank() || streetB.isBlank()) return false;
    if (streetA.equals(streetB)) return true;
    String shorter = streetA.length() <= streetB.length() ? streetA : streetB;
    String longer = streetA.length() <= streetB.length() ? streetB : streetA;
    if (shorter.split(" ").length < 2 || !longer.startsWith(shorter + " ")) return false;
    String extra = longer.substring(shorter.length() + 1).trim();
    if (extra.isEmpty() || !endsAtStreetBoundary(shorter)) return false;
    String[] extraTokens = extra.toUpperCase(Locale.ROOT).split(" ");
    // Numeric tokens ("2" in "Rivervale Walk 2"), single-letter suffixes, or street/directional
    // qualifiers anywhere in extra text indicate an unverified or distinct street component.
    if (extraTokens.length > 6) return false;
    for (String token : extraTokens) {
      if (token.length() < 2 || !token.matches("^[A-Z]+$") || NON_BUILDING_QUALIFIERS.contains(token)) {
        return false;
      }
    }
    return true;
  }

  private static boolean endsAtStreetBoundary(String streetLower) {
    String[] tokens = streetLower.toUpperCase(Locale.ROOT).split(" ");
    if (tokens.length == 0) return false;
    String last = tokens[tokens.length - 1];
    if (STREET_TYPES.contains(last)) return true;
    return tokens.length >= 2
        && last.matches("^\\d+$")
        && STREET_TYPES.contains(tokens[tokens.length - 2]);
  }
}
