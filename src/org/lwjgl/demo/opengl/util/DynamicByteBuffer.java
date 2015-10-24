/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.util;

import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;

/**
 * Dynamically growable {@link ByteBuffer}.
 * 
 * @author Kai Burjack
 */
public class DynamicByteBuffer {

	public ByteBuffer bb;

	public DynamicByteBuffer() {
		bb = BufferUtils.createByteBuffer(1024);
	}

	private void grow() {
		ByteBuffer newbb = BufferUtils.createByteBuffer((int) (bb.capacity() * 1.5));
		bb.flip();
		newbb.put(bb);
		bb = newbb;
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

	public int position() {
		return bb.position();
	}

	public int capacity() {
		return bb.capacity();
	}

	public void flip() {
		bb.flip();
	}

	public int remaining() {
		return bb.remaining();
	}

	public DynamicByteBuffer put(byte b) {
		if (bb.remaining() < 1) {
			grow();
		}
		bb.put(b);
		return this;
	}

}
