package io.github.jonggeun2001.sftppass;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.nio.file.Files;
import java.util.Properties;

final class SftpSession implements AutoCloseable {
    private final Session session;
    private final ChannelSftp sftp;

    private SftpSession(Session session, ChannelSftp sftp) {
        this.session = session;
        this.sftp = sftp;
    }

    static SftpSession connect(SftpPassWrapper.ConnectionOptions options, char[] password) throws JSchException {
        JSch jsch = new JSch();
        if (!options.insecure && options.knownHosts != null && Files.isRegularFile(options.knownHosts)) {
            jsch.setKnownHosts(options.knownHosts.toString());
        }

        Session session = jsch.getSession(options.user, options.host, options.port);
        session.setPassword(new String(password));

        Properties config = new Properties();
        config.setProperty("StrictHostKeyChecking", options.insecure ? "no" : "yes");
        session.setConfig(config);
        session.connect(options.timeoutMillis);

        Channel channel = session.openChannel("sftp");
        channel.connect(options.timeoutMillis);
        return new SftpSession(session, (ChannelSftp) channel);
    }

    ChannelSftp client() {
        return sftp;
    }

    @Override
    public void close() {
        if (sftp != null && sftp.isConnected()) {
            sftp.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}
