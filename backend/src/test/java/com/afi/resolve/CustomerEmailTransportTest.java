package com.afi.resolve;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.transaction.PlatformTransactionManager;

class CustomerEmailTransportTest {
  @Test
  void configuredTransportHandsMailToAnInProcessSmtpSinkOnly() throws Exception {
    try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        var executor = Executors.newSingleThreadExecutor()) {
      server.setSoTimeout(5000);
      var received =
          executor.submit(
              () -> {
                try (var socket = server.accept()) {
                  socket.setSoTimeout(5000);
                  var input =
                      new BufferedReader(
                          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                  var output =
                      new PrintWriter(
                          new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                          true);
                  output.print("220 localhost AFI test sink\r\n");
                  output.flush();
                  boolean data = false;
                  var message = new StringBuilder();
                  String line;
                  while ((line = input.readLine()) != null) {
                    if (data) {
                      if (line.equals(".")) {
                        data = false;
                        output.print("250 accepted by local test sink\r\n");
                        output.flush();
                      } else message.append(line).append('\n');
                      continue;
                    }
                    String command = line.toUpperCase(Locale.ROOT);
                    if (command.startsWith("EHLO") || command.startsWith("HELO"))
                      output.print("250 localhost\r\n");
                    else if (command.startsWith("DATA")) {
                      data = true;
                      output.print("354 end with dot\r\n");
                    } else if (command.startsWith("QUIT")) {
                      output.print("221 bye\r\n");
                      output.flush();
                      break;
                    } else output.print("250 OK\r\n");
                    output.flush();
                  }
                  return message.toString();
                }
              });
      var dispatcher =
          new CustomerEmailDispatcher(
              mock(Store.class),
              mock(PlatformTransactionManager.class),
              true,
              "127.0.0.1",
              server.getLocalPort(),
              "",
              "",
              "demo@example.invalid",
              false);
      var message = new SimpleMailMessage();
      message.setFrom("demo@example.invalid");
      message.setTo("synthetic@example.invalid");
      message.setSubject("Synthetic correction");
      message.setText("Open your synthetic case for correction instructions.");
      dispatcher.mail.send(message);
      assertThat(received.get(5, TimeUnit.SECONDS))
          .contains("Synthetic correction", "synthetic@example.invalid", "correction instructions");
    }
  }

  @Test
  void disabledDispatcherDoesNotReadOrSendOutboxMessages() {
    var store = mock(Store.class);
    new CustomerEmailDispatcher(
            store, mock(PlatformTransactionManager.class), false, "", 587, "", "", "", true)
        .dispatch();
    verifyNoInteractions(store);
  }
}
