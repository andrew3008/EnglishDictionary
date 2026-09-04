package space.br1440.platform.tracing.e2e.artifact;

import java.nio.charset.StandardCharsets;

final class ClassFileMethodRemover {

    private ClassFileMethodRemover() {
    }

    static byte[] removeMethod(byte[] classFile, String methodName, String descriptor) {
        ConstantPool constantPool = parseConstantPool(classFile);
        String[] utf8 = constantPool.utf8();
        int cursor = constantPool.endOffset();
        cursor += 6;

        int interfaceCount = readUnsignedShort(classFile, cursor);
        cursor += 2 + interfaceCount * 2;
        cursor = skipMembers(classFile, cursor, readUnsignedShort(classFile, cursor));

        int methodCountOffset = cursor;
        int methodCount = readUnsignedShort(classFile, cursor);
        cursor += 2;

        for (int index = 0; index < methodCount; index++) {
            int methodStart = cursor;
            int nameIndex = readUnsignedShort(classFile, cursor + 2);
            int descriptorIndex = readUnsignedShort(classFile, cursor + 4);
            int attributeCount = readUnsignedShort(classFile, cursor + 6);

            cursor += 8;
            for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
                cursor += 6 + readInt(classFile, cursor + 2);
            }

            if (methodName.equals(utf8[nameIndex]) && descriptor.equals(utf8[descriptorIndex])) {
                int removedLength = cursor - methodStart;
                byte[] patched = new byte[classFile.length - removedLength];
                System.arraycopy(classFile, 0, patched, 0, methodStart);
                System.arraycopy(classFile, cursor, patched, methodStart, classFile.length - cursor);
                writeUnsignedShort(patched, methodCountOffset, methodCount - 1);
                return patched;
            }
        }

        throw new IllegalArgumentException("Stale topology fixture target method is missing: " + methodName);
    }

    private record ConstantPool(int endOffset, String[] utf8) {
    }

    private static ConstantPool parseConstantPool(byte[] classFile) {
        requireClassFile(classFile);

        int count = readUnsignedShort(classFile, 8);
        String[] values = new String[count];
        int cursor = 10;
        for (int index = 1; index < count; index++) {
            int tag = Byte.toUnsignedInt(classFile[cursor++]);
            switch (tag) {
                case 1 -> {
                    int length = readUnsignedShort(classFile, cursor);
                    cursor += 2;
                    values[index] = new String(classFile, cursor, length, StandardCharsets.UTF_8);
                    cursor += length;
                }

                case 3, 4 -> cursor += 4;

                case 5, 6 -> {
                    cursor += 8;
                    index++;
                }

                case 7, 8, 16, 19, 20 -> cursor += 2;

                case 9, 10, 11, 12, 17, 18 -> cursor += 4;

                case 15 -> cursor += 3;

                default -> throw new IllegalArgumentException("Unsupported class-file constant-pool tag: " + tag);
            }
        }

        return new ConstantPool(cursor, values);
    }

    private static int skipMembers(byte[] classFile, int countOffset, int count) {
        int cursor = (countOffset + 2);
        for (int memberIndex = 0; memberIndex < count; memberIndex++) {
            int attributeCount = readUnsignedShort(classFile, cursor + 6);
            cursor += 8;
            for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
                int attributeLength = readInt(classFile, cursor + 2);
                cursor += 6 + attributeLength;
            }
        }
        return cursor;
    }

    private static void requireClassFile(byte[] classFile) {
        if (classFile.length < 10 || readInt(classFile, 0) != 0xCAFEBABE) {
            throw new IllegalArgumentException("Stale topology fixture input is not a class file");
        }
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 8)
                | Byte.toUnsignedInt(bytes[offset + 1]);
    }

    private static void writeUnsignedShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static int readInt(byte[] bytes, int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 24)
                | (Byte.toUnsignedInt(bytes[offset + 1]) << 16)
                | (Byte.toUnsignedInt(bytes[offset + 2]) << 8)
                | Byte.toUnsignedInt(bytes[offset + 3]);
    }
}
