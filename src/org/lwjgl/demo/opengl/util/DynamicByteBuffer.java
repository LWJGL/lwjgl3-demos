package org.lwjgl.demo.opengl.util;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.*;

/**
 * Dynamically growable {@link ByteBuffer}.
 * 
 * @author Kai Burjack
 */
public class DynamicByteBuffer {

    public long addr;
    public int pos;
    public int cap;

    public DynamicByteBuffer() {
        this(8192);
    }

    public DynamicByteBuffer(int initialSize) {
        addr = nmemAlloc(initialSize);
        cap = initialSize;
    }

    private void grow() {
        int newCap = (int) (cap * 1.5f);
        long newAddr = nmemRealloc(addr, newCap);
        cap = newCap;
        addr = newAddr;
    }

    public void free() {
        nmemFree(addr);
    }

    public DynamicByteBuffer putFloat(float v) {
        if (cap - pos < 4)
            grow();
        memPutFloat(addr + pos, v);
        pos += 4;
        return this;
    }

    public DynamicByteBuffer putLong(long v) {
        if (cap - pos < 8)
            grow();
        memPutLong(addr + pos, v);
        pos += 8;
        return this;
    }

    public DynamicByteBuffer putInt(int v) {
        if (cap - pos < 4)
            grow();
        memPutInt(addr + pos, v);
        pos += 4;
        return this;
    }

    public DynamicByteBuffer putShort(int v) {
        if (v > 1 << 16)
            throw new IllegalArgumentException();
        if (cap - pos < 2)
            grow();
        memPutShort(addr + pos, (short) v);
        pos += 2;
        return this;
    }

    public DynamicByteBuffer putByte(int v) {
        if (v > 255)
            throw new IllegalArgumentException();
        if (cap - pos < 2)
            grow();
        memPutByte(addr + pos, (byte) (v & 0xFF));
        pos++;
        return this;
    }

    public int remaining() {
        return (int) (cap - pos);
    }

}
