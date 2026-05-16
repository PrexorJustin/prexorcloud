package me.prexorjustin.prexorcloud.controller.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import me.prexorjustin.prexorcloud.security.tls.ReloadableServerSslContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the gRPC server keystore and CA PEM file for changes (mtime + size)
 * and triggers {@link ReloadableServerSslContext#reload(Path, char[], Path)}
 * when either has been replaced. Designed to run on the existing scheduled
 * executor — typical poll cadence is 10–30s.
 *
 * <p>Polling rather than {@code WatchService} is intentional: rotation is
 * coarse-grained (daily by default), and polling is portable across container
 * filesystems where inotify events are unreliable.
 */
public final class TlsMaterialWatcher implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(TlsMaterialWatcher.class);

    private final ReloadableServerSslContext sslContext;
    private final Path keystorePath;
    private final char[] keystorePassword;
    private final Path caPemPath;

    private volatile FileTime keystoreMtime;
    private volatile long keystoreSize;
    private volatile FileTime caPemMtime;
    private volatile long caPemSize;

    public TlsMaterialWatcher(
            ReloadableServerSslContext sslContext, Path keystorePath, char[] keystorePassword, Path caPemPath) {
        this.sslContext = sslContext;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.caPemPath = caPemPath;
        snapshot();
    }

    private void snapshot() {
        try {
            if (Files.exists(keystorePath)) {
                this.keystoreMtime = Files.getLastModifiedTime(keystorePath);
                this.keystoreSize = Files.size(keystorePath);
            }
            if (Files.exists(caPemPath)) {
                this.caPemMtime = Files.getLastModifiedTime(caPemPath);
                this.caPemSize = Files.size(caPemPath);
            }
        } catch (Exception e) {
            logger.warn("Failed to snapshot TLS material attributes: {}", e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            if (!Files.exists(keystorePath) || !Files.exists(caPemPath)) {
                return;
            }
            FileTime ksMtime = Files.getLastModifiedTime(keystorePath);
            long ksSize = Files.size(keystorePath);
            FileTime caMtime = Files.getLastModifiedTime(caPemPath);
            long caSize = Files.size(caPemPath);

            boolean ksChanged = !ksMtime.equals(keystoreMtime) || ksSize != keystoreSize;
            boolean caChanged = !caMtime.equals(caPemMtime) || caSize != caPemSize;

            if (!ksChanged && !caChanged) {
                return;
            }

            sslContext.reload(keystorePath, keystorePassword, caPemPath);
            this.keystoreMtime = ksMtime;
            this.keystoreSize = ksSize;
            this.caPemMtime = caMtime;
            this.caPemSize = caSize;
        } catch (Exception e) {
            logger.warn("TLS material reload failed: {}", e.getMessage());
        }
    }

    /**
     * Force an immediate reload — used right after {@link CertificateRotationTask}
     * writes a new keystore so the gRPC server picks it up without waiting for
     * the next watch tick.
     */
    public void forceReload() {
        run();
    }
}
