package io.github.seraphina.utility.win;

import java.io.File;

import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.WindowsLibrary;

public class CodeLoad {

    private static final Object LOAD_LOCK = new Object();
    private static final String LOADED_PROPERTY = "seraphina.jvmtidll.loaded.path";
    private static final String LOADING_PROPERTY = "seraphina.jvmtidll.loading.path";
    private static SharedLibrary loadedSharedLibrary;
    private static WindowsLibrary loadedWindowsLibrary;
    private static String loadedCanonicalPath;

    public boolean execute(String dllPath) {
        if (dllPath == null || dllPath.isEmpty()) {
            return false;
        }

        synchronized (LOAD_LOCK) {
            try {
                String canonicalPath = new File(dllPath).getCanonicalPath();
                if (isAlreadyLoaded(canonicalPath)) {
                    return true;
                }

                beginLoading(canonicalPath);
                try {
                    if (loadWithWindowsLoaders(canonicalPath)) {
                        markLoaded(canonicalPath);
                        return true;
                    }

                    System.out.println("[CodeLoad] native loaders unavailable; entering SeraNative fallback");
                    byte[] rawShellcode = new WIN32().shellcode();
                    long result = new SeraNative().load(rawShellcode, canonicalPath);
                    if (result != 0) {
                        markLoaded(canonicalPath);
                        return true;
                    }
                } finally {
                    if (!canonicalPath.equals(loadedCanonicalPath)) {
                        clearLoading(canonicalPath);
                    }
                }

                return false;
            } catch (Throwable e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    private boolean loadWithWindowsLoaders(String dllPath) {
        try {
            SharedLibrary candidate = Library.loadNative("seraphina_jvmti", dllPath);
            if (hasValidAddress(candidate)) {
                loadedSharedLibrary = candidate;
                return true;
            }
            freeQuietly(candidate);
            System.err.println("[CodeLoad] Library.loadNative returned no valid module handle");
        } catch (Throwable t) {
            t.printStackTrace();
        }

        try {
            WindowsLibrary candidate = new WindowsLibrary(dllPath);
            if (hasValidAddress(candidate)) {
                loadedWindowsLibrary = candidate;
                return true;
            }
            freeQuietly(candidate);
            System.err.println("[CodeLoad] WindowsLibrary returned no valid module handle");
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return false;
    }

    private static boolean hasValidAddress(SharedLibrary library) {
        if (library == null) return false;
        try {
            return library.address() != 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void freeQuietly(SharedLibrary library) {
        if (library == null) return;
        try {
            library.free();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isAlreadyLoaded(String canonicalPath) {
        if (canonicalPath.equals(loadedCanonicalPath)) {
            return true;
        }
        if (canonicalPath.equals(System.getProperty(LOADED_PROPERTY))) {
            loadedCanonicalPath = canonicalPath;
            return true;
        }
        return canonicalPath.equals(System.getProperty(LOADING_PROPERTY));
    }

    private static void beginLoading(String canonicalPath) {
        System.setProperty(LOADING_PROPERTY, canonicalPath);
    }

    private static void markLoaded(String canonicalPath) {
        loadedCanonicalPath = canonicalPath;
        System.setProperty(LOADED_PROPERTY, canonicalPath);
        clearLoading(canonicalPath);
    }

    private static void clearLoading(String canonicalPath) {
        if (canonicalPath.equals(System.getProperty(LOADING_PROPERTY))) {
            System.clearProperty(LOADING_PROPERTY);
        }
    }

    public static void clearLoadedCache(String dllPath) {
        if (dllPath == null || dllPath.isEmpty()) {
            return;
        }
        synchronized (LOAD_LOCK) {
            try {
                String canonicalPath = new File(dllPath).getCanonicalPath();
                if (canonicalPath.equals(loadedCanonicalPath)) {
                    loadedCanonicalPath = null;
                }
                if (canonicalPath.equals(System.getProperty(LOADED_PROPERTY))) {
                    System.clearProperty(LOADED_PROPERTY);
                }
                clearLoading(canonicalPath);
            } catch (Throwable t) {
                loadedCanonicalPath = null;
                System.clearProperty(LOADED_PROPERTY);
                System.clearProperty(LOADING_PROPERTY);
            }
        }
    }
}

