package io.github.seraphina.utility;

import io.github.seraphina.utility.win.NativeLibrary;
import io.github.seraphina.utility.win.SeraNative;
import io.github.seraphina.utility.win.WIN32;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public final class SysUtility {
    private static final Object NATIVE_LOAD_LOCK = new Object();
    private static final Map<Path, NativeLibrary> NATIVE_LIBRARIES = new HashMap<>();

    private static volatile Throwable lastNativeLoadFailure;

    public static NativeLibrary loadNative(String nativeResourcePath) {
        return loadNative(SysUtility.class, nativeResourcePath);
    }

    public static NativeLibrary loadNative(Class<?> resourceOwner, String nativeResourcePath) {
        return loadNative(resourceOwner, nativeResourcePath, getDefaultNativeDirectory());
    }

    public static NativeLibrary loadNative(
            Class<?> resourceOwner,
            String nativeResourcePath,
            Path nativeDirectory
    ) {
        if (resourceOwner == null) {
            throw new IllegalArgumentException("resourceOwner must not be null");
        }
        if (nativeDirectory == null) {
            throw new IllegalArgumentException("nativeDirectory must not be null");
        }

        String normalizedResourcePath = normalizeResourcePath(nativeResourcePath);
        try {
            URL nativeResourceUrl = getNativeResource(resourceOwner, normalizedResourcePath);
            try (InputStream nativeStream = nativeResourceUrl.openStream()) {
                byte[] nativeBytes = nativeStream.readAllBytes();
                Path nativePath = extractNativeResource(
                        normalizedResourcePath,
                        nativeBytes,
                        nativeDirectory
                );
                return mapNativeLibrary(nativePath);
            }
        } catch (Throwable throwable) {
            lastNativeLoadFailure = throwable;
            throw new IllegalStateException("Unable to map native resource: " + normalizedResourcePath, throwable);
        }
    }

    public static NativeLibrary loadNativeFile(Path nativePath) {
        if (nativePath == null) {
            throw new IllegalArgumentException("nativePath must not be null");
        }
        try {
            return mapNativeLibrary(nativePath.toAbsolutePath().normalize());
        } catch (Throwable throwable) {
            lastNativeLoadFailure = throwable;
            throw new IllegalStateException("Unable to map native library: " + nativePath, throwable);
        }
    }

    public static Throwable getLastNativeLoadFailure() {
        return lastNativeLoadFailure;
    }

    private static NativeLibrary mapNativeLibrary(Path nativePath) throws IOException {
        if (!Files.isRegularFile(nativePath)) {
            throw new IOException("Native library does not exist: " + nativePath);
        }

        synchronized (NATIVE_LOAD_LOCK) {
            NativeLibrary loadedLibrary = NATIVE_LIBRARIES.get(nativePath);
            if (loadedLibrary != null) {
                return loadedLibrary;
            }

            long moduleAddress = new SeraNative().IIllII00IIllII(
                    new WIN32().llIIll01l(),
                    nativePath.toString()
            );
            if (moduleAddress == 0L) {
                throw new UnsatisfiedLinkError("SeraNative failed to map: " + nativePath);
            }

            NativeLibrary nativeLibrary = new NativeLibrary(nativePath, moduleAddress);
            NATIVE_LIBRARIES.put(nativePath, nativeLibrary);
            lastNativeLoadFailure = null;
            return nativeLibrary;
        }
    }

    private static Path getDefaultNativeDirectory() {
        String packageDirectory = SysUtility.class.getPackageName().replace('.', '_');
        return Path.of(System.getProperty("java.io.tmpdir"), packageDirectory, "native");
    }

    private static String normalizeResourcePath(String nativeResourcePath) {
        if (nativeResourcePath == null || nativeResourcePath.isBlank()) {
            throw new IllegalArgumentException("nativeResourcePath must not be blank");
        }

        String normalizedResourcePath = nativeResourcePath.replace('\\', '/').strip();
        while (normalizedResourcePath.startsWith("/")) {
            normalizedResourcePath = normalizedResourcePath.substring(1);
        }
        if (normalizedResourcePath.isEmpty()) {
            throw new IllegalArgumentException("nativeResourcePath must not be blank");
        }
        return normalizedResourcePath;
    }

    private static URL getNativeResource(Class<?> resourceOwner, String nativeResourcePath) throws IOException {
        ClassLoader classLoader = resourceOwner.getClassLoader();
        URL nativeResourceUrl = classLoader == null
                ? ClassLoader.getSystemResource(nativeResourcePath)
                : classLoader.getResource(nativeResourcePath);
        if (nativeResourceUrl == null) {
            throw new IOException("Native resource not found: " + nativeResourcePath);
        }
        return nativeResourceUrl;
    }

    private static Path extractNativeResource(
            String nativeResourcePath,
            byte[] nativeBytes,
            Path nativeDirectory
    ) throws IOException {
        String resourceFileName = nativeResourcePath.substring(nativeResourcePath.lastIndexOf('/') + 1);
        String extractedFileName = withContentHash(resourceFileName, sha256(nativeBytes));
        Path normalizedNativeDirectory = nativeDirectory.toAbsolutePath().normalize();
        Path extractedNativePath = normalizedNativeDirectory.resolve(extractedFileName);

        Files.createDirectories(normalizedNativeDirectory);
        if (Files.isRegularFile(extractedNativePath) && Files.size(extractedNativePath) == nativeBytes.length) {
            return extractedNativePath;
        }

        Path temporaryNativePath = Files.createTempFile(normalizedNativeDirectory, extractedFileName, ".tmp");
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
            Files.move(
                    temporaryNativePath,
                    extractedNativePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
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
