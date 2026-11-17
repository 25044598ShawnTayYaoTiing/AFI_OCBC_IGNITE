package com.afi.resolve;

import java.util.*;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/** Detect private document media from bytes; never trust a browser MIME type. */
final class DocumentContent {
  static String type(byte[] bytes) {
    if (bytes.length >= 5 && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
    if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
    if (bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return "image/jpeg";
    throw new ApiException(400, "INVALID_DOCUMENT", "Upload a PDF, JPG, JPEG or PNG with matching file contents");
  }

  static String extension(byte[] bytes) {
    return switch(type(bytes)) { case "application/pdf" -> ".pdf"; case "image/png" -> ".png"; default -> ".jpg"; };
  }

  static void validate(String name, byte[] bytes) {
    if (name == null || name.length() > 200 || bytes.length == 0 || bytes.length > 10 * 1024 * 1024)
      throw new ApiException(400, "INVALID_DOCUMENT", "Choose nonempty documents up to 10 MB each");
    String mime = type(bytes), lower = name.toLowerCase(Locale.ROOT);
    if (!(mime.equals("application/pdf") && lower.endsWith(".pdf") || mime.equals("image/png") && lower.endsWith(".png") || mime.equals("image/jpeg") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))))
      throw new ApiException(400, "INVALID_DOCUMENT", "The filename does not match the file contents");
    if (mime.startsWith("image/")) {
      try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw new IllegalArgumentException();
        var reader = readers.next();
        try {
          reader.setInput(input);
          if ((long)reader.getWidth(0) * reader.getHeight(0) > 25_000_000) throw new IllegalArgumentException();
          reader.read(0);
        } finally { reader.dispose(); }
      } catch (Exception error) {
        throw new ApiException(400, "INVALID_IMAGE", "Use a complete readable JPG or PNG up to 25 megapixels");
      }
    }
  }
}
