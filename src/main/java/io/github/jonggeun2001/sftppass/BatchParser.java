package io.github.jonggeun2001.sftppass;

import java.util.ArrayList;
import java.util.List;

final class BatchParser {
    private BatchParser() {
    }

    static ParsedLine parse(String rawLine) {
        String line = stripComment(rawLine).trim();
        if (line.isEmpty()) {
            return ParsedLine.blank();
        }

        boolean ignoreErrors = false;
        boolean silent = false;
        boolean scanning = true;
        while (scanning && !line.isEmpty()) {
            switch (line.charAt(0)) {
                case '-' -> {
                    ignoreErrors = true;
                    line = line.substring(1).trim();
                }
                case '@' -> {
                    silent = true;
                    line = line.substring(1).trim();
                }
                default -> scanning = false;
            }
        }

        if (line.isEmpty()) {
            return ParsedLine.blank();
        }
        return new ParsedLine(split(line), ignoreErrors, silent, false);
    }

    private static String stripComment(String line) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean escaped = false;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                out.append(c);
                continue;
            }
            if (c == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
                out.append(c);
                continue;
            }
            if (c == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
                out.append(c);
                continue;
            }
            if (c == '#' && !singleQuote && !doubleQuote) {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static List<String> split(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
                continue;
            }
            if (c == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
                continue;
            }
            if (Character.isWhitespace(c) && !singleQuote && !doubleQuote) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }

        if (escaped) {
            current.append('\\');
        }
        if (singleQuote || doubleQuote) {
            throw new IllegalArgumentException("Unclosed quote in batch line: " + line);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    record ParsedLine(List<String> tokens, boolean ignoreErrors, boolean silent, boolean empty) {
        static ParsedLine blank() {
            return new ParsedLine(List.of(), false, false, true);
        }
    }
}
