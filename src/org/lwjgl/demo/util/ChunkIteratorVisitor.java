package org.lwjgl.demo.util;

public interface ChunkIteratorVisitor {
    int CONTINUE = 0;
    int CANCEL = 1;
    int ABORT = 2;
    int visit(int x, int y, int z, float d);
}
