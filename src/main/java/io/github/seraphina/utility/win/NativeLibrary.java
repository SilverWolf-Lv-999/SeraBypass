package io.github.seraphina.utility.win;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;

public record NativeLibrary(Path nativePath, long moduleAddress) {
    private static final int DOS_SIGNATURE = 0x5A4D;
    private static final int PE_SIGNATURE = 0x00004550;
    private static final int PE_HEADER_OFFSET = 0x3C;
    private static final int COFF_HEADER_SIZE = 20;
    private static final int OPTIONAL_HEADER_MAGIC_PE32_PLUS = 0x20B;
    private static final int PE32_PLUS_DATA_DIRECTORY_OFFSET = 112;
    private static final int EXPORT_DIRECTORY_INDEX = 0;
    private static final int EXPORT_DIRECTORY_SIZE = 40;
    private static final int EXPORT_DIRECTORY_NUMBER_OF_NAMES_OFFSET = 24;
    private static final int EXPORT_DIRECTORY_ADDRESS_OF_FUNCTIONS_OFFSET = 28;
    private static final int EXPORT_DIRECTORY_ADDRESS_OF_NAMES_OFFSET = 32;
    private static final int EXPORT_DIRECTORY_ADDRESS_OF_NAME_ORDINALS_OFFSET = 36;
    private static final int MAX_EXPORT_NAME_LENGTH = 1024;

    public NativeLibrary(Path nativePath, long moduleAddress) {
        this.nativePath = Objects.requireNonNull(nativePath, "nativePath");
        if (moduleAddress == 0L) {
            throw new IllegalArgumentException("moduleAddress must not be zero");
        }
        this.moduleAddress = moduleAddress;
    }

    public void registerNative(String bootstrapExport, Method nativeMethod) {
        Objects.requireNonNull(nativeMethod, "nativeMethod");
        if (!Modifier.isNative(nativeMethod.getModifiers())) {
            throw new IllegalArgumentException("nativeMethod must declare the native modifier: " + nativeMethod);
        }
        this.registerNative(
                bootstrapExport,
                nativeMethod.getDeclaringClass(),
                nativeMethod.getName(),
                getMethodDescriptor(nativeMethod)
        );
    }

    public void registerNative(
            String bootstrapExport,
            Class<?> nativeClass,
            String nativeMethodName,
            String nativeMethodDescriptor
    ) {
        validateNotBlank(bootstrapExport, "bootstrapExport");
        Objects.requireNonNull(nativeClass, "nativeClass");
        validateNotBlank(nativeMethodName, "nativeMethodName");
        validateNotBlank(nativeMethodDescriptor, "nativeMethodDescriptor");

        long bootstrapAddress = this.findExportAddress(bootstrapExport);
        ByteBuffer classNameBuffer = null;
        ByteBuffer methodNameBuffer = null;
        ByteBuffer methodDescriptorBuffer = null;
        try {
            classNameBuffer = MemoryUtil.memUTF8(nativeClass.getName().replace('.', '/'), true);
            methodNameBuffer = MemoryUtil.memUTF8(nativeMethodName, true);
            methodDescriptorBuffer = MemoryUtil.memUTF8(nativeMethodDescriptor, true);

            int result = JNI.invokePPPI(
                    MemoryUtil.memAddress(classNameBuffer),
                    MemoryUtil.memAddress(methodNameBuffer),
                    MemoryUtil.memAddress(methodDescriptorBuffer),
                    bootstrapAddress
            );
            if (result != 0) {
                throw new UnsatisfiedLinkError(
                        "Native bootstrap failed with code " + result
                                + ": " + bootstrapExport
                                + " from " + this.nativePath
                );
            }
        } finally {
            MemoryUtil.memFree(methodDescriptorBuffer);
            MemoryUtil.memFree(methodNameBuffer);
            MemoryUtil.memFree(classNameBuffer);
        }
    }

    public long findExportAddress(String exportName) {
        validateNotBlank(exportName, "exportName");

        if (getUnsignedShort(this.moduleAddress) != DOS_SIGNATURE) {
            throw new IllegalStateException("Mapped image does not have an MZ header: " + this.nativePath);
        }

        long peHeaderAddress = this.moduleAddress + getUnsignedInt(this.moduleAddress + PE_HEADER_OFFSET);
        if (MemoryUtil.memGetInt(peHeaderAddress) != PE_SIGNATURE) {
            throw new IllegalStateException("Mapped image does not have a PE header: " + this.nativePath);
        }

        long optionalHeaderAddress = peHeaderAddress + Integer.BYTES + COFF_HEADER_SIZE;
        if (getUnsignedShort(optionalHeaderAddress) != OPTIONAL_HEADER_MAGIC_PE32_PLUS) {
            throw new IllegalStateException("Only PE32+ DLLs are supported: " + this.nativePath);
        }

        long exportDataDirectoryAddress = optionalHeaderAddress
                + PE32_PLUS_DATA_DIRECTORY_OFFSET
                + EXPORT_DIRECTORY_INDEX * 8L;
        long exportDirectoryRva = getUnsignedInt(exportDataDirectoryAddress);
        long exportDirectoryLength = getUnsignedInt(exportDataDirectoryAddress + Integer.BYTES);
        if (exportDirectoryRva == 0L || exportDirectoryLength < EXPORT_DIRECTORY_SIZE) {
            throw new UnsatisfiedLinkError("DLL does not export native bootstrap functions: " + this.nativePath);
        }

        long exportDirectoryAddress = this.moduleAddress + exportDirectoryRva;
        int exportNameCount = MemoryUtil.memGetInt(
                exportDirectoryAddress + EXPORT_DIRECTORY_NUMBER_OF_NAMES_OFFSET
        );
        if (exportNameCount <= 0) {
            throw new UnsatisfiedLinkError("DLL has no named exports: " + this.nativePath);
        }

        long functionTableAddress = this.moduleAddress + getUnsignedInt(
                exportDirectoryAddress + EXPORT_DIRECTORY_ADDRESS_OF_FUNCTIONS_OFFSET
        );
        long nameTableAddress = this.moduleAddress + getUnsignedInt(
                exportDirectoryAddress + EXPORT_DIRECTORY_ADDRESS_OF_NAMES_OFFSET
        );
        long ordinalTableAddress = this.moduleAddress + getUnsignedInt(
                exportDirectoryAddress + EXPORT_DIRECTORY_ADDRESS_OF_NAME_ORDINALS_OFFSET
        );

        for (int index = 0; index < exportNameCount; index++) {
            long nameAddress = this.moduleAddress + getUnsignedInt(
                    nameTableAddress + (long) index * Integer.BYTES
            );
            if (!exportName.equals(readAsciiZ(nameAddress))) {
                continue;
            }

            int ordinal = getUnsignedShort(ordinalTableAddress + (long) index * Short.BYTES);
            long functionRva = getUnsignedInt(functionTableAddress + (long) ordinal * Integer.BYTES);
            if (functionRva >= exportDirectoryRva && functionRva < exportDirectoryRva + exportDirectoryLength) {
                throw new UnsupportedOperationException(
                        "Forwarded exports are not supported: " + exportName + " from " + this.nativePath
                );
            }
            if (functionRva == 0L) {
                throw new UnsatisfiedLinkError("Export resolves to a null function: " + exportName);
            }
            return this.moduleAddress + functionRva;
        }

        throw new UnsatisfiedLinkError("Export not found: " + exportName + " from " + this.nativePath);
    }

    private static String getMethodDescriptor(Method nativeMethod) {
        StringBuilder descriptor = new StringBuilder();
        descriptor.append('(');
        for (Class<?> parameterType : nativeMethod.getParameterTypes()) {
            appendTypeDescriptor(descriptor, parameterType);
        }
        descriptor.append(')');
        appendTypeDescriptor(descriptor, nativeMethod.getReturnType());
        return descriptor.toString();
    }

    private static void appendTypeDescriptor(StringBuilder descriptor, Class<?> type) {
        if (type.isPrimitive()) {
            if (type == void.class) {
                descriptor.append('V');
            } else if (type == boolean.class) {
                descriptor.append('Z');
            } else if (type == byte.class) {
                descriptor.append('B');
            } else if (type == char.class) {
                descriptor.append('C');
            } else if (type == short.class) {
                descriptor.append('S');
            } else if (type == int.class) {
                descriptor.append('I');
            } else if (type == long.class) {
                descriptor.append('J');
            } else if (type == float.class) {
                descriptor.append('F');
            } else if (type == double.class) {
                descriptor.append('D');
            } else {
                throw new IllegalArgumentException("Unsupported primitive type: " + type);
            }
            return;
        }
        if (type.isArray()) {
            descriptor.append(type.getName().replace('.', '/'));
            return;
        }
        descriptor.append('L').append(type.getName().replace('.', '/')).append(';');
    }

    private static int getUnsignedShort(long address) {
        return Short.toUnsignedInt(MemoryUtil.memGetShort(address));
    }

    private static long getUnsignedInt(long address) {
        return Integer.toUnsignedLong(MemoryUtil.memGetInt(address));
    }

    private static String readAsciiZ(long address) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < MAX_EXPORT_NAME_LENGTH; index++) {
            int value = Byte.toUnsignedInt(MemoryUtil.memGetByte(address + index));
            if (value == 0) {
                return builder.toString();
            }
            builder.append((char) value);
        }
        throw new IllegalStateException("Invalid unterminated PE export name");
    }

    private static void validateNotBlank(String value, String parameterName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(parameterName + " must not be blank");
        }
    }
}


