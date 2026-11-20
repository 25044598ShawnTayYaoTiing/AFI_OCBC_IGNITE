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
      var message = dispatcher.genericMessage("synthetic@example.invalid");
      dispatcher.mail.send(message);
      String raw = received.get(5, TimeUnit.SECONDS);
      assertThat(raw)
          .contains("AFI Resolve <demo@example.invalid>")
          .contains("[TEST]_AFI_Resolve_=E2=80=93_Hackathon_Prototype")
          .contains("independent student hackathon prototype")
          .doesNotContain("OCBC");
    }
  }

  @Test
  void strictAllowlistBlocksUnauthorisedRecipientsAndGenericContentIsForced() {
    var dispatcher =
        new CustomerEmailDispatcher(
            mock(Store.class),
            mock(PlatformTransactionManager.class),
            true,
            "127.0.0.1",
            1025,
            "",
            "",
            "afiresolve.demo@gmail.com",
            false);
    dispatcher.testRecipients = "team@example.test";
    dispatcher.requireAllowlist = true;
    assertThat(dispatcher.isAuthorisedRecipient("team@example.test")).isTrue();
    assertThat(dispatcher.isAuthorisedRecipient("TEAM@EXAMPLE.TEST")).isTrue();
    assertThat(dispatcher.isAuthorisedRecipient("stranger@example.test")).isFalse();
    assertThat(dispatcher.isAuthorisedRecipient("team@example.invalid")).isFalse();
    assertThat(dispatcher.formattedFrom()).isEqualTo("AFI Resolve <afiresolve.demo@gmail.com>");
    dispatcher.testRecipients = "";
    assertThat(dispatcher.isAuthorisedRecipient("anyone@example.test")).isFalse();
  }

  @Test
  void sendContactCodeIncludesOtpWithHackathonNoticeAndEnforcesAllowlist() {
    var dispatcher =
        new CustomerEmailDispatcher(
            mock(Store.class),
            mock(PlatformTransactionManager.class),
            true,
            "127.0.0.1",
            1025,
            "",
            "",
            "afiresolve.demo@gmail.com",
            false);
    dispatcher.mail = mock(org.springframework.mail.javamail.JavaMailSenderImpl.class);
    dispatcher.testRecipients = "team@example.test";
    dispatcher.requireAllowlist = true;
    assertThat(dispatcher.canVerifyContact("team@example.test")).isTrue();
    assertThat(dispatcher.canVerifyContact("stranger@example.test")).isFalse();
    dispatcher.sendContactCode("team@example.test", "482913");
    var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(dispatcher.mail, times(1)).send(captor.capture());
    assertThat(captor.getValue().getFrom()).isEqualTo("AFI Resolve <afiresolve.demo@gmail.com>");
    assertThat(captor.getValue().getTo()).containsExactly("team@example.test");
    assertThat(captor.getValue().getText())
        .contains("482913")
        .contains("independent student hackathon prototype")
        .doesNotContain("OCBC");
    assertThatThrownBy(() -> dispatcher.sendContactCode("stranger@example.test", "482913"))
        .isInstanceOf(ApiException.class);
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
