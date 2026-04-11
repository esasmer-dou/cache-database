package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.queue.AdminIncidentRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class SmtpIncidentChannel extends AbstractIncidentChannel {

    private final AdminMonitoringConfig config;

    SmtpIncidentChannel(AdminMonitoringConfig config) {
        super("smtp");
        this.config = config;
    }

    @Override
    public boolean deliver(AdminIncidentRecord record) {
        if (config.incidentEmail().toAddresses().isEmpty()) {
            markDropped();
            return false;
        }
        for (int attempt = 0; attempt <= Math.max(0, config.incidentEmail().maxRetries()); attempt++) {
            Socket activeSocket = null;
            try (Socket socket = createSocket()) {
                connectSocket(socket);
                activeSocket = socket;
                if (activeSocket instanceof SSLSocket sslSocket) {
                    sslSocket.startHandshake();
                    verifyPinnedCertificate(sslSocket.getSession());
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(activeSocket.getOutputStream(), StandardCharsets.UTF_8));
                expectReply(reader, 220);
                send(writer, ehloOrHelo() + " " + config.incidentEmail().heloHost());
                expectReply(reader, 250);
                if (!config.incidentEmail().implicitTls() && config.incidentEmail().startTls()) {
                    send(writer, "STARTTLS");
                    expectReply(reader, 220);
                    activeSocket = upgradeToTls(socket);
                    reader = new BufferedReader(new InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8));
                    writer = new BufferedWriter(new OutputStreamWriter(activeSocket.getOutputStream(), StandardCharsets.UTF_8));
                    send(writer, "EHLO " + config.incidentEmail().heloHost());
                    expectReply(reader, 250);
                }
                if (hasAuth()) {
                    authenticate(reader, writer);
                }
                send(writer, "MAIL FROM:<" + config.incidentEmail().fromAddress() + ">");
                expectReply(reader, 250);
                for (String recipient : config.incidentEmail().toAddresses()) {
                    send(writer, "RCPT TO:<" + recipient + ">");
                    expectReply(reader, 250);
                }
                send(writer, "DATA");
                expectReply(reader, 354);
                writer.write(renderMessage(record));
                writer.write("\r\n.\r\n");
                writer.flush();
                expectReply(reader, 250);
                send(writer, "QUIT");
                expectReply(reader, 221);
                if (activeSocket != socket) {
                    activeSocket.close();
                }
                markDelivered();
                return true;
            } catch (IOException exception) {
                if (activeSocket != null) {
                    try {
                        activeSocket.close();
                    } catch (IOException ignored) {
                    }
                }
                markFailed(exception);
            }
            sleepQuietly(config.incidentEmail().retryBackoffMillis());
        }
        return false;
    }

    private String renderMessage(AdminIncidentRecord record) {
        return "From: " + config.incidentEmail().fromAddress() + "\r\n"
                + "To: " + String.join(", ", config.incidentEmail().toAddresses()) + "\r\n"
                + "Subject: " + config.incidentEmail().subjectPrefix() + " " + record.code() + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "Incident code: " + record.code() + "\r\n"
                + "Severity: " + record.severity().name() + "\r\n"
                + "Description: " + record.description() + "\r\n"
                + "Source: " + record.source() + "\r\n"
                + "Recorded at: " + record.recordedAt() + "\r\n"
                + "Fields: " + record.fields() + "\r\n";
    }

    private void send(BufferedWriter writer, String command) throws IOException {
        writer.write(command);
        writer.write("\r\n");
        writer.flush();
    }

    private String expectReply(BufferedReader reader, int expectedCode) throws IOException {
        String lastLine = null;
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("SMTP expected " + expectedCode + " but received EOF");
            }
            if (!line.startsWith(String.valueOf(expectedCode))) {
                throw new IOException("SMTP expected " + expectedCode + " but got: " + line);
            }
            lastLine = line;
            if (line.length() < 4 || line.charAt(3) != '-') {
                return lastLine;
            }
        }
    }

    private void authenticate(BufferedReader reader, BufferedWriter writer) throws IOException {
        String mechanism = config.incidentEmail().authMechanism() == null
                ? "PLAIN"
                : config.incidentEmail().authMechanism().trim().toUpperCase(Locale.ROOT);
        switch (mechanism) {
            case "LOGIN" -> {
                send(writer, "AUTH LOGIN");
                expectReply(reader, 334);
                send(writer, Base64.getEncoder().encodeToString(config.incidentEmail().username().getBytes(StandardCharsets.UTF_8)));
                expectReply(reader, 334);
                send(writer, Base64.getEncoder().encodeToString(config.incidentEmail().password().getBytes(StandardCharsets.UTF_8)));
                expectReply(reader, 235);
            }
            default -> {
                String payload = "\0" + config.incidentEmail().username() + "\0" + config.incidentEmail().password();
                send(writer, "AUTH PLAIN " + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
                expectReply(reader, 235);
            }
        }
    }

    private String ehloOrHelo() {
        return config.incidentEmail().startTls() || hasAuth() ? "EHLO" : "HELO";
    }

    private boolean hasAuth() {
        return config.incidentEmail().username() != null && !config.incidentEmail().username().isBlank();
    }

    private Socket createSocket() throws IOException {
        if (config.incidentEmail().implicitTls()) {
            return sslContext().getSocketFactory().createSocket();
        }
        return new Socket();
    }

    private void connectSocket(Socket socket) throws IOException {
        socket.connect(
                new InetSocketAddress(config.incidentEmail().smtpHost(), config.incidentEmail().smtpPort()),
                (int) Math.max(1, config.incidentEmail().connectTimeoutMillis())
        );
        socket.setSoTimeout((int) Math.max(1, config.incidentEmail().readTimeoutMillis()));
    }

    private Socket upgradeToTls(Socket socket) throws IOException {
        SSLSocketFactory factory = sslContext().getSocketFactory();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                socket,
                config.incidentEmail().smtpHost(),
                config.incidentEmail().smtpPort(),
                false
        );
        sslSocket.setUseClientMode(true);
        sslSocket.setSoTimeout((int) Math.max(1, config.incidentEmail().readTimeoutMillis()));
        sslSocket.startHandshake();
        verifyPinnedCertificate(sslSocket.getSession());
        return sslSocket;
    }

    private SSLContext sslContext() throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            if (config.incidentEmail().trustAllCertificates()) {
                sslContext.init(null, new TrustManager[]{new PermissiveTrustManager()}, null);
            } else if (config.incidentEmail().trustStorePath() != null && !config.incidentEmail().trustStorePath().isBlank()) {
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(loadTrustStore());
                sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            } else {
                sslContext.init(null, null, null);
            }
            return sslContext;
        } catch (Exception exception) {
            throw new IOException("Failed to initialize SMTP TLS context", exception);
        }
    }

    private KeyStore loadTrustStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(
                config.incidentEmail().trustStoreType() == null || config.incidentEmail().trustStoreType().isBlank()
                        ? KeyStore.getDefaultType()
                        : config.incidentEmail().trustStoreType()
        );
        char[] password = config.incidentEmail().trustStorePassword() == null
                ? new char[0]
                : config.incidentEmail().trustStorePassword().toCharArray();
        try (var inputStream = Files.newInputStream(Path.of(config.incidentEmail().trustStorePath()))) {
            keyStore.load(inputStream, password);
        }
        return keyStore;
    }

    private void verifyPinnedCertificate(SSLSession session) throws IOException {
        if (config.incidentEmail().pinnedServerCertificateSha256().isEmpty()) {
            return;
        }
        try {
            Certificate[] peerCertificates = session.getPeerCertificates();
            if (peerCertificates == null || peerCertificates.length == 0) {
                throw new IOException("SMTP TLS pinning failed: no peer certificate was presented");
            }
            Set<String> allowed = config.incidentEmail().pinnedServerCertificateSha256().stream()
                    .map(this::normalizeFingerprint)
                    .collect(Collectors.toUnmodifiableSet());
            ArrayList<String> presented = new ArrayList<>(peerCertificates.length);
            for (Certificate certificate : peerCertificates) {
                String fingerprint = fingerprint(certificate);
                presented.add(fingerprint);
                if (allowed.contains(fingerprint)) {
                    return;
                }
            }
            throw new IOException("SMTP TLS pinning failed: no configured fingerprint matched peer certificates " + presented);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("SMTP TLS pinning failed", exception);
        }
    }

    private String fingerprint(Certificate certificate) throws Exception {
        byte[] encoded = certificate.getEncoded();
        return normalizeFingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)));
    }

    private String normalizeFingerprint(String fingerprint) {
        return fingerprint.replace(":", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
    }

    private void sleepQuietly(long sleepMillis) {
        if (sleepMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PermissiveTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }
}
