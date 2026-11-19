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
      Pattern.compile("^([A-Z][A-Z '\\-&/]{1,80}?)[,\\n]+\\s*(?:BLOCK\\s+|BLK\\.?\\s+)?(\\d+[A-Z]?)\\s+(.+)$");
  private static final Set<String> STREET_TYPES =
      Set.of(
          "ROAD", "STREET", "AVENUE", "DRIVE", "WAY", "WALK", "LANE", "CRESCENT", "CLOSE",
          "PLACE", "TERRACE", "BOULEVARD", "QUAY", "LINK", "VIEW", "GREEN", "PARK", "RISE",
          "HILL", "VALE", "LOOP", "GARDENS", "SQUARE", "CIRCLE", "GROVE", "HEIGHTS", "RIDGE",
          "PARKWAY", "CENTRAL");
  private static final Set<String> NON_BUILDING_QUALIFIERS =
      Set.of(
          "ROAD", "STREET", "AVENUE", "DRIVE", "WAY", "WALK", "LANE", "CRESCENT", "CLOSE",
          "PLACE", "TERRACE", "BOULEVARD", "QUAY", "LINK", "VIEW", "GREEN", "PARK", "RISE",
          "HILL", "VALE", "LOOP", "GARDENS", "SQUARE", "CIRCLE", "GROVE", "HEIGHTS", "RIDGE",
          "PARKWAY", "CENTRAL", "NORTH", "SOUTH", "EAST", "WEST", "UPPER", "LOWER", "NEW",
          "OLD", "EXTENSION", "EXT", "SECTOR", "PHASE", "RING", "SERVICE");

  record Parts(String block, String street, String unit, String postal, String normalised) {}

  private AddressNormaliser() {}

  static String clean(String value) {
    return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
        .replaceAll("\\s+", " ")
        .trim();
  }

  static String stripLeadingZeros(String token) {
    String stripped = token.replaceFirst("^0+(?=\\d)", "");
    return stripped.isEmpty() ? token : stripped;
  }

  static String expandAbbreviationsUpper(String value) {
    return value
        .replaceAll("\\bRD\\b", "ROAD")
        .replaceAll("\\bAVE\\b", "AVENUE")
        .replaceAll("\\bST\\b", "STREET")
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
                "\\b(?:BUILDING\\s*/\\s*ESTATE|BLOCK\\s*/\\s*HOUSE\\s+NO\\.?|BUILDING\\s+NAME|ESTATE\\s+NAME|STREET\\s+NAME|ROAD\\s+NAME|BLOCK\\s+NO\\.?|HOUSE\\s+NO\\.?|POSTAL\\s*CODE|POSTCODE)\\b",
                " ")
            .replaceAll("\\bS\\s*\\(?\\s*(\\d{6})\\s*\\)?\\b", "SINGAPORE $1")
            .replaceFirst("^BLOCK\\s+|^BLK\\.?\\s+", "")
            .replaceAll("\\b(?:BLOCK|BLK)\\.?\\s+(?=\\d)", "")
            .replaceAll("\\bUNIT\\s*(?:NO\\.?\\s*)?#?\\s*(?=[A-Z0-9]{1,4}\\s*[-/])", "#")
            .replaceAll("\\bUNIT\\s*(?:NO\\.?\\s*)?", "")
            .replaceAll("#\\s*", "#")
            .replaceAll("(#[A-Z0-9]+)\\s*[-/]\\s*", "$1-");
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
    return base.matches("^\\d+[A-Z]? .*(?:" + types + "|JALAN|LORONG)(?: \\d+)?$");
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
      // Handle leading building names separated by comma/newline before block number, e.g. "Rivervale Court, Blk 109 Rivervale Walk".
      String rawNoPostalUnit =
          rawClean
              .replaceAll("\\b(?:BUILDING\\s*/\\s*ESTATE|BLOCK\\s*/\\s*HOUSE\\s+NO\\.?|BUILDING\\s+NAME|ESTATE\\s+NAME|STREET\\s+NAME|ROAD\\s+NAME|BLOCK\\s+NO\\.?|HOUSE\\s+NO\\.?|POSTAL\\s*CODE|POSTCODE)\\b", " ")
              .replaceAll("(?:\\bUNIT\\s*(?:NO\\.?\\s*)?)?(?:#|\\bUNIT\\s*(?:NO\\.?\\s*)?)\\s*[A-Z0-9]{1,4}\\s*[-/]\\s*[A-Z0-9]{1,6}", " ")
              .replaceAll("\\bS\\s*\\(\\s*\\d{6}\\s*\\)", " ")
              .replaceAll("\\bS\\s+\\d{6}\\b", " ")
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
    String extra;
    if (longer.startsWith(shorter + " ")) {
      extra = longer.substring(shorter.length() + 1).trim();
    } else if (longer.endsWith(" " + shorter)) {
      extra = longer.substring(0, longer.length() - shorter.length() - 1).trim();
    } else {
      return false;
    }
    if (extra.isEmpty() || !endsAtStreetBoundary(shorter)) return false;
    String[] extraTokens = extra.toUpperCase(Locale.ROOT).split(" ");
    // Numeric suffixes ("2" in "Rivervale Walk 2") or street/directional words ("North", "Way", "Extension")
    // indicate a distinct street rather than an optional building name.
    if (extraTokens[0].matches("^\\d+[A-Z]?$")) return false;
    if (NON_BUILDING_QUALIFIERS.contains(extraTokens[0])) return false;
    if (extraTokens.length == 1 && NON_BUILDING_QUALIFIERS.contains(extraTokens[0])) return false;
    return Arrays.stream(extraTokens).anyMatch(tok -> tok.length() >= 2 && tok.matches("^[A-Z]+$"));
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
