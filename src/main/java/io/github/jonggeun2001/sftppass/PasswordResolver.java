package io.github.jonggeun2001.sftppass;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class PasswordResolver {
    private PasswordResolver() {
    }

    static char[] resolve(SftpPassWrapper.ConnectionOptions options) throws IOException {
        int explicitSources = 0;
        if (options.passwordStdin) {
            explicitSources++;
        }
        if (options.passwordFile != null) {
            explicitSources++;
        }
        if (options.passwordEnvExplicit) {
            explicitSources++;
        }
        if (explicitSources > 1) {
            throw new IllegalArgumentException("Choose only one password source: --password-stdin, --password-file, or --password-env.");
        }

        if (options.passwordStdin) {
            return readFromStdin();
        }
        if (options.passwordFile != null) {
            return trimLine(new String(Files.readAllBytes(options.passwordFile), StandardCharsets.UTF_8)).toCharArray();
        }

        String envName = options.passwordEnvName();
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue.toCharArray();
        }

        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("No password provided. Set " + envName + ", use --password-stdin, or use --password-file.");
        }
        char[] password = console.readPassword("SFTP password: ");
        if (password == null || password.length == 0) {
            throw new IllegalStateException("Empty password is not allowed.");
        }
        return password;
    }

    static void clear(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] readFromStdin() throws IOException {
        byte[] data = readAllStdinBytes();
        String value = trimLine(new String(data, StandardCharsets.UTF_8));
        if (value.isEmpty()) {
            throw new IllegalStateException("Empty password from stdin is not allowed.");
        }
        return value.toCharArray();
    }

    private static byte[] readAllStdinBytes() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = System.in.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String trimLine(String value) {
        return value.replaceFirst("[\\r\\n]+$", "");
    }
}
