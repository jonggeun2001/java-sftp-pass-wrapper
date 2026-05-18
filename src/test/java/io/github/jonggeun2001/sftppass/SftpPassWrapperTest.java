package io.github.jonggeun2001.sftppass;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SftpPassWrapperTest {
    @Test
    void acceptsPortBeforeSubcommand() {
        CommandLine.ParseResult result = SftpPassWrapper.parseArgs(
            "--host", "sftp.example.com",
            "--user", "deploy",
            "--port", "2222",
            "ls",
            "/upload"
        );

        SftpPassWrapper.Ls command = result.subcommand().commandSpec().userObject();

        assertEquals("sftp.example.com", command.options.host);
        assertEquals("deploy", command.options.user);
        assertEquals(2222, command.options.port);
        assertEquals("/upload", command.remote);
    }
}
