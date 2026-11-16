package com.afi.resolve;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Minimal text PDF receipt. Exact Unicode declarations remain in immutable database evidence. */
final class DeclarationReceipt {
  private DeclarationReceipt() {}

  static byte[] pdf(String type, int version, Map<String, String> fields) {
    var lines = new ArrayList<String>();
    lines.add("SYNTHETIC DEMONSTRATION - DIGITAL CUSTOMER DECLARATION");
    lines.add(type + " / form version " + version);
    lines.add("Authenticated authorisation; no handwritten signature was generated.");
    lines.add("Bank profile fields are synthetic. Submitted fields are customer assertions.");
    lines.add("Exact submitted values are preserved in the case evidence.");
    for (var entry : fields.entrySet()) {
      String line = entry.getKey() + ": " + entry.getValue();
      for (int start = 0; start < line.length(); start += 85)
        lines.add(line.substring(start, Math.min(start + 85, line.length())));
    }
    int pages = (lines.size() + 47) / 48;
    var objects = new ArrayList<String>();
    objects.add("<< /Type /Catalog /Pages 2 0 R >>");
    StringBuilder kids = new StringBuilder();
    for (int i = 0; i < pages; i++) kids.append(4 + i * 2).append(" 0 R ");
    objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + pages + " >>");
    objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
    for (int page = 0; page < pages; page++) {
      objects.add(
          "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >>"
              + " >> /Contents "
              + (5 + page * 2)
              + " 0 R >>");
      var stream = new StringBuilder("BT /F1 10 Tf 40 800 Td 15 TL\n");
      for (String line : lines.subList(page * 48, Math.min((page + 1) * 48, lines.size()))) {
        String text =
            line.replaceAll("[^\\x20-\\x7E]", "?")
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
        stream.append('(').append(text).append(") Tj T*\n");
      }
      stream.append("ET\n");
      objects.add(
          "<< /Length "
              + stream.toString().getBytes(StandardCharsets.US_ASCII).length
              + " >>\nstream\n"
              + stream
              + "endstream");
    }
    var out = new ByteArrayOutputStream();
    out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
    var offsets = new ArrayList<Integer>();
    offsets.add(0);
    for (int i = 0; i < objects.size(); i++) {
      offsets.add(out.size());
      out.writeBytes(
          ((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n")
              .getBytes(StandardCharsets.US_ASCII));
    }
    int xref = out.size();
    out.writeBytes(
        ("xref\n0 " + offsets.size() + "\n0000000000 65535 f \n")
            .getBytes(StandardCharsets.US_ASCII));
    for (int i = 1; i < offsets.size(); i++)
      out.writeBytes(
          String.format(Locale.ROOT, "%010d 00000 n \n", offsets.get(i))
              .getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(
        ("trailer\n<< /Size "
                + offsets.size()
                + " /Root 1 0 R >>\nstartxref\n"
                + xref
                + "\n%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }
}
