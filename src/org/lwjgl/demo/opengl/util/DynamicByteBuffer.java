/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.util;

import java.nio.ByteBuffer;

import org.lwjgl.system.*;

/**
 * Dynamically growable {@link ByteBuffer}.
 * 
 * @author Kai Burjack
 */
public class DynamicByteBuffer {

    public ByteBuffer bb;

    public DynamicByteBuffer() {
        this(8192);
    }
    public DynamicByteBuffer(int initialSize) {
        bb = MemoryUtil.memAlloc(initialSize);
    }

    private void grow() {
        ByteBuffer newbb = MemoryUtil.memRealloc(bb, (int) (bb.capacity() * 1.5));
        bb = newbb;
    }

    public void free() {
        MemoryUtil.memFree(bb);
    }

    public DynamicByteBuffer putFloat(float v) {
        if (bb.remaining() < 4) {
            grow();
        }
        bb.putFloat(v);
        return this;
    }

    public DynamicByteBuffer putLong(long v) {
        if (bb.remaining() < 8) {
            grow();
        }
        bb.putLong(v);
        return this;
    }

    public DynamicByteBuffer putInt(int v) {
        if (bb.remaining() < 4) {
            grow();
        }
        bb.putInt(v);
        return this;
    }

    public DynamicByteBuffer putShort(int v) {
        if (bb.remaining() < 2) {
            grow();
        }
        bb.putShort((short) (v & 0xFFFF));
        return this;
    }

    public DynamicByteBuffer putByte(int v) {
        if (bb.remaining() < 1) {
            grow();
        }
        bb.put((byte) (v & 0xFF));
        return this;
    }

    public void flip() {
        bb.flip();
    }

    public int remaining() {
        return bb.remaining();
    }

}
