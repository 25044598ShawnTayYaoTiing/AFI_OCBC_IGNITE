package com.afi.resolve;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DocumentUploadErrorsTest {
  @Test
  void imageFailuresHaveDistinctActionableMessages() {
    assertThat(DocumentUploadErrors.imageError("IMAGE_TOO_SMALL").getMessage()).contains("200 pixels", "thumbnail");
    assertThat(DocumentUploadErrors.imageError("IMAGE_BLURRY").getMessage()).contains("in focus");
    assertThat(DocumentUploadErrors.imageError("IMAGE_CROPPED").getMessage()).contains("cut off");
    assertThat(DocumentUploadErrors.imageError("NO_DOCUMENT_FIELDS").getMessage()).contains("actual form", "logo");
    assertThat(DocumentUploadErrors.imageError("INVALID_IMAGE").getMessage()).contains("decoded");
    assertThat(DocumentUploadErrors.imageError("IMAGE_UNREADABLE").status()).isEqualTo(422);
  }

  @Test
  void providerOrConversionFailuresCannotBeMislabelledAsBadPhotos() {
    assertThat(DocumentUploadErrors.imageError("AI_SERVICE_ERROR")).isNull();
    assertThat(DocumentUploadErrors.imageError("IMAGE_CONVERSION_FAILED")).isNull();
    assertThat(DocumentUploadErrors.imageError("VALIDATION_ERROR")).isNull();
    assertThat(DocumentUploadErrors.imageError("untrusted provider text")).isNull();
  }
}
