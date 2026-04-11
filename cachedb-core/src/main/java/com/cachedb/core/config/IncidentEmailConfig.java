package com.reactor.cachedb.core.config;

import java.util.ArrayList;
import java.util.List;

public record IncidentEmailConfig(
        boolean enabled,
        String smtpHost,
        int smtpPort,
        boolean implicitTls,
        boolean startTls,
        boolean trustAllCertificates,
        long connectTimeoutMillis,
        long readTimeoutMillis,
        int maxRetries,
        long retryBackoffMillis,
        String heloHost,
        String username,
        String password,
        String authMechanism,
        String trustStorePath,
        String trustStorePassword,
        String trustStoreType,
        List<String> pinnedServerCertificateSha256,
        String fromAddress,
        List<String> toAddresses,
        String subjectPrefix
) {
    public static Builder builder() {
        return new Builder();
    }

    public static IncidentEmailConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled;
        private String smtpHost = "127.0.0.1";
        private int smtpPort = 25;
        private boolean implicitTls;
        private boolean startTls;
        private boolean trustAllCertificates;
        private long connectTimeoutMillis = 2_000;
        private long readTimeoutMillis = 5_000;
        private int maxRetries = 2;
        private long retryBackoffMillis = 500;
        private String heloHost = "localhost";
        private String username = "";
        private String password = "";
        private String authMechanism = "PLAIN";
        private String trustStorePath = "";
        private String trustStorePassword = "";
        private String trustStoreType = "JKS";
        private final List<String> pinnedServerCertificateSha256 = new ArrayList<>();
        private String fromAddress = "cachedb@localhost";
        private final List<String> toAddresses = new ArrayList<>();
        private String subjectPrefix = "[CacheDB Incident]";

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder smtpHost(String smtpHost) {
            this.smtpHost = smtpHost;
            return this;
        }

        public Builder smtpPort(int smtpPort) {
            this.smtpPort = smtpPort;
            return this;
        }

        public Builder implicitTls(boolean implicitTls) {
            this.implicitTls = implicitTls;
            return this;
        }

        public Builder startTls(boolean startTls) {
            this.startTls = startTls;
            return this;
        }

        public Builder trustAllCertificates(boolean trustAllCertificates) {
            this.trustAllCertificates = trustAllCertificates;
            return this;
        }

        public Builder connectTimeoutMillis(long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder readTimeoutMillis(long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryBackoffMillis(long retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
            return this;
        }

        public Builder heloHost(String heloHost) {
            this.heloHost = heloHost;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder authMechanism(String authMechanism) {
            this.authMechanism = authMechanism;
            return this;
        }

        public Builder trustStorePath(String trustStorePath) {
            this.trustStorePath = trustStorePath;
            return this;
        }

        public Builder trustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
            return this;
        }

        public Builder trustStoreType(String trustStoreType) {
            this.trustStoreType = trustStoreType;
            return this;
        }

        public Builder addPinnedServerCertificateSha256(String fingerprint) {
            if (fingerprint != null && !fingerprint.isBlank()) {
                this.pinnedServerCertificateSha256.add(fingerprint);
            }
            return this;
        }

        public Builder pinnedServerCertificateSha256(List<String> fingerprints) {
            this.pinnedServerCertificateSha256.clear();
            if (fingerprints != null) {
                this.pinnedServerCertificateSha256.addAll(
                        fingerprints.stream()
                                .filter(value -> value != null && !value.isBlank())
                                .toList()
                );
            }
            return this;
        }

        public Builder fromAddress(String fromAddress) {
            this.fromAddress = fromAddress;
            return this;
        }

        public Builder addToAddress(String toAddress) {
            if (toAddress != null && !toAddress.isBlank()) {
                this.toAddresses.add(toAddress);
            }
            return this;
        }

        public Builder toAddresses(List<String> toAddresses) {
            this.toAddresses.clear();
            if (toAddresses != null) {
                this.toAddresses.addAll(toAddresses.stream().filter(value -> value != null && !value.isBlank()).toList());
            }
            return this;
        }

        public Builder subjectPrefix(String subjectPrefix) {
            this.subjectPrefix = subjectPrefix;
            return this;
        }

        public IncidentEmailConfig build() {
            return new IncidentEmailConfig(
                    enabled,
                    smtpHost,
                    smtpPort,
                    implicitTls,
                    startTls,
                    trustAllCertificates,
                    connectTimeoutMillis,
                    readTimeoutMillis,
                    maxRetries,
                    retryBackoffMillis,
                    heloHost,
                    username,
                    password,
                    authMechanism,
                    trustStorePath,
                    trustStorePassword,
                    trustStoreType,
                    List.copyOf(pinnedServerCertificateSha256),
                    fromAddress,
                    List.copyOf(toAddresses),
                    subjectPrefix
            );
        }
    }
}
