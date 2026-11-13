package com.afi.resolve;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DocumentDateTest {
  @Test
  void acceptsUnambiguousDatesAndRejectsMissingImpossibleAndFutureValues() {
    var service = new DocumentService(null, null, null, null, null);
    assertTrue(service.validDate("2026-10-30"));
    assertTrue(service.validDate("30 October 2026"));
    assertTrue(service.validDate("30 Oct 2026"));
    assertFalse(service.validDate("31 February 2026"));
    assertFalse(service.validDate("[NOT COMPLETED]"));
    assertFalse(service.validDate("01/02/2026"));
    assertFalse(service.validDate("30 October 2099"));
    var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Singapore"));
    assertTrue(service.validDate(today.toString()));
    assertFalse(service.validDate(today.plusDays(1).toString()));
  }
}
