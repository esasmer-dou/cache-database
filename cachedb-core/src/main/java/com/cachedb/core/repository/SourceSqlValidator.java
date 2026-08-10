package com.reactor.cachedb.core.repository;

import java.util.Locale;
import java.util.Set;

/** Conservative lexer for the explicit source-SQL escape hatch. */
public final class SourceSqlValidator {
    private static final Set<String> MUTATING_TOKENS = Set.of(
            "ALTER", "CALL", "CREATE", "DELETE", "DROP", "EXEC", "EXECUTE", "GRANT",
            "INSERT", "INTO", "MERGE", "REPLACE", "REVOKE", "TRUNCATE", "UPDATE", "UPSERT"
    );

    private SourceSqlValidator() {
    }

    public static String requireReadOnly(String sql) {
        String normalized = sql == null ? "" : sql.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Source SQL must not be blank");
        }
        Scan scan = scan(normalized);
        if (!(scan.firstToken().equals("SELECT") || scan.firstToken().equals("WITH"))) {
            throw new IllegalArgumentException("Source SQL must start with SELECT or WITH");
        }
        if (scan.hasStatementSeparator() || scan.hasComment()) {
            throw new IllegalArgumentException("Source SQL must be one statement without comments");
        }
        if (scan.hasMutatingToken() || scan.hasSelectInto() || scan.hasForUpdate()) {
            throw new IllegalArgumentException("Source SQL must be read-only");
        }
        return normalized;
    }

    public static int placeholderCount(String sql) {
        return scan(sql == null ? "" : sql).placeholderCount();
    }

    private static Scan scan(String sql) {
        StringBuilder token = new StringBuilder(24);
        String previous = "";
        String first = "";
        boolean mutating = false;
        boolean selectInto = false;
        boolean forUpdate = false;
        boolean separator = false;
        boolean comment = false;
        int placeholders = 0;
        State state = State.PLAIN;

        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            switch (state) {
                case SINGLE_QUOTE -> {
                    if (character == '\'' && next == '\'') {
                        index++;
                    } else if (character == '\'') {
                        state = State.PLAIN;
                    }
                    continue;
                }
                case DOUBLE_QUOTE -> {
                    if (character == '"' && next == '"') {
                        index++;
                    } else if (character == '"') {
                        state = State.PLAIN;
                    }
                    continue;
                }
                case BRACKET -> {
                    if (character == ']' && next == ']') {
                        index++;
                    } else if (character == ']') {
                        state = State.PLAIN;
                    }
                    continue;
                }
                case BACKTICK -> {
                    if (character == '`' && next == '`') {
                        index++;
                    } else if (character == '`') {
                        state = State.PLAIN;
                    }
                    continue;
                }
                case PLAIN -> {
                    // Continue below.
                }
            }

            if (character == '\'' || character == '"' || character == '[' || character == '`') {
                TokenResult result = acceptToken(token, previous, first, mutating, selectInto, forUpdate);
                previous = result.previous();
                first = result.first();
                mutating = result.mutating();
                selectInto = result.selectInto();
                forUpdate = result.forUpdate();
                state = character == '\'' ? State.SINGLE_QUOTE
                        : character == '"' ? State.DOUBLE_QUOTE
                        : character == '[' ? State.BRACKET : State.BACKTICK;
                continue;
            }
            if (character == '-' && next == '-' || character == '/' && next == '*') {
                comment = true;
                break;
            }
            if (character == ';') {
                separator = true;
            }
            if (character == '?') {
                placeholders++;
            }
            if (Character.isLetterOrDigit(character) || character == '_') {
                token.append(Character.toUpperCase(character));
            } else {
                TokenResult result = acceptToken(token, previous, first, mutating, selectInto, forUpdate);
                previous = result.previous();
                first = result.first();
                mutating = result.mutating();
                selectInto = result.selectInto();
                forUpdate = result.forUpdate();
            }
        }
        TokenResult result = acceptToken(token, previous, first, mutating, selectInto, forUpdate);
        return new Scan(result.first(), result.mutating(), result.selectInto(), result.forUpdate(),
                separator, comment, placeholders);
    }

    private static TokenResult acceptToken(
            StringBuilder token,
            String previous,
            String first,
            boolean mutating,
            boolean selectInto,
            boolean forUpdate
    ) {
        if (token.isEmpty()) {
            return new TokenResult(previous, first, mutating, selectInto, forUpdate);
        }
        String current = token.toString().toUpperCase(Locale.ROOT);
        token.setLength(0);
        String resolvedFirst = first.isEmpty() ? current : first;
        return new TokenResult(
                current,
                resolvedFirst,
                mutating || MUTATING_TOKENS.contains(current),
                selectInto || previous.equals("SELECT") && current.equals("INTO"),
                forUpdate || previous.equals("FOR") && current.equals("UPDATE")
        );
    }

    private enum State {
        PLAIN,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BRACKET,
        BACKTICK
    }

    private record TokenResult(
            String previous,
            String first,
            boolean mutating,
            boolean selectInto,
            boolean forUpdate
    ) {
    }

    private record Scan(
            String firstToken,
            boolean hasMutatingToken,
            boolean hasSelectInto,
            boolean hasForUpdate,
            boolean hasStatementSeparator,
            boolean hasComment,
            int placeholderCount
    ) {
    }
}
