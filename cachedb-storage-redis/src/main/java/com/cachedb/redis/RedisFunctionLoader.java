package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class RedisFunctionLoader {

    private static final long LOAD_LEASE_MILLIS = 30_000L;
    private static final String RELEASE_LEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final JedisPooled jedis;
    private final RedisFunctionsConfig config;
    private final RedisFunctionLibrarySource source;

    public RedisFunctionLoader(
            JedisPooled jedis,
            RedisFunctionsConfig config,
            RedisFunctionLibrarySource source
    ) {
        this.jedis = jedis;
        this.config = config;
        this.source = source;
    }

    public void initialize() {
        if (!config.enabled() || !config.autoLoadLibrary()) {
            return;
        }

        String rendered = source.render(config);
        String desiredMarker = normalizedVersion() + "|" + sha256(rendered);
        String markerKey = "cachedb:function-library:" + config.libraryName() + ":marker";
        String leaseKey = markerKey + ":lease";
        String currentMarker = jedis.get(markerKey);
        if (desiredMarker.equals(currentMarker)) {
            return;
        }
        rejectDowngradeOrDrift(currentMarker, desiredMarker);

        String leaseOwner = UUID.randomUUID().toString();
        String acquired = jedis.set(leaseKey, leaseOwner, SetParams.setParams().nx().px(LOAD_LEASE_MILLIS));
        if (acquired == null) {
            waitForConcurrentLoader(markerKey, desiredMarker);
            return;
        }
        try {
            currentMarker = jedis.get(markerKey);
            if (desiredMarker.equals(currentMarker)) {
                return;
            }
            rejectDowngradeOrDrift(currentMarker, desiredMarker);
            try {
                if (config.replaceLibraryOnLoad()) {
                    jedis.functionLoadReplace(rendered);
                } else {
                    jedis.functionLoad(rendered);
                }
                jedis.set(markerKey, desiredMarker);
            } catch (JedisDataException exception) {
                if (!config.strictLoading() && libraryExistsError(exception)) {
                    return;
                }
                throw exception;
            }
        } finally {
            jedis.eval(RELEASE_LEASE_SCRIPT, 1, leaseKey, leaseOwner);
        }
    }

    private void waitForConcurrentLoader(String markerKey, String desiredMarker) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String marker = jedis.get(markerKey);
            if (desiredMarker.equals(marker)) {
                return;
            }
            rejectDowngradeOrDrift(marker, desiredMarker);
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Redis Function deployment", exception);
            }
        }
        throw new IllegalStateException("Timed out waiting for Redis Function deployment lease: " + config.libraryName());
    }

    private void rejectDowngradeOrDrift(String currentMarker, String desiredMarker) {
        if (currentMarker == null || currentMarker.isBlank()) {
            return;
        }
        String[] current = currentMarker.split("\\|", 2);
        String[] desired = desiredMarker.split("\\|", 2);
        int versionOrder = compareVersions(current[0], desired[0]);
        if (versionOrder > 0) {
            throw new IllegalStateException(
                    "Refusing to downgrade Redis Function library " + config.libraryName()
                            + " from " + current[0] + " to " + desired[0]
            );
        }
        if (versionOrder == 0 && !currentMarker.equals(desiredMarker)) {
            throw new IllegalStateException(
                    "Redis Function library content drift detected for version " + desired[0]
                            + "; publish a new libraryVersion instead of replacing the same version"
            );
        }
    }

    private String normalizedVersion() {
        String value = config.libraryVersion() == null ? "" : config.libraryVersion().trim();
        if (!value.matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?")) {
            throw new IllegalArgumentException("Redis Function libraryVersion is invalid: " + config.libraryVersion());
        }
        return value;
    }

    private int compareVersions(String left, String right) {
        return SemanticVersion.parse(left).compareTo(SemanticVersion.parse(right));
    }

    private String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private boolean libraryExistsError(JedisDataException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("Library already exists");
    }

    private record SemanticVersion(int major, int minor, int patch, String qualifier)
            implements Comparable<SemanticVersion> {

        private static SemanticVersion parse(String value) {
            String[] releaseAndQualifier = value.split("-", 2);
            String[] numbers = releaseAndQualifier[0].split("\\.");
            if (numbers.length != 3) {
                throw new IllegalArgumentException("Redis Function libraryVersion must use major.minor.patch: " + value);
            }
            return new SemanticVersion(
                    Integer.parseInt(numbers[0]),
                    Integer.parseInt(numbers[1]),
                    Integer.parseInt(numbers[2]),
                    releaseAndQualifier.length == 2 ? releaseAndQualifier[1] : ""
            );
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int compared = Integer.compare(major, other.major);
            if (compared == 0) {
                compared = Integer.compare(minor, other.minor);
            }
            if (compared == 0) {
                compared = Integer.compare(patch, other.patch);
            }
            if (compared != 0) {
                return compared;
            }
            if (qualifier.isEmpty()) {
                return other.qualifier.isEmpty() ? 0 : 1;
            }
            if (other.qualifier.isEmpty()) {
                return -1;
            }
            return compareQualifier(qualifier, other.qualifier);
        }

        private static int compareQualifier(String left, String right) {
            String[] leftParts = left.split("\\.");
            String[] rightParts = right.split("\\.");
            int length = Math.max(leftParts.length, rightParts.length);
            for (int index = 0; index < length; index++) {
                if (index >= leftParts.length) {
                    return -1;
                }
                if (index >= rightParts.length) {
                    return 1;
                }
                String leftPart = leftParts[index];
                String rightPart = rightParts[index];
                boolean leftNumeric = leftPart.matches("\\d+");
                boolean rightNumeric = rightPart.matches("\\d+");
                int compared;
                if (leftNumeric && rightNumeric) {
                    compared = Integer.compare(Integer.parseInt(leftPart), Integer.parseInt(rightPart));
                } else if (leftNumeric != rightNumeric) {
                    compared = leftNumeric ? -1 : 1;
                } else {
                    compared = leftPart.compareTo(rightPart);
                }
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        }
    }
}
