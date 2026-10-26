package space.br1440.platform.tracing.gradle.javaagent.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.GradleException;

final class DistributionFiles {

    private DistributionFiles() {
    }

    static String sha256Hex(Path file) {
        MessageDigest digest = newSha256Digest();

        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        } catch (IOException exception) {
            String message = "Cannot compute SHA-256 of " + file;
            throw new GradleException(message, exception);
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    static Properties readJarProperties(Path jar, String entryName) {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                String message = entryName + " is missing from " + jar;
                throw new GradleException(message);
            }

            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                return properties;
            }
        } catch (IOException exception) {
            throw new GradleException("Cannot read " + entryName + " from " + jar, exception);
        }
    }

    static void writeUtf8(Path file, String content, String description) {
        Path destination = file.toAbsolutePath().normalize();
        try {
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            String message = "Cannot write " + description + " " + destination;
            throw new GradleException(message, exception);
        }
    }

    static void publishAtomically(Path stagedFile, Path publishedFile) {
        Path destination = publishedFile.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        Path temporary = null;

        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "." + destination.getFileName() + ".", ".tmp");

            Files.copy(stagedFile, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            String message = "File system does not support atomic move for " + destination;
            throw withCleanup(temporary, new GradleException(message, exception));
        } catch (IOException exception) {
            String message = "Cannot atomically replace " + destination;
            throw withCleanup(temporary, new GradleException(message, exception));
        }
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            String message = "SHA-256 is mandatory for every Java SE platform";
            throw new IllegalStateException(message, exception);
        }
    }

    private static GradleException withCleanup(Path temporary, GradleException failure) {
        if (temporary != null) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        return failure;
    }
}
