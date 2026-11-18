package com.afi.resolve;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

/** Opt-in real HTTP Customer API -> conversion -> Gemini PDF extraction -> validation -> RM PDF. */
@EnabledIfSystemProperty(named = "image.test.ai-url", matches = "http://127\\.0\\.0\\.1:[0-9]+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=jdbc:h2:mem:image-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa", "spring.datasource.password=", "afi.demo-enabled=true",
  "afi.seed-named-demo-customers=false", "afi.reviewer-password=reviewer-demo",
  "afi.ai-key=image-e2e-service-key", "afi.storage-provider=local", "afi.mail-enabled=false"
})
class ImageUploadEndToEndTest {
  @Autowired TestRestTemplate http;
  @Autowired Store store;

  @DynamicPropertySource
  static void localServices(DynamicPropertyRegistry properties) throws Exception {
    properties.add("afi.ai-url", () -> System.getProperty("image.test.ai-url"));
    Path directory = Files.createTempDirectory("afi-image-e2e-");
    properties.add("afi.document-storage", directory::toString);
  }

  @Test
  void customerImagesBecomeOnePdfWithOrderedReferencesAndPrivateAuditOriginals() throws Exception {
    var staff = http.withBasicAuth("reviewer", "reviewer-demo");
    var provision = staff.postForEntity("/api/documents/customers", Map.of(
      "username", "image-e2e-customer", "password", "image-e2e-password", "customerName", "Alex Tan",
      "customerReference", "SYN-IMAGE-E2E", "relationshipManager", "reviewer"), JsonNode.class);
    assertThat(provision.getStatusCode()).isEqualTo(HttpStatus.OK);
    var login = http.postForEntity("/api/customer/auth/token", Map.of("username", "image-e2e-customer", "password", "image-e2e-password"), JsonNode.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = login.getBody().path("accessToken").asText();

    var image = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1200, 1600);
    graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
    String[] lines = {"SYNTHETIC - NOT AN OFFICIAL BANK FORM", "CUSTOMER INFORMATION FORM",
      "Customer Name: Alex Tan", "Customer Reference: SYN-IMAGE-E2E", "Mailing Address: 12 Example Lane",
      "Residential Address: 12 Example Lane", "Contact Information: alex@example.invalid",
      "Declaration Date: 2026-11-17", "Signature Indicator: Not provided"};
    for (int i = 0; i < lines.length; i++) graphics.drawString(lines[i], 55, 100 + i * 95);
    graphics.dispose();
    var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
    byte[] original = output.toByteArray();
    graphics = image.createGraphics();
    graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1200, 1600);
    graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
    graphics.drawString("SYNTHETIC - NOT AN OFFICIAL BANK FORM", 55, 100);
    graphics.drawString("CUSTOMER INFORMATION FORM - PAGE 2", 55, 200);
    graphics.drawString("Identity Number: DEMO-ID-SYN-IMAGE-E2E", 55, 300);
    graphics.dispose();
    output.reset(); ImageIO.write(image, "png", output);
    byte[] second = output.toByteArray();
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("files", new ByteArrayResource(original) { public String getFilename() { return "customer-screenshot.png"; } });
    body.add("files", new ByteArrayResource(second) { public String getFilename() { return "customer-page-two.png"; } });
    body.add("combineImages", "true");
    var headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.set("Idempotency-Key", "image-e2e-request");
    var uploaded = http.postForEntity("/api/customer/submissions", new HttpEntity<>(body, headers), JsonNode.class);
    assertThat(uploaded.getStatusCode()).withFailMessage("Image upload: %s", uploaded.getBody()).isEqualTo(HttpStatus.OK);
    String id = uploaded.getBody().path("submission").path("id").asText();
    assertThat(uploaded.getBody().path("submission").path("status").asText()).isNotEqualTo("OPERATIONS");
    var detail = staff.getForObject("/api/documents/submissions/" + id, JsonNode.class);
    var document = detail.path("documents").get(0);
    // Existing policy may withhold customer contact pending RM authorisation.
    assertThat(detail.path("tasks").size()).isPositive();
    assertThat(document.path("extraction").path("method").asText()).isEqualTo("GEMINI");
    assertThat(document.path("extraction").path("requires_review").asBoolean()).isTrue();
    assertThat(document.path("extraction").path("converted_from_images").asBoolean()).isTrue();
    assertThat(document.path("extraction").path("conversion_page_count").asInt()).isEqualTo(2);
    assertThat(document.path("filename").asText()).isEqualTo("customer-screenshot.pdf");
    assertThat(document.path("fields").findValuesAsText("field_value")).contains("Alex Tan", "SYN-IMAGE-E2E");
    assertThat(document.path("fields").findValuesAsText("field_value")).contains("DEMO-ID-SYN-IMAGE-E2E");
    for (var field : document.path("fields"))
      if ("identity_number".equals(field.path("field_name").asText())) assertThat(field.path("page_number").asInt()).isEqualTo(2);
    assertThat(store.db.queryForObject("SELECT COUNT(*) FROM validation_results WHERE submission_id=?", Integer.class, id)).isPositive();
    assertThat(store.db.queryForObject("SELECT COUNT(*) FROM case_routing_history WHERE submission_id=?", Integer.class, id)).isZero();
    String preview = "/api/documents/submissions/" + id + "/versions/" + document.path("version_id").asText() + "/pdf";
    var response = staff.getForEntity(preview, byte[].class);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(new String(response.getBody(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    String originals = "/api/documents/submissions/" + id + "/versions/" + document.path("version_id").asText() + "/originals/";
    assertThat(staff.getForEntity(originals + "1", byte[].class).getBody()).isEqualTo(original);
    assertThat(staff.getForEntity(originals + "2", byte[].class).getBody()).isEqualTo(second);
    assertThat(http.getForEntity(originals + "1", byte[].class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    String artifact = System.getProperty("image.test.output", "");
    if (!artifact.isBlank()) Files.write(Path.of(artifact), response.getBody());
    assertThat(http.getForEntity(preview, byte[].class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
