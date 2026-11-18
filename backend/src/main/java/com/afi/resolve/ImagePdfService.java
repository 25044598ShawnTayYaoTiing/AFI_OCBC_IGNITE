package com.afi.resolve;

import static com.afi.resolve.Domain.map;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

/** Retain private originals and obtain an ordered PDF from the existing internal Python service. */
@Service
public class ImagePdfService {
  final Store store;
  final AiClient ai;
  final DocumentStorage storage;
  final FailureAudit audit;

  public ImagePdfService(Store store, AiClient ai, DocumentStorage storage, FailureAudit audit) {
    this.store = store; this.ai = ai; this.storage = storage; this.audit = audit;
  }

  record Converted(byte[] pdf, ArrayNode originals) {}

  Converted convert(String submission, List<byte[]> pages, List<String> names, String actor) throws Exception {
    var originals = store.json.createArrayNode();
    try {
      for (int index = 0; index < pages.size(); index++) {
        byte[] raw = pages.get(index);
        String key = UUID.randomUUID() + DocumentContent.extension(raw);
        // Register metadata before a storage write that could partially succeed.
        originals.addObject().put("storage_key", key).put("filename", names.get(index))
            .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)))
            .put("size_bytes", raw.length);
        storage.put(key, raw);
      }
    } catch (Exception error) {
      cleanup(originals);
      throw error;
    }
    final byte[] pdf;
    try {
      pdf = requestPdf(pages);
    } catch (Exception error) {
      if (error instanceof ApiException invalid && invalid.status() < 500) {
        cleanup(originals);
        throw invalid;
      }
      try {
        // Independent audit transaction survives rollback of a new case/intake request.
        audit.imageConversionFailed(submission, actor, originals);
      } catch (Exception auditFailure) {
        cleanup(originals);
        throw new ApiException(503, "IMAGE_CONVERSION_FAILED", "Conversion and audit storage are unavailable. Your request was not accepted; keep the selected files and retry.");
      }
      throw new ApiException(503, "IMAGE_CONVERSION_FAILED", "PDF conversion is temporarily unavailable. Your original images are securely retained for audit. Keep your files selected and retry the same request.");
    }
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        public void afterCompletion(int status) { if (status != STATUS_COMMITTED) cleanup(originals); }
      });
    return new Converted(pdf, originals);
  }

  byte[] requestPdf(List<byte[]> pages) {
    final org.springframework.http.ResponseEntity<byte[]> response;
    try {
      response = ai.client.post().uri("/documents/convert-images")
        .body(map("images_base64", pages.stream().map(raw -> Base64.getEncoder().encodeToString(raw)).toList()))
        .retrieve().toEntity(byte[].class);
    } catch (org.springframework.web.client.RestClientResponseException error) {
      if (error.getStatusCode().value() == 400 || error.getStatusCode().value() == 422) {
        String code = "";
        try { code = store.json.readTree(error.getResponseBodyAsString()).path("code").asText(); }
        catch (Exception ignored) { /* Never forward arbitrary upstream response text. */ }
        var imageError = DocumentUploadErrors.imageError(code);
        if (imageError != null) throw imageError;
      }
      throw error;
    }
    byte[] pdf = response.getBody();
    if (pdf == null || pdf.length > 16 * 1024 * 1024 || !"application/pdf".equals(DocumentContent.type(pdf))
        || !Integer.toString(pages.size()).equals(response.getHeaders().getFirst("X-PDF-Page-Count")))
      throw new ApiException(502, "INVALID_CONVERTED_PDF", "Conversion did not return a valid PDF and page count");
    return pdf;
  }

  void cleanup(ArrayNode originals) {
    for (var original : originals) try { storage.delete(original.path("storage_key").asText()); }
    catch (Exception error) {
      org.slf4j.LoggerFactory.getLogger(ImagePdfService.class).warn("Private original cleanup failed; reconcile retained image metadata");
    }
  }
}
