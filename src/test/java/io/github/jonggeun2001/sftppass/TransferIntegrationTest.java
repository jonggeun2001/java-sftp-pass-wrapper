package io.github.jonggeun2001.sftppass;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class TransferIntegrationTest {
    @TempDir Path temp;
    Path remote;
    Path local;
    SshServer server;
    Session session;
    ChannelSftp sftp;

    @BeforeEach
    void connect() throws Exception {
        remote = Files.createDirectory(temp.resolve("remote"));
        local = Files.createDirectory(temp.resolve("local"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temp.resolve("hostkey")));
        server.setPasswordAuthenticator((user, password, unused) -> true);
        server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory.Builder().build()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(remote));
        server.start();
        session = new JSch().getSession("test", "127.0.0.1", server.getPort());
        session.setPassword("test");
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(10000);
        sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(10000);
        sftp.lcd(local.toString());
    }

    @AfterEach
    void disconnect() throws Exception {
        if (sftp != null) sftp.disconnect();
        if (session != null) session.disconnect();
        if (server != null) server.stop(true);
    }

    private void command(String... args) throws Exception {
        String[] connection = {"--host", "unused", "--user", "unused"};
        String[] full = Arrays.copyOf(connection, connection.length + args.length);
        System.arraycopy(args, 0, full, connection.length, args.length);
        SftpPassWrapper.SftpAction action = (SftpPassWrapper.SftpAction)
            SftpPassWrapper.parseArgs(full).subcommand().commandSpec().userObject();
        action.execute(sftp);
    }

    private void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    void recursiveUploadAndDownloadPreserveTreeAndEmptyDirectories() throws Exception {
        write(local.resolve("tree/sub/file.txt"), "nested");
        write(local.resolve("tree/.hidden"), "hidden");
        Files.createDirectories(local.resolve("tree/empty"));
        command("put", "-r", "tree", "/renamed");
        assertEquals("nested", read(remote.resolve("renamed/sub/file.txt")));
        assertTrue(Files.isDirectory(remote.resolve("renamed/empty")));
        command("get", "--recursive", "/renamed", "copy");
        assertEquals("hidden", read(local.resolve("copy/.hidden")));
        assertEquals("nested", read(local.resolve("copy/sub/file.txt")));
        assertTrue(Files.isDirectory(local.resolve("copy/empty")));
    }

    @Test
    void multipleTransfersExpandOnlyMatchingFiles() throws Exception {
        write(local.resolve("one.csv"), "one");
        write(local.resolve("two.csv"), "two");
        write(local.resolve("skip.txt"), "skip");
        Files.createDirectory(remote.resolve("upload"));
        command("mput", "*.csv", "/upload");
        assertEquals("one", read(remote.resolve("upload/one.csv")));
        assertFalse(Files.exists(remote.resolve("upload/skip.txt")));
        Files.createDirectory(local.resolve("downloads"));
        command("mget", "/upload/*.csv", "downloads");
        assertEquals("two", read(local.resolve("downloads/two.csv")));
    }

    @Test
    void recursiveMultiTransferUsesSourceBasenamesAndChmodsFiles() throws Exception {
        write(local.resolve("treeA/deep/a.txt"), "A");
        Files.createDirectories(local.resolve("treeB/empty"));
        command("mput", "-r", "--chmod", "640", "tree*");
        assertEquals("A", read(remote.resolve("treeA/deep/a.txt")));
        assertEquals(0640, sftp.stat("/treeA/deep/a.txt").getPermissions() & 0777);
        assertTrue(sftp.stat("/treeA/deep").isDir());
        Files.createDirectory(local.resolve("copies"));
        command("mget", "-r", "/tree*", "copies");
        assertTrue(Files.isDirectory(local.resolve("copies/treeB/empty")));
    }

    @Test
    void missingPatternsAndNonDirectoryMultiTargetsFail() throws Exception {
        write(local.resolve("file.txt"), "data");
        assertThrows(IOException.class, () -> command("mput", "missing*"));
        assertThrows(IOException.class, () -> command("mget", "/missing*"));
        assertThrows(IOException.class, () -> command("mput", "*.txt", "/missing"));
        assertFalse(Files.exists(remote.resolve("missing")));
    }

    @Test
    void directoriesRequireRecursiveFlag() throws Exception {
        Files.createDirectory(local.resolve("tree"));
        Files.createDirectory(remote.resolve("tree"));
        assertThrows(IOException.class, () -> command("mput", "tree"));
        assertThrows(IOException.class, () -> command("mget", "/tree"));
    }

    @Test
    void batchTransfersRespectCdLcdAndQuotedPaths() throws Exception {
        write(local.resolve("source dir/sub/file.txt"), "batch");
        Files.createDirectory(remote.resolve("upload"));
        Files.createDirectory(local.resolve("downloads"));
        Path batch = temp.resolve("batch.sftp");
        write(batch, "cd /upload\nmput -r \"source dir\"\nlcd downloads\nmget -r *\nbye\n");
        command("batch", batch.toString());
        assertEquals("batch", read(local.resolve("downloads/source dir/sub/file.txt")));
    }

    @Test
    void recursiveTransferRejectsSymlinksInsteadOfFollowingThem() throws Exception {
        write(local.resolve("outside.txt"), "private");
        Files.createDirectories(local.resolve("tree"));
        Files.createSymbolicLink(local.resolve("tree/link"), local.resolve("outside.txt"));
        assertThrows(IOException.class, () -> command("put", "-r", "tree", "/tree"));
        write(remote.resolve("outside.txt"), "private");
        Files.createDirectories(remote.resolve("links"));
        Files.createSymbolicLink(remote.resolve("links/link"), remote.resolve("outside.txt"));
        assertThrows(IOException.class, () -> command("get", "-r", "/links", "links"));
        assertFalse(Files.exists(local.resolve("links/link")));
    }

    @Test
    void downloadDoesNotOverwriteThroughLocalDestinationSymlink() throws Exception {
        write(remote.resolve("tree/file.txt"), "new");
        write(local.resolve("outside.txt"), "original");
        Files.createDirectories(local.resolve("target/tree"));
        Files.createSymbolicLink(local.resolve("target/tree/file.txt"), local.resolve("outside.txt"));
        assertThrows(IOException.class, () -> command("get", "-r", "/tree", "target"));
        assertEquals("original", read(local.resolve("outside.txt")));
    }

    @Test
    void recursiveTransfersKeepWildcardCharactersInChildNamesLiteral() throws Exception {
        write(local.resolve("tree/a*.txt"), "literal");
        write(local.resolve("tree/abc.txt"), "other");
        command("put", "-r", "tree", "/tree");
        assertEquals("literal", read(remote.resolve("tree/a*.txt")));
        command("get", "-r", "/tree", "copy");
        assertEquals("literal", read(local.resolve("copy/a*.txt")));
        assertEquals("other", read(local.resolve("copy/abc.txt")));
    }
    @Test
    void singleTransfersAppendBasenameToExistingDirectoryAndOverwriteFiles() throws Exception {
        write(local.resolve("tree/file.txt"), "first");
        Files.createDirectory(remote.resolve("upload"));
        command("put", "-r", "tree", "/upload");
        write(local.resolve("tree/file.txt"), "updated");
        command("put", "-r", "tree", "/upload");
        Files.createDirectory(local.resolve("downloads"));
        command("get", "-r", "/upload/tree", "downloads");
        assertEquals("updated", read(local.resolve("downloads/tree/file.txt")));
    }

    @Test
    void recursiveBatchGetAndPutAndIgnoredErrorsWork() throws Exception {
        write(local.resolve("source/sub/file.txt"), "batch");
        Path batch = temp.resolve("single.sftp");
        write(batch, "put -r --chmod 600 source /renamed\n-mget /missing*\nget -R /renamed copy\n");
        command("batch", batch.toString());
        assertEquals("batch", read(local.resolve("copy/sub/file.txt")));
        assertEquals(0600, sftp.stat("/renamed/sub/file.txt").getPermissions() & 0777);
    }

    @Test
    void nonIgnoredBatchTransferFailureStopsFollowingCommands() throws Exception {
        write(local.resolve("file.txt"), "unused");
        Path batch = temp.resolve("fail.sftp");
        write(batch, "mput missing*\nput file.txt /should-not-exist\n");
        assertThrows(IllegalStateException.class, () -> command("batch", batch.toString()));
        assertFalse(Files.exists(remote.resolve("should-not-exist")));
    }

    @Test
    void uploadRejectsRemoteDestinationSymlinks() throws Exception {
        write(local.resolve("tree/file.txt"), "new");
        write(remote.resolve("outside.txt"), "original");
        Files.createDirectories(remote.resolve("target/tree"));
        Files.createSymbolicLink(remote.resolve("target/tree/file.txt"), remote.resolve("outside.txt"));
        assertThrows(IOException.class, () -> command("put", "-r", "tree", "/target"));
        assertEquals("original", read(remote.resolve("outside.txt")));
    }

    @Test
    void questionMarkPatternAndDefaultDownloadDirectoryWork() throws Exception {
        write(remote.resolve("a1.txt"), "one");
        write(remote.resolve("a22.txt"), "two");
        command("mget", "/a?.txt");
        assertEquals("one", read(local.resolve("a1.txt")));
        assertFalse(Files.exists(local.resolve("a22.txt")));
    }

    @Test
    void ordinaryFileTransfersAndChmodRemainCompatible() throws Exception {
        write(local.resolve("file.txt"), "original");
        command("put", "--chmod", "600", "file.txt", "/remote.txt");
        command("get", "/remote.txt", "copy.txt");
        assertEquals("original", read(local.resolve("copy.txt")));
        assertEquals(0600, sftp.stat("/remote.txt").getPermissions() & 0777);
    }

    @Test
    void localParentTraversalCannotHideASymlinkBeforeNormalization() throws Exception {
        write(remote.resolve("file.txt"), "new");
        write(local.resolve("result.txt"), "original");
        Files.createDirectory(local.resolve("other"));
        Files.createSymbolicLink(local.resolve("link"), local.resolve("other"));
        assertThrows(IOException.class, () -> command("get", "-r", "/file.txt", "link/../result.txt"));
        assertEquals("original", read(local.resolve("result.txt")));
        assertThrows(IOException.class, () -> command("put", "-r", "link/../result.txt", "/uploaded.txt"));
        assertFalse(Files.exists(remote.resolve("uploaded.txt")));
    }

    @Test
    void remoteParentTraversalCannotHideASymlinkBeforeNormalization() throws Exception {
        write(local.resolve("file.txt"), "new");
        write(remote.resolve("result.txt"), "original");
        Files.createDirectory(remote.resolve("other"));
        Files.createSymbolicLink(remote.resolve("link"), remote.resolve("other"));
        assertThrows(IOException.class, () -> command("put", "-r", "file.txt", "/link/../result.txt"));
        assertEquals("original", read(remote.resolve("result.txt")));
        assertThrows(IOException.class, () -> command("get", "-r", "/link/../result.txt", "downloaded.txt"));
        assertFalse(Files.exists(local.resolve("downloaded.txt")));
    }

    @Test
    void unmatchedNonportableRemoteNamesDoNotBreakPatternDownloads() throws Exception {
        write(remote.resolve("report.csv"), "report");
        write(remote.resolve("backup:old.txt"), "unselected");
        command("mget", "/*.csv");
        assertEquals("report", read(local.resolve("report.csv")));
        assertThrows(IOException.class, () -> command("mget", "/backup*"));
    }

}
