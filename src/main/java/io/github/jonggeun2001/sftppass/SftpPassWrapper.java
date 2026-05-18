package io.github.jonggeun2001.sftppass;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import java.util.concurrent.Callable;

@Command(
    name = "sftp-pass",
    description = "Password-based SFTP helper for environments without sshpass.",
    mixinStandardHelpOptions = true,
    version = "sftp-pass 0.1.0",
    subcommands = {
        SftpPassWrapper.Put.class,
        SftpPassWrapper.Get.class,
        SftpPassWrapper.Ls.class,
        SftpPassWrapper.Rm.class,
        SftpPassWrapper.Mkdir.class,
        SftpPassWrapper.Rmdir.class,
        SftpPassWrapper.Rename.class,
        SftpPassWrapper.Batch.class
    }
)
public class SftpPassWrapper implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new SftpPassWrapper()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.err);
    }

    static class ConnectionOptions {
        @Option(names = {"-H", "--host"}, required = true, description = "SFTP server hostname or IP.")
        String host;

        @Option(names = {"-P", "--port"}, defaultValue = "22", description = "SFTP server port. Default: ${DEFAULT-VALUE}.")
        int port;

        @Option(names = {"-u", "--user"}, required = true, description = "SFTP username.")
        String user;

        @Option(names = "--password-env", description = "Environment variable containing the password. Default lookup: SFTP_PASSWORD.")
        void setPasswordEnv(String value) {
            this.passwordEnv = value;
            this.passwordEnvExplicit = true;
        }

        String passwordEnv = "SFTP_PASSWORD";
        boolean passwordEnvExplicit;

        @Option(names = "--password-file", description = "Read password from a UTF-8 file. Trailing newline is ignored.")
        Path passwordFile;

        @Option(names = "--password-stdin", description = "Read password from stdin. Trailing newline is ignored.")
        boolean passwordStdin;

        @Option(names = "--known-hosts", description = "Known hosts file. Default: ${DEFAULT-VALUE}.")
        Path knownHosts = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts");

        @Option(names = "--insecure", description = "Disable host key checking. Use only for controlled internal testing.")
        boolean insecure;

        @Option(names = "--timeout", defaultValue = "30000", description = "Connection timeout in milliseconds. Default: ${DEFAULT-VALUE}.")
        int timeoutMillis;

        String passwordEnvName() {
            return passwordEnv == null || passwordEnv.trim().isEmpty() ? "SFTP_PASSWORD" : passwordEnv;
        }
    }

    abstract static class SftpAction implements Callable<Integer> {
        @Mixin
        ConnectionOptions options;

        @Override
        public Integer call() throws Exception {
            char[] password = PasswordResolver.resolve(options);
            try {
                try (SftpSession session = SftpSession.connect(options, password)) {
                    execute(session.client());
                    return 0;
                }
            } finally {
                PasswordResolver.clear(password);
            }
        }

        abstract void execute(ChannelSftp sftp) throws Exception;
    }

    @Command(name = "put", description = "Upload a local file to a remote path.", mixinStandardHelpOptions = true)
    static class Put extends SftpAction {
        @Parameters(index = "0", description = "Local file path.")
        String local;

        @Parameters(index = "1", description = "Remote file path.")
        String remote;

        @Override
        void execute(ChannelSftp sftp) throws SftpException {
            sftp.put(local, remote);
        }
    }

    @Command(name = "get", description = "Download a remote file to a local path.", mixinStandardHelpOptions = true)
    static class Get extends SftpAction {
        @Parameters(index = "0", description = "Remote file path.")
        String remote;

        @Parameters(index = "1", description = "Local file path.")
        String local;

        @Override
        void execute(ChannelSftp sftp) throws SftpException {
            sftp.get(remote, local);
        }
    }

    @Command(name = "ls", description = "List a remote directory.", mixinStandardHelpOptions = true)
    static class Ls extends SftpAction {
        @Parameters(index = "0", arity = "0..1", defaultValue = ".", description = "Remote path. Default: current directory.")
        String remote;

        @Override
        void execute(ChannelSftp sftp) throws SftpException {
            printListing(sftp, remote);
        }
    }

    @Command(name = "rm", description = "Remove a remote file.", mixinStandardHelpOptions = true)
    static class Rm extends SftpAction {
        @Parameters(index = "0", description = "Remote file path.")
        String remote;

        @Override
        void execute(ChannelSftp sftp) throws SftpException {
            sftp.rm(remote);
        }
    }

    @Command(name = "mkdir", description = "Create a remote directory.", mixinStandardHelpOptions = true)
    static class Mkdir extends SftpAction {
        @Parameters(index = "0", description = "Remote directory path.")
        String remote;

        @Override
        void execute(ChannelSftp sftp) throws SftpException {
            sftp.mkdir(remote);
        }
    }

    @Command(name = "rmdir", description = "Remove a remote directory.", mixinStandardHelpOptions = true)
    static class Rmdir extends SftpAction {
        @Parameters(index = "0", description = "Remote directory path.")
        String remote;

        @Override
        void execute(ChannelSftp sftp) throws SftpException {
            sftp.rmdir(remote);
        }
    }

    @Command(name = "rename", aliases = "mv", description = "Rename or move a remote path.", mixinStandardHelpOptions = true)
    static class Rename extends SftpAction {
        @Parameters(index = "0", description = "Source remote path.")
        String source;

        @Parameters(index = "1", description = "Target remote path.")
        String target;

        @Override
        void execute(ChannelSftp sftp) throws SftpException {
            sftp.rename(source, target);
        }
    }

    @Command(name = "batch", description = "Execute a subset of OpenSSH sftp batch commands.", mixinStandardHelpOptions = true)
    static class Batch extends SftpAction {
        @Parameters(index = "0", description = "Batch file path.")
        Path batchFile;

        @Override
        void execute(ChannelSftp sftp) throws Exception {
            List<String> lines = java.nio.file.Files.readAllLines(batchFile);
            for (int i = 0; i < lines.size(); i++) {
                BatchParser.ParsedLine parsed = BatchParser.parse(lines.get(i));
                if (parsed.empty()) {
                    continue;
                }
                if (!parsed.silent()) {
                    System.err.println("sftp-pass> " + String.join(" ", parsed.tokens()));
                }
                try {
                    runBatchCommand(sftp, parsed.tokens());
                } catch (BatchExit ignored) {
                    break;
                } catch (Exception e) {
                    if (parsed.ignoreErrors()) {
                        System.err.printf("Ignoring error at %s:%d: %s%n", batchFile, i + 1, e.getMessage());
                    } else {
                        throw new IllegalStateException("Batch failed at " + batchFile + ":" + (i + 1) + " -> " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    private static void runBatchCommand(ChannelSftp sftp, List<String> args) throws Exception {
        String command = args.get(0).toLowerCase(Locale.ROOT);
        switch (command) {
            case "put":
                requireArgs(args, 2, 3, "put <local> [remote]");
                break;
            case "get":
                requireArgs(args, 2, 3, "get <remote> [local]");
                break;
            case "ls":
                requireArgs(args, 1, 2, "ls [remote]");
                break;
            case "rm":
                requireArgs(args, 2, 2, "rm <remote>");
                break;
            case "mkdir":
                requireArgs(args, 2, 2, "mkdir <remote>");
                break;
            case "rmdir":
                requireArgs(args, 2, 2, "rmdir <remote>");
                break;
            case "rename":
            case "mv":
                requireArgs(args, 3, 3, "rename <old> <new>");
                break;
            case "cd":
                requireArgs(args, 2, 2, "cd <remote-dir>");
                break;
            case "lcd":
                requireArgs(args, 2, 2, "lcd <local-dir>");
                break;
            case "pwd":
            case "lpwd":
            case "bye":
            case "quit":
            case "exit":
                requireArgs(args, 1, 1, command);
                break;
            default:
                throw new IllegalArgumentException("Unsupported batch command: " + command);
        }

        switch (command) {
            case "put":
                sftp.put(args.get(1), args.size() == 3 ? args.get(2) : ".");
                break;
            case "get":
                sftp.get(args.get(1), args.size() == 3 ? args.get(2) : ".");
                break;
            case "ls":
                printListing(sftp, args.size() == 2 ? args.get(1) : ".");
                break;
            case "rm":
                sftp.rm(args.get(1));
                break;
            case "mkdir":
                sftp.mkdir(args.get(1));
                break;
            case "rmdir":
                sftp.rmdir(args.get(1));
                break;
            case "rename":
            case "mv":
                sftp.rename(args.get(1), args.get(2));
                break;
            case "cd":
                sftp.cd(args.get(1));
                break;
            case "lcd":
                sftp.lcd(args.get(1));
                break;
            case "pwd":
                System.out.println(sftp.pwd());
                break;
            case "lpwd":
                System.out.println(sftp.lpwd());
                break;
            case "bye":
            case "quit":
            case "exit":
                // Stop processing by throwing a private signal.
                throw new BatchExit();
            default:
                throw new IllegalArgumentException("Unsupported batch command: " + command);
        }
    }

    private static void requireArgs(List<String> args, int min, int max, String usage) {
        if (args.size() < min || args.size() > max) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }

    @SuppressWarnings("unchecked")
    private static void printListing(ChannelSftp sftp, String path) throws SftpException {
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(path);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
        for (ChannelSftp.LsEntry entry : entries) {
            SftpATTRS attrs = entry.getAttrs();
            String type = attrs.isDir() ? "d" : attrs.isLink() ? "l" : "-";
            String mtime = formatter.format(Instant.ofEpochSecond(attrs.getMTime()));
            System.out.printf("%s %12d %s %s%n", type, attrs.getSize(), mtime, entry.getFilename());
        }
    }

    private static final class BatchExit extends RuntimeException {
    }
}
