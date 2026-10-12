package io.github.jonggeun2001.sftppass;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledOnOs({OS.LINUX, OS.MAC})
@Timeout(20)
class ExecIntegrationTest {
    @TempDir Path temp;
    SshServer server;
    Path passwordFile;
    ByteArrayOutputStream stdout;
    ByteArrayOutputStream stderr;
    PrintStream originalOut;
    PrintStream originalErr;

    @BeforeEach
    void startServer() throws Exception {
        passwordFile = temp.resolve("password");
        Files.write(passwordFile, "test\n".getBytes(StandardCharsets.UTF_8));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temp.resolve("hostkey")));
        server.setPasswordAuthenticator((user, password, unused) -> "test".equals(password));
        // An exec-only server proves that the client does not require an SFTP subsystem.
        server.setCommandFactory((channel, command) ->
            new ProcessShellFactory(command, "/bin/sh", "-c", command).createShell(channel));
        server.start();
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(stdout, true, "UTF-8"));
        System.setErr(new PrintStream(stderr, true, "UTF-8"));
    }

    @AfterEach
    void stopServer() throws Exception {
        if (originalOut != null) System.setOut(originalOut);
        if (originalErr != null) System.setErr(originalErr);
        if (server != null) server.stop(true);
    }

    private int exec(String... command) {
        List<String> args = new ArrayList<>(Arrays.asList(
            "--host", "127.0.0.1", "--user", "test", "--port", Integer.toString(server.getPort()),
            "--password-file", passwordFile.toString(), "--insecure", "--timeout", "10000", "exec"));
        args.addAll(Arrays.asList(command));
        return SftpPassWrapper.execute(args.toArray(new String[0]));
    }

    @Test
    void streamsOutputAndReturnsExitStatusWhilePreservingArguments() {
        String[] arguments = {"two words", "", "a'b", "\"quoted\"", "$HOME", "$(printf injected)",
            "; printf injected", "*", "line\nbreak", "--host", "--help", "--", "@literal", "한글"};
        List<String> command = new ArrayList<>(Arrays.asList(
            "sh", "-c", "printf '<%s>\\n' \"$@\"; printf 'remote stderr\\n' >&2; exit 7", "_"));
        command.addAll(Arrays.asList(arguments));

        assertEquals(7, exec(command.toArray(new String[0])));
        StringBuilder expected = new StringBuilder();
        for (String argument : arguments) expected.append('<').append(argument).append(">\n");
        assertEquals(expected.toString(), new String(stdout.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("remote stderr\n", new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        System.out.print("stdout still open");
        System.err.print("stderr still open");
        assertFalse(System.out.checkError());
        assertFalse(System.err.checkError());
    }

    @Test
    void closesRemoteStdinAndExecutesACommandWithoutArguments() {
        assertEquals(0, exec("cat"));
        assertEquals(0, stdout.size());
        assertEquals(0, stderr.size());
    }

    @Test
    void reportsMissingExecutableAsRemoteFailure() {
        assertEquals(127, exec("/no-such-remote-executable"));
        assertEquals(0, stdout.size());
        assertFalse(stderr.size() == 0);
    }
}
