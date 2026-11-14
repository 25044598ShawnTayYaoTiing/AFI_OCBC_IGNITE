package com.afi.resolve;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReleaseController {
  @Value("${AFI_RELEASE_SHA:local}")
  String version;

  @GetMapping("/api/version")
  public Map<String, String> version() {
    return Map.of("version", version);
  }
}
