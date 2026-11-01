package com.afi.resolve;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class ResolveApplication {
  public static void main(String[] args) {
    SpringApplication.run(ResolveApplication.class, args);
  }
}
