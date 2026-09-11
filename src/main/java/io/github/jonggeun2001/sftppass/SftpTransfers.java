package io.github.jonggeun2001.sftppass;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import java.util.regex.Pattern;

/** Literal tree transfers; only multi-transfer source basenames are globbed. */
final class SftpTransfers {
    private final ChannelSftp sftp;

    SftpTransfers(ChannelSftp sftp) {
        this.sftp = sftp;
    }

    void upload(String source, String destination, boolean recursive, boolean multiple, Integer chmod)
            throws IOException, SftpException {
        List<Path> sources = localSources(source, multiple);
        String target = remotePath(destination);
        checkRemoteAncestors(target);
        SftpATTRS targetAttrs = remoteAttrs(target);
        boolean directory = targetAttrs != null && targetAttrs.isDir();
        if (multiple && !directory) {
            throw new IOException("Multi-upload target must be an existing directory: " + destination);
        }
        for (Path path : sources) {
            Path name = path.getFileName();
            if (directory && name == null) throw new IOException("Source must have a basename: " + path);
            uploadTree(path, directory ? join(target, name.toString()) : target, recursive, chmod);
        }
    }

    void download(String source, String destination, boolean recursive, boolean multiple)
            throws IOException, SftpException {
        List<String> sources = remoteSources(source, multiple);
        Path target = localPath(destination);
        checkLocalAncestors(target);
        boolean directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        if (multiple && !directory) {
            throw new IOException("Multi-download target must be an existing directory: " + destination);
        }
        for (String path : sources) {
            downloadTree(path, directory ? target.resolve(basename(path)) : target, recursive);
        }
    }

    private void uploadTree(Path source, String target, boolean recursive, Integer chmod)
            throws IOException, SftpException {
        checkLocalAncestors(source);
        checkRemoteAncestors(target);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            if (!recursive) throw new IOException("Directory requires -r: " + source);
            SftpATTRS attrs = remoteAttrs(target);
            if (attrs == null) sftp.mkdir(target); // mkdir takes a literal path, unlike lstat/put.
            else if (!attrs.isDir()) throw new IOException("Target is not a directory: " + target);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                for (Path child : children) {
                    uploadTree(child, join(target, child.getFileName().toString()), true, chmod);
                }
            }
        } else {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Not a regular file: " + source);
            }
            SftpATTRS attrs = remoteAttrs(target);
            if (attrs != null && !attrs.isReg()) throw new IOException("Target is not a regular file: " + target);
            try (InputStream input = Files.newInputStream(source)) {
                sftp.put(input, quote(target));
            }
            if (chmod != null) sftp.chmod(chmod, quote(target));
        }
    }

    private void downloadTree(String source, Path target, boolean recursive) throws IOException, SftpException {
        checkRemoteAncestors(source);
        SftpATTRS attrs = sftp.lstat(quote(source));
        checkLocalAncestors(target);
        if (attrs.isDir()) {
            if (!recursive) throw new IOException("Directory requires -r: " + source);
            Files.createDirectories(target);
            for (ChannelSftp.LsEntry entry : listing(source)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                validateName(name);
                downloadTree(join(source, name), target.resolve(name), true);
            }
        } else {
            if (!attrs.isReg()) throw new IOException("Not a regular file: " + source);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Target is not a regular file: " + target);
            }
            try (OutputStream output = Files.newOutputStream(target)) {
                sftp.get(quote(source), output);
            }
        }
    }

    private List<Path> localSources(String source, boolean multiple) throws IOException {
        Path path = localPath(source);
        if (!multiple || path.getFileName() == null || !hasPattern(path.getFileName().toString())) {
            return Collections.singletonList(path);
        }
        Path parent = path.getParent();
        if (hasPattern(parent.toString())) throw new IOException("Patterns are supported only in the final path component.");
        checkLocalAncestors(parent);
        Pattern pattern = pattern(path.getFileName().toString());
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
            for (Path child : children) {
                if (pattern.matcher(child.getFileName().toString()).matches()) matches.add(child);
            }
        }
        if (matches.isEmpty()) throw new IOException("No files match: " + source);
        Collections.sort(matches);
        return matches;
    }

    private List<String> remoteSources(String source, boolean multiple) throws IOException, SftpException {
        String path = remotePath(source);
        if (!multiple || !hasPattern(path)) return Collections.singletonList(path);
        int slash = path.lastIndexOf('/');
        String parent = slash == 0 ? "/" : path.substring(0, slash);
        if (hasPattern(parent)) throw new IOException("Patterns are supported only in the final path component.");
        checkRemoteAncestors(parent);
        Pattern pattern = pattern(path.substring(slash + 1));
        List<String> matches = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : listing(parent)) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            if (pattern.matcher(name).matches()) {
                validateName(name);
                matches.add(join(parent, name));
            }
        }
        if (matches.isEmpty()) throw new IOException("No files match: " + source);
        Collections.sort(matches);
        return matches;
    }

    private Path localPath(String path) throws IOException {
        Path parsed = Paths.get(path);
        Path absolute = (parsed.isAbsolute() ? parsed : Paths.get(sftp.lpwd()).resolve(parsed)).toAbsolutePath();
        // Check the supplied path before normalization can erase a link/.. component.
        checkLocalAncestors(absolute);
        return absolute.normalize();
    }

    private String remotePath(String path) throws SftpException, IOException {
        String absolute = path.startsWith("/") ? path : join(sftp.pwd(), path);
        checkRemoteAncestors(absolute);
        List<String> parts = new ArrayList<>();
        for (String part : absolute.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return "/" + String.join("/", parts);
    }

    private void checkLocalAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not supported: " + current);
        }
    }

    private void checkRemoteAncestors(String path) throws SftpException, IOException {
        String current = "/";
        for (String part : path.substring(1).split("/")) {
            if (part.isEmpty()) continue;
            current = join(current, part);
            SftpATTRS attrs = remoteAttrs(current);
            if (attrs != null && attrs.isLink()) throw new IOException("Symbolic links are not supported: " + current);
        }
    }

    private SftpATTRS remoteAttrs(String path) throws SftpException {
        try {
            return sftp.lstat(quote(path));
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Vector<ChannelSftp.LsEntry> listing(String path) throws SftpException {
        return sftp.ls(quote(path));
    }

    private static String join(String parent, String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    private static String basename(String path) throws IOException {
        String name = path.substring(path.lastIndexOf('/') + 1);
        validateName(name);
        return name;
    }

    private static void validateName(String name) throws IOException {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("Unsafe or unsupported remote entry name: " + name);
        }
    }

    private static boolean hasPattern(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }

    private static Pattern pattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static String quote(String path) {
        return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
