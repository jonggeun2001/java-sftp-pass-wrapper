package io.github.jonggeun2001.sftppass;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        SftpPassWrapper.Ls command = (SftpPassWrapper.Ls) result.subcommand().commandSpec().userObject();

        assertEquals("sftp.example.com", command.options.host);
        assertEquals("deploy", command.options.user);
        assertEquals(2222, command.options.port);
        assertEquals("/upload", command.remote);
    }

    @Test
    void acceptsChmodAfterPutSubcommand() {
        CommandLine.ParseResult result = SftpPassWrapper.parseArgs(
            "--host", "sftp.example.com",
            "--user", "deploy",
            "put",
            "--chmod", "777",
            "./local.txt",
            "/upload/local.txt"
        );

        SftpPassWrapper.Put command = (SftpPassWrapper.Put) result.subcommand().commandSpec().userObject();

        assertEquals("777", command.chmod);
        assertEquals("./local.txt", command.local);
        assertEquals("/upload/local.txt", command.remote);
    }

    @Test
    void parsesChmodAsOctalMode() {
        assertEquals(0777, SftpPassWrapper.parseChmodMode("777"));
        assertEquals(0755, SftpPassWrapper.parseChmodMode("0755"));
        assertEquals(01777, SftpPassWrapper.parseChmodMode("1777"));
    }

    @Test
    void rejectsInvalidChmodMode() {
        assertThrows(IllegalArgumentException.class, () -> SftpPassWrapper.parseChmodMode("999"));
        assertThrows(IllegalArgumentException.class, () -> SftpPassWrapper.parseChmodMode("abc"));
    }

    @Test
    void resolvesChmodTargetInsideRemoteDirectory() {
        assertEquals(
            "/upload/script.sh",
            SftpPassWrapper.resolveChmodTargetPath("./script.sh", "/upload", true)
        );
        assertEquals(
            "/upload/script.sh",
            SftpPassWrapper.resolveChmodTargetPath("./script.sh", "/upload/", true)
        );
        assertEquals(
            "/script.sh",
            SftpPassWrapper.resolveChmodTargetPath("./script.sh", "/", true)
        );
    }

    @Test
    void keepsExplicitRemoteFileChmodTarget() {
        assertEquals(
            "/upload/renamed.sh",
            SftpPassWrapper.resolveChmodTargetPath("./script.sh", "/upload/renamed.sh", false)
        );
    }
}
