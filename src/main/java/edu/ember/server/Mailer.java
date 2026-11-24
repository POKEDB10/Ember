package edu.ember.server;

import java.io.IOException;

/**
 * Sends one plain-text email. The server uses {@link SmtpMailer} when SMTP is configured; without
 * it, codes are printed on the server console (for running locally), and tests plug in their own.
 * @concept interfaces, Strategy pattern, lambdas
 */
@FunctionalInterface
public interface Mailer {
    void send(String to, String subject, String body) throws IOException;

    /** No SMTP configured: shows the message on the server console, with the address masked. */
    static Mailer console() {
        return (to, subject, body) -> System.out.println("[mail, SMTP not configured] to " + mask(to)
                + ": " + subject + " (set EMBER_SMTP_* in config/secrets.properties to send real email)");
    }

    /** a***@example.com: enough to tell addresses apart in a log, not to read them (ASCII: any console shows it). */
    static String mask(String email) {
        int at = email.indexOf('@');
        return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
