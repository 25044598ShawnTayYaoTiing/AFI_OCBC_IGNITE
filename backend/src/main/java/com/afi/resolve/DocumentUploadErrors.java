package com.afi.resolve;

/** Customer-safe messages: never expose provider response text or image metadata. */
final class DocumentUploadErrors {
  static ApiException imageError(String code) {
    String message = switch (code) {
      case "INVALID_IMAGE", "INVALID_DOCUMENT" -> "This file could not be decoded as a supported document. Export it again as a PDF, JPG or PNG and replace the selected file.";
      case "IMAGE_TOO_SMALL" -> "This image is too small to read reliably. Upload the original screenshot or photo, at least 200 pixels on each side, instead of a thumbnail.";
      case "IMAGE_BLURRY" -> "The document text appears blurry. Retake the photo in focus or upload the original full-resolution screenshot.";
      case "IMAGE_CROPPED" -> "Part of the document appears cut off. Upload a full-page image with all document edges and fields visible.";
      case "IMAGE_INCOMPLETE" -> "The document appears incomplete. Include all pages of the same form, in order. Blank fields can be handled by the normal correction process.";
      case "IMAGE_UNREADABLE" -> "The image could not be read as document evidence. Preview the selected file and replace it with a clear image of the actual form or supporting document.";
      case "NO_DOCUMENT_FIELDS" -> "No readable form fields were found in this image. Preview the file and upload the actual form or supporting document rather than a logo, icon or other graphic.";
      case "IMAGE_QUALITY" -> "The image service could not read this document clearly. Preview the selected file, check its resolution and include the full document before retrying.";
      default -> null;
    };
    return message == null ? null : new ApiException(422, code, message);
  }
}
