package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<?> uploadTooLarge() {
    return ResponseEntity.status(413)
        .body(
            map(
                "code",
                "UPLOAD_TOO_LARGE",
                "message",
                "PDF limit is 10 MB per file and 105 MB per request"));
  }

  final FailureAudit audit;

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<?> notFound() {
    return ResponseEntity.status(404)
        .body(map("code", "NOT_FOUND", "message", "API route not found"));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> methodNotAllowed() {
    return ResponseEntity.status(405)
        .body(map("code", "METHOD_NOT_ALLOWED", "message", "Method not supported for this route"));
  }

  public ApiErrors(FailureAudit audit) {
    this.audit = audit;
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<?> api(ApiException e, HttpServletRequest request) {
    if (request.getMethod().equals("POST")) {
      String[] parts = request.getRequestURI().split("/");
      String id =
          parts.length > 3 && parts[2].equals("exceptions") && parts[3].startsWith("EX-")
              ? parts[3]
              : null;
      audit.record(
          id,
          request.getUserPrincipal() == null ? "ANONYMOUS" : request.getUserPrincipal().getName(),
          e.code(),
          e.getMessage());
    }
    return ResponseEntity.status(e.status()).body(map("code", e.code(), "message", e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            map(
                "code",
                "INVALID_REQUEST",
                "message",
                "Request fields are missing, malformed, or outside permitted limits"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<?> denied() {
    return ResponseEntity.status(403)
        .body(
            map(
                "code",
                "FORBIDDEN",
                "message",
                "This operation requires Resolution Management privileges"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    LoggerFactory.getLogger(ApiErrors.class)
        .error("Request failed: {}", e.getClass().getSimpleName());
    return ResponseEntity.status(500)
        .body(
            map(
                "code",
                "INTERNAL_ERROR",
                "message",
                "Operation failed safely. Refresh the case before retrying."));
  }
}
