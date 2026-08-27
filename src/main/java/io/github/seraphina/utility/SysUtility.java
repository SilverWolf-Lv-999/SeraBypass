package io.github.seraphina.utility;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

public final class SysUtility {
    private static final Object NATIVE_LOAD_LOCK = new Object();
    private static final Set<String> LOADED_NATIVE_RESOURCE_PATHS = new HashSet<>();

    private static volatile Throwable lastNativeLoadFailure;

    public static boolean loadNative(String nativeResourcePath) {
        return loadNative(SysUtility.class, nativeResourcePath);
    }

    public static boolean loadNative(Class<?> resourceOwner, String nativeResourcePath) {
        try {
            loadNativeOrThrow(resourceOwner, nativeResourcePath);
            return true;
        } catch (Throwable throwable) {
            lastNativeLoadFailure = throwable;
            return false;
        }
    }

    public static void loadNativeOrThrow(String nativeResourcePath) throws IOException {
        loadNativeOrThrow(SysUtility.class, nativeResourcePath);
    }

    public static void loadNativeOrThrow(Class<?> resourceOwner, String nativeResourcePath) throws IOException {
        if (resourceOwner == null) {
            throw new IllegalArgumentException("resourceOwner must not be null");
        }

        String normalizedResourcePath = normalizeResourcePath(nativeResourcePath);
        synchronized (NATIVE_LOAD_LOCK) {
            if (LOADED_NATIVE_RESOURCE_PATHS.contains(normalizedResourcePath)) {
                return;
            }

            byte[] nativeBytes = readNativeResource(resourceOwner, normalizedResourcePath);
            Path extractedNativePath = extractNativeResource(normalizedResourcePath, nativeBytes);
            System.load(extractedNativePath.toAbsolutePath().toString());

            LOADED_NATIVE_RESOURCE_PATHS.add(normalizedResourcePath);
            lastNativeLoadFailure = null;
        }
    }

    public static boolean loadNativeFile(Path nativePath) {
        try {
            if (nativePath == null) {
                throw new IllegalArgumentException("nativePath must not be null");
            }
            System.load(nativePath.toAbsolutePath().normalize().toString());
            lastNativeLoadFailure = null;
            return true;
        } catch (Throwable throwable) {
            lastNativeLoadFailure = throwable;
            return false;
        }
    }

    public static Throwable getLastNativeLoadFailure() {
        return lastNativeLoadFailure;
    }


    private static String normalizeResourcePath(String nativeResourcePath) {
        if (nativeResourcePath == null || nativeResourcePath.isBlank()) {
            return null;
        }

        String normalizedResourcePath = nativeResourcePath.replace('\\', '/').strip();
        while (normalizedResourcePath.startsWith("/")) {
            normalizedResourcePath = normalizedResourcePath.substring(1);
        }
        if (normalizedResourcePath.isEmpty()) {
            return null;
        }
        return normalizedResourcePath;
    }

    private static byte[] readNativeResource(Class<?> resourceOwner, String nativeResourcePath) throws IOException {
        try (InputStream inputStream = resourceOwner.getResourceAsStream('/' + nativeResourcePath)) {
            if (inputStream == null) {
                throw new IOException("Native resource not found: " + nativeResourcePath);
            }
            return inputStream.readAllBytes();
        }
    }

    private static Path extractNativeResource(String nativeResourcePath, byte[] nativeBytes) throws IOException {
        String resourceFileName = nativeResourcePath.substring(nativeResourcePath.lastIndexOf('/') + 1);
        String extractedFileName = withContentHash(resourceFileName, sha256(nativeBytes));
        Path nativeCacheDirectory = Path.of(System.getProperty("java.io.tmpdir"), "sera-bypass/native");
        Path extractedNativePath = nativeCacheDirectory.resolve(extractedFileName);

        Files.createDirectories(nativeCacheDirectory);
        if (Files.isRegularFile(extractedNativePath) && Files.size(extractedNativePath) == nativeBytes.length) {
            return extractedNativePath;
        }

        Path temporaryNativePath = Files.createTempFile(nativeCacheDirectory, extractedFileName, ".tmp");
        try {
            Files.write(temporaryNativePath, nativeBytes, StandardOpenOption.TRUNCATE_EXISTING);
            moveIntoPlace(temporaryNativePath, extractedNativePath);
        } finally {
            Files.deleteIfExists(temporaryNativePath);
        }
        return extractedNativePath;
    }

    private static void moveIntoPlace(Path temporaryNativePath, Path extractedNativePath) throws IOException {
        try {
            Files.move(temporaryNativePath, extractedNativePath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryNativePath, extractedNativePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String withContentHash(String resourceFileName, String contentHash) {
        int extensionIndex = resourceFileName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return resourceFileName + '-' + contentHash;
        }
        return resourceFileName.substring(0, extensionIndex) + '-' + contentHash
                + resourceFileName.substring(extensionIndex);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return toHex(messageDigest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte currentByte : bytes) {
            builder.append(Character.forDigit((currentByte >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(currentByte & 0xF, 16));
        }
        return builder.toString();
    }
}
