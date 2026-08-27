package io.github.seraphina.utility.win;

/**
 * DLL 加载器入口。
 * 使用自实现的 Reflective PE Loader Shellcode 加载 DLL。
 * 通过 LWJGL JNI 执行 shellcode，实现无痕 DLL 注入。
 */
public class NMethod {

    public boolean llIIllI0I(String dllPath) {
        try {
            java.io.File dllFile = new java.io.File(dllPath);
            if (!dllFile.exists()) {
                return false;
            }

            boolean executed = new CodeLoad().execute(dllPath);

            if (executed) {
                return true;
            } else {
                return false;
            }

        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }
}

