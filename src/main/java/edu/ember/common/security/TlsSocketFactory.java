package edu.ember.common.security;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/** Creates TLS 1.3 sockets backed by Ember's own keystore and truststore. @concept Factory pattern, TLS */
public final class TlsSocketFactory {
    private static final String TLS_PROTOCOL = "TLSv1.3";

    private TlsSocketFactory() {
        throw new AssertionError("No instances");
    }

    public static SSLServerSocket createServerSocket(Path keyStorePath, char[] password, int port)
            throws IOException, GeneralSecurityException {
        KeyStore keyStore = loadKeyStore(keyStorePath, password);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
        context.init(keyManagers.getKeyManagers(), null, null);
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"));
        socket.setEnabledProtocols(new String[] {TLS_PROTOCOL});
        return socket;
    }

    public static SSLSocket createClientSocket(Path trustStorePath, char[] password,
            String host, int port) throws IOException, GeneralSecurityException {
        KeyStore trustStore = loadKeyStore(trustStorePath, password);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
        context.init(null, trustManagers.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
        socket.setEnabledProtocols(new String[] {TLS_PROTOCOL});
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        return socket;
    }

    private static KeyStore loadKeyStore(Path path, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }
}
