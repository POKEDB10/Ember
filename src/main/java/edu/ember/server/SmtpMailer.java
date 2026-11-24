package edu.ember.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small SMTP client on plain JDK sockets: implicit TLS (port 465) or STARTTLS (587), AUTH PLAIN,
 * one message per connection. The server's certificate and host name are checked by the JDK.
 * @concept networking, TLS, protocol state machine
 */
public final class SmtpMailer implements Mailer {
    /** How the connection is secured. NONE exists only for tests against a local fake server. */
    public enum Security { TLS, STARTTLS, NONE }

    private final String host;
    private final int port;
    private final Security security;
    private final String user;
    private final String password;
    private final String from;

    public SmtpMailer(String host, int port, Security security, String user, String password, String from) {
        if (security == Security.NONE && user != null) {
            throw new IllegalArgumentException("SMTP passwords are only sent over TLS");
        }
        this.host = host;
        this.port = port;
        this.security = security;
        this.user = user;
        this.password = password;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) throws IOException {
        for (String header : new String[] {to, subject, from}) {
            if (header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0) {
                throw new IOException("Line breaks are not allowed in mail headers");
            }
        }
        Socket socket = security == Security.TLS ? tls(new Socket(), true) : new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout(20_000);
            if (socket instanceof SSLSocket secure) {
                secure.startHandshake();
            }
            Session session = new Session(socket);
            session.expect(220);
            session.command("EHLO ember", 250);
            if (security == Security.STARTTLS) {
                session.command("STARTTLS", 220);
                SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, host, port, true);
                tls(secure, false);
                secure.startHandshake();
                socket = secure;
                session = new Session(secure);
                session.command("EHLO ember", 250);
            }
            if (user != null) {
                String token = Base64.getEncoder().encodeToString(("\0" + user + "\0" + password)
                        .getBytes(StandardCharsets.UTF_8));
                session.command("AUTH PLAIN " + token, 235);
            }
            session.command("MAIL FROM:<" + from + ">", 250);
            session.command("RCPT TO:<" + to + ">", 250);
            session.command("DATA", 354);
            StringBuilder message = new StringBuilder()
                    .append("From: Ember <").append(from).append(">\r\n")
                    .append("To: <").append(to).append(">\r\n")
                    .append("Subject: ").append(subject).append("\r\n")
                    .append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n")
                    .append("Content-Transfer-Encoding: 8bit\r\n\r\n");
            for (String line : body.split("\r?\n", -1)) {
                message.append(line.startsWith(".") ? "." : "").append(line).append("\r\n"); // dot-stuffing
            }
            session.command(message + ".", 250);
            session.command("QUIT", 221);
        } finally {
            socket.close();
        }
    }

    private SSLSocket tls(Socket plain, boolean create) throws IOException {
        SSLSocket secure = create ? (SSLSocket) SSLSocketFactory.getDefault().createSocket() : (SSLSocket) plain;
        SSLParameters parameters = secure.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS"); // the certificate must name this host
        secure.setSSLParameters(parameters);
        return secure;
    }

    /** Reads multi-line SMTP replies ("250-..." continues, "250 ..." ends). */
    private static final class Session {
        private final BufferedReader in;
        private final OutputStream out;

        private Session(Socket socket) throws IOException {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void command(String line, int expected) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(expected);
        }

        void expect(int expected) throws IOException {
            String line;
            do {
                line = in.readLine();
                if (line == null || line.length() < 3) {
                    throw new IOException("The mail server closed the connection");
                }
            } while (line.length() > 3 && line.charAt(3) == '-');
            int code = Integer.parseInt(line.substring(0, 3));
            if (code != expected && !(expected == 250 && code == 251)) {
                throw new IOException("The mail server answered " + code); // never echo the reply: it may quote credentials
            }
        }
    }
}
