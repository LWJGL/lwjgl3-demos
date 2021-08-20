/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.game;

import static java.lang.Float.*;
import static java.lang.Math.*;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Collections.sort;
import static java.util.Comparator.*;
import static org.joml.SimplexNoise.noise;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBBufferStorage.*;
import static org.lwjgl.opengl.ARBClearBufferObject.*;
import static org.lwjgl.opengl.ARBClipControl.glClipControl;
import static org.lwjgl.opengl.ARBClipControl.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.ARBDebugOutput.GL_DEBUG_OUTPUT_SYNCHRONOUS_ARB;
import static org.lwjgl.opengl.ARBDrawIndirect.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.ARBIndirectParameters.*;
import static org.lwjgl.opengl.ARBMultiDrawIndirect.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.ARBShaderAtomicCounters.GL_ATOMIC_COUNTER_BUFFER;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.*;
import static org.lwjgl.opengl.ARBShaderStorageBufferObject.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GLUtil.setupDebugMessageCallback;
import static org.lwjgl.opengl.NVFramebufferMultisampleCoverage.glRenderbufferStorageMultisampleCoverageNV;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.*;
import java.lang.Runtime;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.joml.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.demo.game.VoxelGameGL.GreedyMeshing.FaceConsumer;
import org.lwjgl.demo.util.FreeListAllocator;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

/**
 * A simple voxel game.
 * 
 * @author Kai Burjack
 */
public class VoxelGameGL {

    private static final boolean FULLSCREEN = has("fullscreen", false);
    private static final boolean VSYNC = has("vsync", true);
    private static final boolean DEBUG = has("debug", false);
    private static final boolean GRAB_CURSOR = has("grabCursor", true);
    private static final boolean GLDEBUG = has("gldebug", false);
    private static final NumberFormat INT_FORMATTER = NumberFormat.getIntegerInstance();
    private static final NumberFormat PERCENT_FORMATTER = NumberFormat.getPercentInstance();
    static {
        if (DEBUG) {
            /* When we are in debug mode, enable all LWJGL debug flags */
            System.setProperty("org.lwjgl.util.Debug", "true");
            System.setProperty("org.lwjgl.util.NoChecks", "false");
            System.setProperty("org.lwjgl.util.DebugLoader", "true");
            System.setProperty("org.lwjgl.util.DebugAllocator", "true");
            System.setProperty("org.lwjgl.util.DebugStack", "true");
        } else {
            System.setProperty("org.lwjgl.util.NoChecks", "true");
        }
        /* Configure LWJGL MemoryStack to 1024KB */
        Configuration.STACK_SIZE.set(1024);
    }

    /**
     * Dynamically growable ByteBuffer.
     */
    public static class DynamicByteBuffer {
        public long addr;
        public int pos, cap;

        /**
         * Allocate a ByteBuffer with the given initial capacity.
         */
        public DynamicByteBuffer(int initialCapacity) {
            addr = nmemAlloc(initialCapacity);
            cap = initialCapacity;
            if (DEBUG) {
                System.out.println("Creating new DynamicByteBuffer with capacity [" + INT_FORMATTER.format(cap / 1024) + " KB]");
            }
        }

        private void grow() {
            int newCap = (int) (cap * 1.75f);
            if (DEBUG) {
                System.out.println(
                        "Growing DynamicByteBuffer from [" + INT_FORMATTER.format(cap / 1024) + " KB] to [" + INT_FORMATTER.format(newCap / 1024) + " KB]");
            }
            long newAddr = nmemRealloc(addr, newCap);
            cap = newCap;
            addr = newAddr;
        }

        public void free() {
            if (DEBUG) {
                System.out.println("Freeing DynamicByteBuffer (used " + PERCENT_FORMATTER.format((float) pos / cap) + " of capacity)");
            }
            nmemFree(addr);
        }

        public DynamicByteBuffer putInt(int v) {
            if (cap - pos < Integer.BYTES)
                grow();
            return putIntNoGrow(v);
        }

        private DynamicByteBuffer putIntNoGrow(int v) {
            memPutInt(addr + pos, v);
            pos += Integer.BYTES;
            return this;
        }

        public DynamicByteBuffer putShort(int v) {
            if (cap - pos < Short.BYTES)
                grow();
            return putShortNoGrow(v);
        }

        private DynamicByteBuffer putShortNoGrow(int v) {
            memPutShort(addr + pos, (short) v);
            pos += Short.BYTES;
            return this;
        }
    }

    /**
     * Implementation of Greedy Meshing that takes into account the minimum/maximum Y coordinate of
     * active voxels to speedup the meshing.
     * <p>
     * It also generates "neighbor configurations" for simple AO inside of the face value.
     */
    public static class GreedyMeshing {
        /**
         * Consumes a generated face.
         */
        public interface FaceConsumer {
            /**
             * @param u0 the U coordinate of the minimum corner
             * @param v0 the V coordinate of the minimum corner
             * @param u1 the U coordinate of the maximum corner
             * @param v1 the V coordinate of the maximum corner
             * @param p  the main coordinate of the face (depending on the side)
             * @param s  the side of the face (including positive or negative)
             * @param v  the face value (includes neighbor configuration)
             */
            void consume(int u0, int v0, int u1, int v1, int p, int s, int v);
        }

        /**
         * Pre-computed lookup table for neighbor configurations depending on whether any particular of the
         * three neighbors of the possible four vertices of a face is occupied or not.
         */
        private static final int[] NEIGHBOR_CONFIGS = computeNeighborConfigs();
        /**
         * We limit the length of merged faces to 32, to be able to store 31 as 5 bits.
         */
        private static final int MAX_MERGE_LENGTH = 32;

        private final int[] m;
        private final int dx, dy, dz, ny, py;
        private byte[] vs;
        private int count;

        private static int[] computeNeighborConfigs() {
            int[] offs = new int[256];
            for (int i = 0; i < 256; i++) {
                boolean cxny = (i & 1) == 1, nxny = (i & 1 << 1) == 1 << 1, nxcy = (i & 1 << 2) == 1 << 2, nxpy = (i & 1 << 3) == 1 << 3;
                boolean cxpy = (i & 1 << 4) == 1 << 4, pxpy = (i & 1 << 5) == 1 << 5, pxcy = (i & 1 << 6) == 1 << 6, pxny = (i & 1 << 7) == 1 << 7;
                offs[i] = (cxny ? 1 : 0) + (nxny ? 2 : 0) + (nxcy ? 4 : 0) | (cxny ? 1 : 0) + (pxny ? 2 : 0) + (pxcy ? 4 : 0) << 3
                        | (cxpy ? 1 : 0) + (nxpy ? 2 : 0) + (nxcy ? 4 : 0) << 6 | (cxpy ? 1 : 0) + (pxpy ? 2 : 0) + (pxcy ? 4 : 0) << 9;
                offs[i] = offs[i] << 8;
            }
            return offs;
        }

        public GreedyMeshing(int ny, int py, int dx, int dz) {
            this.dx = dx;
            this.dy = py + 1 - ny;
            this.dz = dz;
            this.ny = ny;
            this.py = py + 1;
            this.m = new int[max(dx, dy) * max(dy, dz)];
        }

        private byte at(int x, int y, int z) {
            return vs[idx(x, y, z)];
        }

        private int idx(int x, int y, int z) {
            return x + 1 + (dx + 2) * (z + 1 + (dz + 2) * (y + 1));
        }

        public int mesh(byte[] vs, FaceConsumer faces) {
            this.vs = vs;
            meshX(faces);
            meshY(faces);
            meshZ(faces);
            return count;
        }

        private void meshX(FaceConsumer faces) {
            for (int x = 0; x < dx;) {
                generateMaskX(x);
                mergeAndGenerateFacesX(faces, ++x);
            }
        }

        private void meshY(FaceConsumer faces) {
            for (int y = ny - 1; y < py;) {
                generateMaskY(y);
                mergeAndGenerateFacesY(faces, ++y);
            }
        }

        private void meshZ(FaceConsumer faces) {
            for (int z = 0; z < dz;) {
                generateMaskZ(z);
                mergeAndGenerateFacesZ(faces, ++z);
            }
        }

        private void generateMaskX(int x) {
            int n = 0;
            for (int z = 0; z < dz; z++)
                for (int y = ny; y < py; y++, n++)
                    generateMaskX(x, y, z, n);
        }

        private void generateMaskY(int y) {
            int n = 0;
            for (int x = 0; x < dx; x++)
                for (int z = 0; z < dz; z++, n++)
                    generateMaskY(x, y, z, n);
        }

        private void generateMaskZ(int z) {
            int n = 0;
            for (int y = ny; y < py; y++)
                for (int x = 0; x < dx; x++, n++)
                    generateMaskZ(x, y, z, n);
        }

        private void generateMaskX(int x, int y, int z, int n) {
            int a = at(x, y, z), b = at(x + 1, y, z);
            if (((a == 0) == (b == 0)))
                m[n] = 0;
            else if (a != 0) {
                m[n] = (a & 0xFF) | neighborsX(x + 1, y, z);
            } else
                m[n] = (b & 0xFF) | neighborsX(x, y, z) | 1 << 31;
        }

        private int neighborsX(int x, int y, int z) {
            /* UV = YZ */
            int n0 = at(x, y, z - 1) != 0 ? 1 : 0;
            int n1 = at(x, y - 1, z - 1) != 0 ? 2 : 0;
            int n2 = at(x, y - 1, z) != 0 ? 4 : 0;
            int n3 = at(x, y - 1, z + 1) != 0 ? 8 : 0;
            int n4 = at(x, y, z + 1) != 0 ? 16 : 0;
            int n5 = at(x, y + 1, z + 1) != 0 ? 32 : 0;
            int n6 = at(x, y + 1, z) != 0 ? 64 : 0;
            int n7 = at(x, y + 1, z - 1) != 0 ? 128 : 0;
            return NEIGHBOR_CONFIGS[n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7];
        }

        private void generateMaskY(int x, int y, int z, int n) {
            int a = at(x, y, z), b = at(x, y + 1, z);
            if (((a == 0) == (b == 0)))
                m[n] = 0;
            else if (a != 0) {
                m[n] = (a & 0xFF) | neighborsY(x, y + 1, z);
            } else
                m[n] = (b & 0xFF) | (y >= 0 ? neighborsY(x, y, z) : 0) | 1 << 31;
        }

        private int neighborsY(int x, int y, int z) {
            /* UV = ZX */
            int n0 = at(x - 1, y, z) != 0 ? 1 : 0;
            int n1 = at(x - 1, y, z - 1) != 0 ? 2 : 0;
            int n2 = at(x, y, z - 1) != 0 ? 4 : 0;
            int n3 = at(x + 1, y, z - 1) != 0 ? 8 : 0;
            int n4 = at(x + 1, y, z) != 0 ? 16 : 0;
            int n5 = at(x + 1, y, z + 1) != 0 ? 32 : 0;
            int n6 = at(x, y, z + 1) != 0 ? 64 : 0;
            int n7 = at(x - 1, y, z + 1) != 0 ? 128 : 0;
            return NEIGHBOR_CONFIGS[n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7];
        }

        private void generateMaskZ(int x, int y, int z, int n) {
            int a = at(x, y, z), b = at(x, y, z + 1);
            if (((a == 0) == (b == 0)))
                m[n] = 0;
            else if (a != 0)
                m[n] = (a & 0xFF) | neighborsZ(x, y, z + 1);
            else
                m[n] = (b & 0xFF) | neighborsZ(x, y, z) | 1 << 31;
        }

        private int neighborsZ(int x, int y, int z) {
            /* UV = XY */
            int n0 = at(x, y - 1, z) != 0 ? 1 : 0;
            int n1 = at(x - 1, y - 1, z) != 0 ? 2 : 0;
            int n2 = at(x - 1, y, z) != 0 ? 4 : 0;
            int n3 = at(x - 1, y + 1, z) != 0 ? 8 : 0;
            int n4 = at(x, y + 1, z) != 0 ? 16 : 0;
            int n5 = at(x + 1, y + 1, z) != 0 ? 32 : 0;
            int n6 = at(x + 1, y, z) != 0 ? 64 : 0;
            int n7 = at(x + 1, y - 1, z) != 0 ? 128 : 0;
            return NEIGHBOR_CONFIGS[n0 | n1 | n2 | n3 | n4 | n5 | n6 | n7];
        }

        private void mergeAndGenerateFacesX(FaceConsumer faces, int x) {
            int i, j, n, incr;
            for (j = 0, n = 0; j < dz; j++)
                for (i = ny; i < py; i += incr, n += incr)
                    incr = mergeAndGenerateFaceX(faces, x, n, i, j);
        }

        private void mergeAndGenerateFacesY(FaceConsumer faces, int y) {
            int i, j, n, incr;
            for (j = 0, n = 0; j < dx; j++)
                for (i = 0; i < dz; i += incr, n += incr)
                    incr = mergeAndGenerateFaceY(faces, y, n, i, j);
        }

        private void mergeAndGenerateFacesZ(FaceConsumer faces, int z) {
            int i, j, n, incr;
            for (j = ny, n = 0; j < py; j++)
                for (i = 0; i < dx; i += incr, n += incr)
                    incr = mergeAndGenerateFaceZ(faces, z, n, i, j);
        }

        private int mergeAndGenerateFaceX(FaceConsumer faces, int x, int n, int i, int j) {
            int mn = m[n];
            if (mn == 0)
                return 1;
            int w = determineWidthX(mn, n, i);
            int h = determineHeight(mn, n, j, w, dy, dz);
            faces.consume(i, j, i + w, j + h, x, mn > 0 ? 1 : 0, mn);
            count++;
            eraseMask(n, w, h, dy);
            return w;
        }

        private int mergeAndGenerateFaceY(FaceConsumer faces, int y, int n, int i, int j) {
            int mn = m[n];
            if (mn == 0)
                return 1;
            int w = determineWidthY(mn, n, i);
            int h = determineHeight(mn, n, j, w, dz, dx);
            faces.consume(i, j, i + w, j + h, y, 2 + (mn > 0 ? 1 : 0), mn);
            count++;
            eraseMask(n, w, h, dz);
            return w;
        }

        private int mergeAndGenerateFaceZ(FaceConsumer faces, int z, int n, int i, int j) {
            int mn = m[n];
            if (mn == 0)
                return 1;
            int w = determineWidthZ(mn, n, i);
            int h = determineHeight(mn, n, j, w, dx, py);
            faces.consume(i, j, i + w, j + h, z, 4 + (mn > 0 ? 1 : 0), mn);
            count++;
            eraseMask(n, w, h, dx);
            return w;
        }

        private void eraseMask(int n, int w, int h, int d) {
            for (int l = 0; l < h; l++)
                for (int k = 0; k < w; k++)
                    m[n + k + l * d] = 0;
        }

        private int determineWidthX(int c, int n, int i) {
            int w = 1;
            for (; w < MAX_MERGE_LENGTH && i + w < py && c == m[n + w]; w++)
                ;
            return w;
        }

        private int determineWidthY(int c, int n, int i) {
            int w = 1;
            for (; w < MAX_MERGE_LENGTH && i + w < dz && c == m[n + w]; w++)
                ;
            return w;
        }

        private int determineWidthZ(int c, int n, int i) {
            int w = 1;
            for (; w < MAX_MERGE_LENGTH && i + w < dx && c == m[n + w]; w++)
                ;
            return w;
        }

        private int determineHeight(int c, int n, int j, int w, int du, int maxV) {
            int h = 1;
            for (; h < MAX_MERGE_LENGTH && j + h < maxV; h++)
                for (int k = 0; k < w; k++)
                    if (c != m[n + k + h * du])
                        return h;
            return h;
        }
    }

    /**
     * Represents the voxel field of a single chunk.
     */
    private static class VoxelField {
        /**
         * The minimum y index containing non-zero voxels. This is used to speedup greedy meshing by
         * skipping empty area altogether.
         */
        private int ny;
        /**
         * The maximum y index containing non-zero voxels. This is used to speedup greedy meshing by
         * skipping empty area altogether.
         */
        private int py;
        /**
         * The actual voxel field as a flat array.
         */
        private byte[] field;
        /**
         * The number of set/active non-zero voxels. This value can be used to get a (very) rough estimate
         * of the needed faces when meshing.
         */
        private int num;

        /**
         * Stores the value 'v' into voxel (x, y, z).
         * 
         * @param x the local x coordinate
         * @param y the local y coordinate
         * @param z the local z coordinate
         * @param v the voxel value
         * @return this
         */
        private VoxelField store(int x, int y, int z, byte v) {
            field[idx(x, y, z)] = v;
            /*
             * Update min/max Y coordinate so that meshing as well as frustum culling will take it into account
             */
            ny = min(ny, y);
            py = max(py, y);
            return this;
        }

        /**
         * Loads the current value of the voxel (x, y, z).
         * 
         * @param x the local x coordinate
         * @param y the local y coordinate
         * @param z the local z coordinate
         * @return the voxel value
         */
        private byte load(int x, int y, int z) {
            return field[idx(x, y, z)];
        }
    }

    /**
     * Represents a chunk.
     */
    private static class Chunk {
        /**
         * Whether this chunk can be rendered.
         */
        private boolean ready;
        /**
         * The chunk x position (in units of whole chunks).
         */
        private final int cx;
        /**
         * The chunk z position (in units of whole chunks).
         */
        private final int cz;
        /**
         * The minimum y coordinate where a non-empty voxel exists.
         * <p>
         * This is to speed up meshing and improve frustum culling accuracy.
         */
        private int minY;
        /**
         * The maximum y coordinate where a non-empty voxel exists.
         * <p>
         * This is to speed up meshing and improve frustum culling accuracy.
         */
        private int maxY;
        /**
         * The region associated with this chunk.
         */
        private FreeListAllocator.Region r;
        /**
         * The index in per-chunk buffers.
         */
        private int index;
        /**
         * Bitmask of the occupation of this chunk's neighbors.
         */
        private int neighbors;

        private Chunk(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public String toString() {
            return "[" + index + " @" + cx + "," + cz + "]";
        }
    }

    /**
     * Simple material definition with only a color.
     */
    private static class Material {
        private final int col;

        private Material(int col) {
            this.col = col;
        }
    }

    /**
     * Describes a collision contact.
     */
    private static class Contact implements Comparable<Contact> {
        /* The collision normal */
        public int nx, ny, nz;
        /* The global position of the collided voxel */
        public int x, y, z;
        /* The collision time */
        public final float t;

        public Contact(float t, int x, int y, int z) {
            this.t = t;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int compareTo(Contact o) {
            /* Resolve first by Y contacts, then by distance */
            return ny != o.ny ? o.ny - ny : Float.compare(t, o.t);
        }

        public String toString() {
            return "{" + x + "|" + y + "|" + z + " " + nx + " " + ny + " " + nz + " @ " + t + "}";
        }
    }

    /**
     * An action that is optionally delayed a given number of frames.
     */
    private static class DelayedRunnable {
        private final Callable<Void> runnable;
        private final String name;
        private int delay;

        public DelayedRunnable(Callable<Void> runnable, String name, int delay) {
            this.runnable = runnable;
            this.name = name;
            this.delay = delay;
        }
    }

    /**
     * The type/value of the empty voxel.
     */
    private static final byte EMPTY_VOXEL = 0;

    /**
     * The width and depth of a chunk (in number of voxels).
     */
    private static final int CHUNK_SIZE_SHIFT = 5;
    private static final int CHUNK_SIZE = 1 << CHUNK_SIZE_SHIFT;

    /**
     * The height of a chunk (in number of voxels).
     */
    private static final int CHUNK_HEIGHT = 256;

    /**
     * The index/token used in an index buffer for primitive restart.
     */
    private static final int PRIMITIVE_RESTART_INDEX = 0xFFFF;

    /**
     * The initial capacity of per-face data buffers. The unit is in number of faces, not bytes.
     */
    private static final int INITIAL_PER_FACE_BUFFER_CAPACITY = 1 << 25;

    /**
     * The maximum allowed number of active rendered chunks. We use this as an upper limit when
     * allocating (multi-buffered) arrays/buffer objects.
     */
    private static final int MAX_ACTIVE_CHUNKS = 1 << 16;

    /**
     * The minimum height for the generated terrain.
     */
    private static final int BASE_Y = 50;

    /**
     * The chunk offset for the noise function.
     */
    private static final int GLOBAL_X = 2500, GLOBAL_Z = 851;

    /**
     * The total height of the player's collision box.
     */
    private static final float PLAYER_HEIGHT = 1.80f;

    /**
     * The eye level of the player.
     */
    private static final float PLAYER_EYE_HEIGHT = 1.70f;

    /**
     * The width of the player's collision box.
     */
    private static final float PLAYER_WIDTH = 0.4f;

    /**
     * The number of chunks whose voxel fields are kept in memory to load or store voxels.
     */
    private static final int MAX_CACHED_CHUNKS = 6;

    /**
     * The number of chunks, starting from the player's position, that should be visible in any given
     * direction.
     */
    private static final int MAX_RENDER_DISTANCE_CHUNKS = 40;

    /**
     * The maximum render distance in meters.
     */
    private static final int MAX_RENDER_DISTANCE_METERS = MAX_RENDER_DISTANCE_CHUNKS << CHUNK_SIZE_SHIFT;

    /**
     * The vertical field of view of the camera in degrees.
     */
    private static final int FOV_DEGREES = 72;

    /**
     * The maximum number of async. chunk creation tasks submitted for execution.
     * <p>
     * When this number of tasks is reached,
     * {@link #createInRenderDistanceAndDestroyOutOfRenderDistanceChunks()} will not create any new
     * chunk creation tasks until the number decreases.
     */
    private static final int MAX_NUMBER_OF_CHUNK_TASKS = 16;

    /**
     * Number of distinct buffer regions for dynamic buffers that update every frame, in order to avoid
     * GPU/CPU stalls when waiting for a buffer region to become ready for update after a sourcing draw
     * command finished with it.
     */
    private static final int DYNAMIC_BUFFER_OBJECT_REGIONS = 4;

    /**
     * The number of coverage samples for a multisampled framebuffer, iff
     * {@link #useNvMultisampleCoverage} is <code>true</code>.
     */
    private static final int COVERAGE_SAMPLES = 2;

    /**
     * The number of color samples for a multisampled framebuffer, iff {@link #useNvMultisampleCoverage}
     * is <code>true</code>.
     */
    private static final int COLOR_SAMPLES = 1;

    /**
     * Distance to the near plane.
     */
    private static final float NEAR = 0.1f;

    /**
     * Distance to the far plane.
     */
    private static final float FAR = MAX_RENDER_DISTANCE_CHUNKS * CHUNK_SIZE * 2.0f;

    /**
     * Ambient occlusion factors to be included as a #define in shaders.
     */
    private static final String AO_FACTORS = "0.60, 0.70, 0.85, 1.0";

    /*
     * Comparators to sort the chunks by whether they are in the view frustum and by distance to the
     * player.
     */
    private final Comparator<Chunk> inView = comparing(this::chunkNotInFrustum);
    private final Comparator<Chunk> byDistance = comparingDouble(c -> distToChunk(c.cx, c.cz));
    private final Comparator<Chunk> inViewAndDistance = inView.thenComparing(byDistance);

    /**
     * Used to offload compute-heavy tasks, such as chunk meshing and triangulation, from the render
     * thread to background threads.
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(max(1, Runtime.getRuntime().availableProcessors() / 2), r -> {
        Thread t = new Thread(r);
        t.setPriority(Thread.MIN_PRIORITY);
        t.setName("Chunk builder");
        t.setDaemon(true);
        return t;
    });

    /**
     * The number of chunk building tasks that are currently queued and have not yet finished.
     */
    private final AtomicInteger chunkBuildTasksCount = new AtomicInteger();

    /**
     * Tasks that must be run on the update/render thread, usually because they involve calling OpenGL
     * functions.
     */
    private final Queue<DelayedRunnable> updateAndRenderRunnables = new ConcurrentLinkedQueue<>();

    private long window;
    private int width;
    private int height;
    private final Vector3f playerAcceleration = new Vector3f(0, -30.0f, 0);
    private final Vector3f playerVelocity = new Vector3f();
    private final Vector3d playerPosition = new Vector3d(0, 240, 0);
    private float angx, angy, dangx, dangy;
    private final Matrix4f pMat = new Matrix4f();
    private final Matrix4x3f vMat = new Matrix4x3f();
    private final Matrix4f mvpMat = new Matrix4f();
    private final Matrix4f imvpMat = new Matrix4f();
    private final Matrix4f tmpMat = new Matrix4f();
    private final Quaternionf tmpq = new Quaternionf();
    private final Material[] materials = new Material[512];
    private float nxX, nxY, nxZ, nxW, pxX, pxY, pxZ, pxW, nyX, nyY, nyZ, nyW, pyX, pyY, pyZ, pyW;
    private Callback debugProc;
    private GLCapabilities caps;

    /* All the different features we are using */
    private boolean useMultiDrawIndirect;
    private boolean useBufferStorage;
    private boolean useClearBuffer;
    private boolean drawPointsWithGS;
    private boolean useInverseDepth;
    private boolean useNvMultisampleCoverage;
    private boolean canGenerateDrawCallsViaShader;
    private boolean useOcclusionCulling;
    private boolean useTemporalCoherenceOcclusionCulling;
    private boolean canSourceIndirectDrawCallCountFromBuffer;
    private boolean useRepresentativeFragmentTest;
    private boolean canUseSynchronousDebugCallback;
    /* Other queried OpenGL state/configuration */
    private int uniformBufferOffsetAlignment;

    /* Computed values depending on the render path we use */
    private int verticesPerFace, indicesPerFace, voxelVertexSize;

    /**
     * Index identifying the current region of any dynamic buffer which we will use for updating and
     * drawing from.
     */
    private int currentDynamicBufferIndex;
    /**
     * Fence sync objects for updating/drawing a particular region of the indirect draw buffer.
     */
    private final long[] dynamicBufferUpdateFences = new long[DYNAMIC_BUFFER_OBJECT_REGIONS];
    private int activeFaceCount;
    private int numChunksInFrustum;
    private int fbo, colorRbo, depthRbo;

    private final boolean[] keydown = new boolean[GLFW_KEY_LAST + 1];
    private int mouseX, mouseY;
    private final Vector3f tmpv3f = new Vector3f();
    private final Vector4f tmpv4f = new Vector4f();
    private boolean jumping;

    /**
     * All active chunks by their coordinates.
     */
    private final Map<Vector2i, Chunk> chunkByCoordinate = new LinkedHashMap<>();

    /**
     * All chunks unordered in a linear list.
     */
    private final List<Chunk> allChunks = new ArrayList<>();

    /**
     * All chunks that are at the "frontier" (between loaded and yet unloaded chunks).
     */
    private final List<Chunk> frontierChunks = new ArrayList<>();

    /**
     * LRU cache for voxel fields of recently used chunks.
     * <p>
     * This is used for querying for hit detection and for modifying voxels.
     */
    private final Map<Vector2i, VoxelField> fieldCache = new LinkedHashMap<Vector2i, VoxelField>(MAX_CACHED_CHUNKS + 1, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        public boolean removeEldestEntry(Map.Entry<Vector2i, VoxelField> eldest) {
            return size() > MAX_CACHED_CHUNKS;
        }
    };

    private final FreeListAllocator allocator = new FreeListAllocator(new FreeListAllocator.OutOfCapacityCallback() {
      public int onCapacityIncrease(int currentCapacity) {
        int newPerFaceBufferCapacity = max(currentCapacity << 1, INITIAL_PER_FACE_BUFFER_CAPACITY);
        updateAndRenderRunnables.add(new DelayedRunnable(() -> {
            enlargePerFaceBuffers(currentCapacity, newPerFaceBufferCapacity);
            return null;
        }, "Enlarge per-face buffers", 0));
        return newPerFaceBufferCapacity;
      }
    });

    /**
     * Simple {@link BitSet} to find and allocate indexes for per-chunk arrays/buffers.
     */
    private final BitSet chunkIndexes = new BitSet(MAX_ACTIVE_CHUNKS);

    /* Resources for drawing the chunks */
    private int chunkInfoBufferObject;
    private int chunkInfoTexture;
    private int vertexDataBufferObject;
    private int indexBufferObject;
    private int indirectDrawBuffer;
    private int chunksVao;
    private int chunksProgram;
    private int materialsTexture;
    private int chunksProgramUboBlockIndex;
    private int chunksProgramUbo;
    private long chunksProgramUboAddr;
    private static final int chunksProgramUboSize = 4 * (16 + 2 * 4);

    /* Resources for drawing chunks' bounding boxes to fill visibility buffer */
    private int visibilityFlagsBuffer;
    private int boundingBoxesVao;
    private int boundingBoxesProgram;
    private int boundingBoxesProgramUboBlockIndex;
    private int boundingBoxesProgramUbo;
    private long boundingBoxesProgramUboAddr;
    private int boundingBoxesVertexBufferObject;
    private long boundingBoxesVertexBufferObjectAddr;
    private long indirectDrawBufferAddr;
    private static final int boundingBoxesProgramUboSize = 4 * (16 + 4);

    /* Resources for collecting draw calls using the visibility buffer */
    private int collectDrawCallsProgram;
    private int atomicCounterBuffer;
    private int indirectDrawCulledBuffer;

    /* Resources for drawing the "selection" quad */
    private int nullVao;
    private int selectionProgram;
    private int selectionProgramUboBlockIndex;
    private int selectionProgramUbo;
    private long selectionProgramUboAddr;
    private static final int selectionProgramUboSize = 4 * 16;
    private final Vector3i selectedVoxelPosition = new Vector3i();
    private final Vector3i sideOffset = new Vector3i();
    private boolean hasSelection;
    private boolean firstCursorPos = true;
    private boolean fly;
    private boolean wireframe;
    private boolean debugBoundingBoxes;

    private static boolean has(String prop, boolean def) {
        String value = System.getProperty(prop);
        return value != null ? value.isEmpty() || Boolean.parseBoolean(value) : def;
    }

    /**
     * Callback for mouse movement.
     * 
     * @param window the window (we only have one, currently)
     * @param x      the x coordinate
     * @param y      the y coordinate
     */
    private void onCursorPos(long window, double x, double y) {
        if (!firstCursorPos) {
            float deltaX = (float) x - mouseX;
            float deltaY = (float) y - mouseY;
            dangx += deltaY;
            dangy += deltaX;
        }
        firstCursorPos = false;
        mouseX = (int) x;
        mouseY = (int) y;
    }

    /**
     * Calls {@link #findAndSetSelectedVoxel(float, float, float, float, float, float)} with the current
     * player position and orientation.
     */
    private void determineSelectedVoxel() {
        if (fly) {
            hasSelection = false;
            return;
        }
        Vector3f dir = tmpq.rotationX(angx).rotateY(angy).positiveZ(tmpv3f).negate();
        findAndSetSelectedVoxel((float) playerPosition.x, (float) playerPosition.y, (float) playerPosition.z, dir.x, dir.y, dir.z);
    }

    /**
     * GLFW callback when a key is pressed/released.
     */
    private void onKey(long window, int key, int scancode, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE) {
            glfwSetWindowShouldClose(window, true);
        } else if (key >= 0) {
            keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
        }
        handleSpecialKeys(key, action);
    }

    /**
     * Handle special keyboard keys before storing a key press/release state in the {@link #keydown}
     * array.
     */
    private void handleSpecialKeys(int key, int action) {
        if (key == GLFW_KEY_F && action == GLFW_PRESS) {
            fly = !fly;
        } else if (key == GLFW_KEY_2 && action == GLFW_PRESS) {
            wireframe = !wireframe;
        } else if (key == GLFW_KEY_1 && action == GLFW_PRESS) {
            debugBoundingBoxes = !debugBoundingBoxes;
        }
    }

    /**
     * GLFW framebuffer size callback.
     */
    private void onFramebufferSize(long window, int w, int h) {
        if (w <= 0 && h <= 0)
            return;
        updateAndRenderRunnables.add(new DelayedRunnable(() -> {
            width = w;
            height = h;
            createFramebufferObject();
            glViewport(0, 0, width, height);
            return null;
        }, "Framebuffer size change", 0));
    }

    /**
     * Store the value <code>v</code> in the voxel at the global coordinate <code>(x, y, z)</code>.
     */
    private void store(int x, int y, int z, byte v) {
        /* Perform arithmetic/sign-preserving shift to calculate chunk position */
        int cx = x >> CHUNK_SIZE_SHIFT, cz = z >> CHUNK_SIZE_SHIFT;
        Chunk chunk = chunkByCoordinate.get(new Vector2i(cx, cz));
        if (chunk == null) {
            System.err.println("Tried to store(" + x + ", " + y + ", " + z + ", " + v + ") to non-existing chunk");
            return;
        }
        VoxelField f = voxelFieldFor(cx, cz, false);
        int lx = x - (cx << CHUNK_SIZE_SHIFT), lz = z - (cz << CHUNK_SIZE_SHIFT);
        f.store(lx, y, lz, v);
        chunk.minY = f.ny;
        chunk.maxY = f.py;
        updateChunk(chunk, f);
        updateEdgesOfNeighborChunks(cx, cz, lx, y, lz, v);
    }

    /**
     * Update the edges of nearby chunks.
     * <p>
     * This updates either zero, one, two or three nearby chunks.
     * <p>
     * Each chunk has a one voxel margin around it to correctly generate faces between two adjacent
     * chunks.
     */
    private void updateEdgesOfNeighborChunks(int cx, int cz, int lx, int y, int lz, byte v) {
        if (lx == CHUNK_SIZE - 1) {
            updateChunk(chunkByCoordinate.get(new Vector2i(cx + 1, cz)), voxelFieldFor(cx + 1, cz, false).store(-1, y, lz, v));
            if (lz == CHUNK_SIZE - 1) {
                updateChunk(chunkByCoordinate.get(new Vector2i(cx + 1, cz + 1)), voxelFieldFor(cx + 1, cz + 1, false).store(-1, y, -1, v));
            } else if (lz == 0) {
                updateChunk(chunkByCoordinate.get(new Vector2i(cx + 1, cz - 1)), voxelFieldFor(cx + 1, cz - 1, false).store(-1, y, CHUNK_SIZE, v));
            }
        } else if (lx == 0) {
            updateChunk(chunkByCoordinate.get(new Vector2i(cx - 1, cz)), voxelFieldFor(cx - 1, cz, false).store(CHUNK_SIZE, y, lz, v));
            if (lz == CHUNK_SIZE - 1) {
                updateChunk(chunkByCoordinate.get(new Vector2i(cx - 1, cz + 1)), voxelFieldFor(cx - 1, cz + 1, false).store(CHUNK_SIZE, y, -1, v));
            } else if (lz == 0) {
                updateChunk(chunkByCoordinate.get(new Vector2i(cx - 1, cz - 1)), voxelFieldFor(cx - 1, cz - 1, false).store(CHUNK_SIZE, y, CHUNK_SIZE, v));
            }
        }
        if (lz == CHUNK_SIZE - 1) {
            updateChunk(chunkByCoordinate.get(new Vector2i(cx, cz + 1)), voxelFieldFor(cx, cz + 1, false).store(lx, y, -1, v));
        } else if (lz == 0) {
            updateChunk(chunkByCoordinate.get(new Vector2i(cx, cz - 1)), voxelFieldFor(cx, cz - 1, false).store(lx, y, CHUNK_SIZE, v));
        }
    }

    /**
     * Return the voxel field value at the global position <code>(x, y, z)</code>.
     */
    private byte load(int x, int y, int z) {
        int cx = x >> CHUNK_SIZE_SHIFT, cz = z >> CHUNK_SIZE_SHIFT;
        ensureChunk(cx, cz);
        VoxelField f = voxelFieldFor(cx, cz, false);
        int lx = x - (cx << CHUNK_SIZE_SHIFT), lz = z - (cz << CHUNK_SIZE_SHIFT);
        return f.load(lx, y, lz);
    }

    /**
     * GLSL's step function.
     */
    private static int step(float edge, float x) {
        return x < edge ? 0 : 1;
    }

    /**
     * Determine the side (as a normal vector) at which the ray
     * <code>(ox, oy, oz) + t * (dx, dy, dz)</code> enters the unit box with min coordinates
     * <code>(x, y, z)</code>, and store the normal of that face into <code>off</code>.
     */
    private static void enterSide(float ox, float oy, float oz, float dx, float dy, float dz, int x, int y, int z, Vector3i off) {
        float tMinx = (x - ox) / dx, tMiny = (y - oy) / dy, tMinz = (z - oz) / dz;
        float tMaxx = (x + 1 - ox) / dx, tMaxy = (y + 1 - oy) / dy, tMaxz = (z + 1 - oz) / dz;
        float t1x = min(tMinx, tMaxx), t1y = min(tMiny, tMaxy), t1z = min(tMinz, tMaxz);
        float tNear = max(max(t1x, t1y), t1z);
        off.set(tNear == t1x ? dx > 0 ? -1 : 1 : 0, tNear == t1y ? dy > 0 ? -1 : 1 : 0, tNear == t1z ? dz > 0 ? -1 : 1 : 0);
    }

    /**
     * Determine the voxel pointed to by a ray <code>(ox, oy, oz) + t * (dx, dy, dz)</code> and store
     * the position and side offset of that voxel (if any) into {@link #selectedVoxelPosition} and
     * {@link #sideOffset}, respectively.
     * 
     * @param ox the ray origin's x coordinate
     * @param oy the ray origin's y coordinate
     * @param oz the ray origin's z coordinate
     * @param dx the ray direction's x coordinate
     * @param dy the ray direction's y coordinate
     * @param dz the ray direction's z coordinate
     */
    private void findAndSetSelectedVoxel(float ox, float oy, float oz, float dx, float dy, float dz) {
        /* "A Fast Voxel Traversal Algorithm for Ray Tracing" by John Amanatides, Andrew Woo */
        float big = 1E30f;
        int px = (int) floor(ox), py = (int) floor(oy), pz = (int) floor(oz);
        float dxi = 1f / dx, dyi = 1f / dy, dzi = 1f / dz;
        float sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        float dtx = min(dxi * sx, big), dty = min(dyi * sy, big), dtz = min(dzi * sz, big);
        float tx = abs((px + max(sx, 0) - ox) * dxi), ty = abs((py + max(sy, 0) - oy) * dyi), tz = abs((pz + max(sz, 0) - oz) * dzi);
        int maxSteps = 16;
        for (int i = 0; i < maxSteps && py >= 0; i++) {
            if (i > 0 && py < CHUNK_HEIGHT) {
                if (load(px, py, pz) != 0) {
                    selectedVoxelPosition.set(px, py, pz);
                    enterSide(ox, oy, oz, dx, dy, dz, px, py, pz, sideOffset);
                    hasSelection = true;
                    return;
                }
            }
            /* Advance to next voxel */
            int cmpx = step(tx, tz) * step(tx, ty);
            int cmpy = step(ty, tx) * step(ty, tz);
            int cmpz = step(tz, ty) * step(tz, tx);
            tx += dtx * cmpx;
            ty += dty * cmpy;
            tz += dtz * cmpz;
            px += sx * cmpx;
            py += sy * cmpy;
            pz += sz * cmpz;
        }
        hasSelection = false;
    }

    /**
     * GLFW callback for mouse buttons.
     */
    private void onMouseButton(long window, int button, int action, int mods) {
        updateAndRenderRunnables.add(new DelayedRunnable(() -> {
            if (button == GLFW_MOUSE_BUTTON_1 && action == GLFW_PRESS && hasSelection) {
                placeAtSelectedVoxel();
            } else if (button == GLFW_MOUSE_BUTTON_2 && action == GLFW_PRESS && hasSelection) {
                removeSelectedVoxel();
            }
            return null;
        }, "Mouse button event", 0));
    }

    private void removeSelectedVoxel() {
        store(selectedVoxelPosition.x, selectedVoxelPosition.y, selectedVoxelPosition.z, (byte) 0);
    }

    private void placeAtSelectedVoxel() {
        if (selectedVoxelPosition.y + sideOffset.y < 0 || selectedVoxelPosition.y + sideOffset.y >= CHUNK_HEIGHT)
            return;
        store(selectedVoxelPosition.x + sideOffset.x, selectedVoxelPosition.y + sideOffset.y, selectedVoxelPosition.z + sideOffset.z, (byte) 3);
    }

    /**
     * Register all necessary GLFW callbacks.
     */
    private void registerWindowCallbacks() {
        glfwSetFramebufferSizeCallback(window, this::onFramebufferSize);
        glfwSetKeyCallback(window, this::onKey);
        glfwSetCursorPosCallback(window, this::onCursorPos);
        glfwSetMouseButtonCallback(window, this::onMouseButton);
    }

    private void createWindow() {
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        /*
         * Disable window framebuffer bits we don't need, because we render into offscreen FBO and blit to
         * window.
         */
        glfwWindowHint(GLFW_DEPTH_BITS, 0);
        glfwWindowHint(GLFW_STENCIL_BITS, 0);
        glfwWindowHint(GLFW_ALPHA_BITS, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_TRUE);
        if (FULLSCREEN) {
            glfwWindowHint(GLFW_FLOATING, GLFW_TRUE);
            glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        }
        if (DEBUG || GLDEBUG) {
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        }
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vidmode = glfwGetVideoMode(monitor);
        width = (int) (Objects.requireNonNull(vidmode).width() * (FULLSCREEN ? 1 : 0.8f));
        height = (int) (vidmode.height() * (FULLSCREEN ? 1 : 0.8f));
        window = glfwCreateWindow(width, height, "Hello, voxel world!", FULLSCREEN ? monitor : NULL, NULL);
        if (GRAB_CURSOR) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }
        if (window == NULL)
            throw new AssertionError("Failed to create the GLFW window");
    }

    private void setWindowPosition() {
        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (Objects.requireNonNull(vidmode).width() - width) / 2, (vidmode.height() - height) / 2);
    }

    private void queryFramebufferSizeForHiDPI() {
        try (MemoryStack frame = stackPush()) {
            IntBuffer framebufferSize = frame.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
            width = framebufferSize.get(0);
            height = framebufferSize.get(1);
        }
    }

    private void installDebugCallback() {
        debugProc = setupDebugMessageCallback();
        if (canUseSynchronousDebugCallback) {
            glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS_ARB);
        }
    }

    /**
     * Set global GL state that will not be changed afterwards.
     */
    private void configureGlobalGlState() {
        glClearColor(225 / 255f, 253 / 255f, 255 / 255f, 0f);
        glEnable(GL_PRIMITIVE_RESTART);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glPrimitiveRestartIndex(PRIMITIVE_RESTART_INDEX);
        if (useInverseDepth) {
            glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE);
            glDepthFunc(GL_GREATER);
            glClearDepth(0.0);
        } else {
            glDepthFunc(GL_LESS);
        }
    }

    /**
     * Query all (optional) capabilites/extensions that we want to use from the OpenGL context via
     * LWJGL's {@link GLCapabilities}.
     */
    private void determineOpenGLCapabilities() {
        caps = GL.createCapabilities();
        useMultiDrawIndirect = caps.GL_ARB_multi_draw_indirect || caps.OpenGL43;
        useBufferStorage = caps.GL_ARB_buffer_storage || caps.OpenGL44;
        useClearBuffer = caps.GL_ARB_clear_buffer_object || caps.OpenGL43;
        drawPointsWithGS = useMultiDrawIndirect; // <- we just haven't implemented point/GS rendering without MDI yet
        useInverseDepth = caps.GL_ARB_clip_control || caps.OpenGL45;
        useNvMultisampleCoverage = caps.GL_NV_framebuffer_multisample_coverage;
        canUseSynchronousDebugCallback = caps.GL_ARB_debug_output || caps.OpenGL43;
        canGenerateDrawCallsViaShader = caps.GL_ARB_shader_image_load_store/* 4.2 */ && caps.GL_ARB_shader_storage_buffer_object/* 4.3 */
                && caps.GL_ARB_shader_atomic_counters/* 4.2 */ || caps.OpenGL43;
        useOcclusionCulling = canGenerateDrawCallsViaShader && useMultiDrawIndirect;
        useTemporalCoherenceOcclusionCulling = useOcclusionCulling && true;
        canSourceIndirectDrawCallCountFromBuffer = canGenerateDrawCallsViaShader && (caps.GL_ARB_indirect_parameters || caps.OpenGL46);
        useRepresentativeFragmentTest = caps.GL_NV_representative_fragment_test;
        /* Query the necessary UBO alignment which we need for multi-buffering */
        uniformBufferOffsetAlignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);

        System.out.println("useMultiDrawIndirect: " + useMultiDrawIndirect);
        System.out.println("useBufferStorage: " + useBufferStorage);
        System.out.println("drawPointsWithGS: " + drawPointsWithGS);
        System.out.println("useInverseDepth: " + useInverseDepth);
        System.out.println("useNvMultisampleCoverage: " + useNvMultisampleCoverage);
        System.out.println("canUseSynchronousDebugCallback: " + canUseSynchronousDebugCallback);
        System.out.println("canGenerateDrawCallsViaShader: " + canGenerateDrawCallsViaShader);
        System.out.println("useOcclusionCulling: " + useOcclusionCulling);
        System.out.println("useTemporalCoherenceOcclusionCulling: " + useTemporalCoherenceOcclusionCulling);
        System.out.println("canSourceIndirectDrawCallCountFromBuffer: " + canSourceIndirectDrawCallCountFromBuffer);
        System.out.println("useRepresentativeFragmentTest: " + useRepresentativeFragmentTest);
        System.out.println("uniformBufferOffsetAlignment: " + uniformBufferOffsetAlignment);
    }

    /**
     * We will render to an FBO.
     */
    private void createFramebufferObject() {
        /*
         * Delete any existing FBO (happens when we resize the window).
         */
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            glDeleteRenderbuffers(new int[] { colorRbo, depthRbo });
        }
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        colorRbo = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, colorRbo);
        if (useNvMultisampleCoverage) {
            glRenderbufferStorageMultisampleCoverageNV(GL_RENDERBUFFER, COVERAGE_SAMPLES, COLOR_SAMPLES, GL_RGBA8, width, height);
        } else {
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, COVERAGE_SAMPLES, GL_RGBA8, width, height);
        }
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colorRbo);
        depthRbo = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
        if (useNvMultisampleCoverage) {
            glRenderbufferStorageMultisampleCoverageNV(GL_RENDERBUFFER, COVERAGE_SAMPLES, COLOR_SAMPLES, GL_DEPTH_COMPONENT32F, width, height);
        } else {
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, COVERAGE_SAMPLES, GL_DEPTH_COMPONENT32F, width, height);
        }
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRbo);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Create an empty VAO.
     */
    private void createNullVao() {
        nullVao = glGenVertexArrays();
    }

    private void createBoundingBoxesVao() {
        boundingBoxesVao = glGenVertexArrays();
        glBindVertexArray(boundingBoxesVao);
        boundingBoxesVertexBufferObject = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, boundingBoxesVertexBufferObject);
        if (useBufferStorage) {
            glBufferStorage(GL_ARRAY_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * 4 * Integer.BYTES,
                    GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT);
            boundingBoxesVertexBufferObjectAddr = nglMapBufferRange(GL_ARRAY_BUFFER, 0L, DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * 4 * Integer.BYTES,
                    GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_FLUSH_EXPLICIT_BIT);
        } else {
            glBufferData(GL_ARRAY_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * 4 * Integer.BYTES, GL_STATIC_DRAW);
        }
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    /**
     * Create the buffer holding the chunk information (currently the position of the chunk).
     * <p>
     * If we use MDI, then the buffer will be an instanced array buffer, else it will be a texture
     * buffer, where the shader will lookup the chunk info based on chunk index (stored as per-vertex
     * attribute).
     */
    private void createChunkInfoBuffers() {
        chunkInfoBufferObject = glGenBuffers();
        /*
         * When we have MDI we will use an instanced vertex attribute to hold the chunk position, where each
         * chunk is a separate instance. When we don't have MDI, we will use a buffer texture and lookup in
         * the shader.
         */
        if (useMultiDrawIndirect) {
            glBindBuffer(GL_ARRAY_BUFFER, chunkInfoBufferObject);
            if (useBufferStorage) {
                glBufferStorage(GL_ARRAY_BUFFER, 4 * Integer.BYTES * MAX_ACTIVE_CHUNKS, GL_DYNAMIC_STORAGE_BIT);
            } else {
                glBufferData(GL_ARRAY_BUFFER, 4 * Integer.BYTES * MAX_ACTIVE_CHUNKS, GL_STATIC_DRAW);
            }
        } else {
            chunkInfoTexture = glGenTextures();
            glBindBuffer(GL_TEXTURE_BUFFER, chunkInfoBufferObject);
            glBindTexture(GL_TEXTURE_BUFFER, chunkInfoTexture);
            if (useBufferStorage) {
                glBufferStorage(GL_TEXTURE_BUFFER, 4 * Integer.BYTES * MAX_ACTIVE_CHUNKS, GL_DYNAMIC_STORAGE_BIT);
            } else {
                glBufferData(GL_TEXTURE_BUFFER, 4 * Integer.BYTES * MAX_ACTIVE_CHUNKS, GL_STATIC_DRAW);
            }
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, chunkInfoBufferObject);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
        }
    }

    /**
     * Create the (multi-buffered) buffer for multi-draw-indirect rendering, holding either the MDI draw
     * structs of chunks or, if we use occlusion culling, hold the chunks' face offset and count.
     */
    private void createMultiDrawIndirectBuffer() {
        indirectDrawBuffer = glGenBuffers();
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBuffer);
        /*
         * When we use occlusion culling (temporal coherence or not) we do not store MDI structs into the
         * indirect buffer, but only face offset and count, because we will generated the actual MDI structs
         * in the collectdrawcalls.vs.glsl shader.
         */
        long structSize = useOcclusionCulling ? 2 * Integer.BYTES : 5 * Integer.BYTES;
        if (useBufferStorage) {
            glBufferStorage(GL_DRAW_INDIRECT_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * structSize,
                    GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT);
            indirectDrawBufferAddr = nglMapBufferRange(GL_DRAW_INDIRECT_BUFFER, 0L, DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * structSize,
                    GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_FLUSH_EXPLICIT_BIT);
        } else {
            glBufferData(GL_DRAW_INDIRECT_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * structSize, GL_STATIC_DRAW);
        }
    }

    /**
     * Create some pre-defined materials.
     */
    private void createMaterials() {
        materials[1] = new Material(rgb(56, 140, 7));
        materials[2] = new Material(rgb(128, 106, 90));
        materials[3] = new Material(rgb(88, 108, 88));
        createMaterialsTexture();
    }

    /**
     * Ensure that the chunk at <code>(cx, cz)</code> is created.
     * 
     * @return a new {@link Chunk} instance iff the chunk does not already exist; <code>null</code>
     *         otherwise
     */
    private Chunk ensureChunk(int cx, int cz) {
        if (chunkByCoordinate.containsKey(new Vector2i(cx, cz)))
            return null;
        return createChunk(cx, cz);
    }

    /**
     * Destroy the given chunk.
     * <p>
     * Steps to do:
     * <ol>
     * <li>Remove that chunk from the frontier (it likely was a frontier chunk)
     * <li>Promote chunks around it to frontier
     * <li>Deallocate the per-face and per-chunk buffer regions
     * <li>Remove it from the linear list of all chunks
     * <li>Decrement the "active faces" counter
     * <li>Remove it from the coordinate lookup map
     * </ol>
     */
    private int destroyChunk(Chunk chunk) {
        if (DEBUG) {
            System.out.println("Destroying chunk: " + chunk);
        }
        int numDiff = onFrontierChunkRemoved(chunk) - 1;
        frontierChunks.remove(chunk);
        if (DEBUG) {
            System.out.println("Removed frontier chunk #" + frontierChunks.size() + ": " + chunk);
        }
        deallocatePerFaceBufferRegion(chunk);
        deallocatePerChunkIndex(chunk);
        allChunks.remove(chunk);
        activeFaceCount -= chunk.r.len;
        if (DEBUG) {
            System.out.println("Number of chunks: " + INT_FORMATTER.format(allChunks.size()) + " ("
                    + INT_FORMATTER.format(computePerFaceBufferObjectSize() / 1024 / 1024) + " MB)");
            System.out.println("Number of faces:  " + INT_FORMATTER.format(activeFaceCount));
        }
        chunkByCoordinate.remove(new Vector2i(chunk.cx, chunk.cz));
        return numDiff;
    }

    /**
     * Create a chunk at the position <code>(cx, cz)</code> (in units of whole chunks).
     * 
     * @param cx the x coordinate of the chunk position
     * @param cz the z coordinate of the chunk position
     */
    private Chunk createChunk(int cx, int cz) {
        Chunk chunk = new Chunk(cx, cz);
        allChunks.add(chunk);
        chunkByCoordinate.put(new Vector2i(cx, cz), chunk);
        chunk.index = allocateChunkIndex();
        addFrontier(chunk);
        chunkBuildTasksCount.incrementAndGet();
        /*
         * Submit async task to create the chunk.
         */
        executorService.submit(() -> {
            try {
                asyncCreateChunk(cx, cz, chunk);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        return chunk;
    }

    /**
     * Will be called from within the {@link ExecutorService} thread to build a voxel field and
     * triangulate it.
     */
    private void asyncCreateChunk(int cx, int cz, Chunk chunk) {
        long time1 = System.nanoTime();
        /* Create voxel field for that chunk without storing in cache. */
        VoxelField field = voxelFieldFor(cx, cz, true);
        long time2 = System.nanoTime();
        long time3 = meshChunkFacesAndWriteToBuffers(chunk, field);
        if (DEBUG) {
            System.out.println("Async. created chunk " + chunk + " in " + INT_FORMATTER.format((time3 - time1) / (long) 1E3) + " µs ["
                    + INT_FORMATTER.format((time2 - time1) / (long) 1E3) + " | " + INT_FORMATTER.format((time3 - time2) / (long) 1E3) + "]");
        }
    }

    /**
     * Compute the used memory in all buffer objects for all chunks.
     */
    private int computePerFaceBufferObjectSize() {
        int bytes = 0;
        for (Chunk c : allChunks) {
          if (c.r != null)
            bytes += c.r.len * (verticesPerFace * voxelVertexSize + indicesPerFace * Short.BYTES);
        }
        return bytes;
    }

    /**
     * Called whenever the given chunk (which is automatically made frontier) became visible.
     */
    private void addFrontier(Chunk chunk) {
        frontierChunks.add(chunk);
        if (DEBUG) {
            System.out.println("Added frontier chunk #" + frontierChunks.size() + ": " + chunk);
        }
        int cx = chunk.cx, cz = chunk.cz;
        /*
         * Update the four neighbors of this new frontier chunk, because those chunks might not be frontier
         * anymore, once all their four neighbors are occupied.
         */
        updateFrontierNeighbor(chunk, cx - 1, cz);
        updateFrontierNeighbor(chunk, cx + 1, cz);
        updateFrontierNeighbor(chunk, cx, cz - 1);
        updateFrontierNeighbor(chunk, cx, cz + 1);
    }

    /**
     * Called whenever a new frontier chunk was created, in order to update its neighbor at chunk
     * position <code>(cx, cz)</code>.
     */
    private void updateFrontierNeighbor(Chunk frontier, int cx, int cz) {
        Chunk n = chunkByCoordinate.get(new Vector2i(cx, cz));
        if (n != null) {
            n.neighbors++;
            frontier.neighbors++;
            if (n.neighbors == 4) {
                frontierChunks.remove(n);
                if (DEBUG) {
                    System.out.println("Removed surrounded frontier chunk #" + frontierChunks.size() + ": " + n);
                }
            }
        }
    }

    /**
     * Called whenever the given frontier chunk was destroyed and the chunks frontier must be updated.
     */
    private int onFrontierChunkRemoved(Chunk frontierChunk) {
        int cx = frontierChunk.cx, cz = frontierChunk.cz;
        double d = distToChunk(cx, cz);
        return onFrontierChunkRemoved(cx - 1, cz, d) + onFrontierChunkRemoved(cx + 1, cz, d) + onFrontierChunkRemoved(cx, cz - 1, d)
                + onFrontierChunkRemoved(cx, cz + 1, d);
    }

    private int onFrontierChunkRemoved(int cx, int cz, double d) {
        Chunk n = chunkByCoordinate.get(new Vector2i(cx, cz));
        if (n != null) {
            n.neighbors--;
            if (!frontierChunks.contains(n) && (chunkInRenderDistance(cx, cz) || distToChunk(cx, cz) < d)) {
                frontierChunks.add(n);
                if (DEBUG) {
                    System.out.println("Added retreating frontier chunk #" + frontierChunks.size() + ": " + n);
                }
                return 1;
            }
        }
        return 0;
    }

    /**
     * Allocate a new chunk index for per-chunk buffers/arrays.
     */
    private int allocateChunkIndex() {
        int next = chunkIndexes.nextClearBit(0);
        if (next >= MAX_ACTIVE_CHUNKS) {
            throw new AssertionError("Failed to allocate per-chunk index");
        }
        if (DEBUG) {
            System.out.println("Allocated pre-chunk index " + next);
        }
        chunkIndexes.set(next);
        return next;
    }

    /**
     * Deallocate/free a chunk index for per-chunk buffers/arrays.
     */
    private void deallocatePerChunkIndex(Chunk chunk) {
        /*
         * If we use temporal coherence occlusion culling, we must delay deallocating the chunk index by 1
         * frame, because the next frame still wants to potentially draw the chunk when it was visible last
         * frame, so we must not allocate this index to another chunk immediately.
         */
        int delayFrames = useOcclusionCulling && useTemporalCoherenceOcclusionCulling ? 1 : 0;
        updateAndRenderRunnables.add(new DelayedRunnable(() -> {
            chunkIndexes.set(chunk.index, false);
            if (DEBUG) {
                System.out.println("Deallocated per-chunk index for chunk: " + chunk);
            }
            return null;
        }, "Deallocate per-chunk index for chunk [" + chunk + "]", delayFrames));
    }

    /**
     * Get the cached or rebuilt voxel field for the chunk at position <code>(cx, cz)</code>.
     * 
     * @param cx           the x coordinate of the chunk position
     * @param cz           the z coordinate of the chunk position
     * @param throughCache whether we <i>don't</i> want to get the field from the cache or store it into
     *                     the cache
     */
    private VoxelField voxelFieldFor(int cx, int cz, boolean throughCache) {
        Vector2i pos = new Vector2i(cx, cz);
        VoxelField f;
        if (!throughCache) {
            synchronized (fieldCache) {
                f = fieldCache.get(pos);
                if (f == null) {
                    if (DEBUG) {
                        System.out.println("Creating cached voxel field for chunk at " + pos);
                    }
                    f = createVoxelField(cx, cz);
                    fieldCache.put(pos, f);
                }
            }
        } else {
            if (DEBUG) {
                System.out.println("Creating uncached voxel field for chunk at " + pos);
            }
            f = createVoxelField(cx, cz);
        }
        return f;
    }

    /**
     * Create and compile a shader object of the given type, with source from the given classpath
     * resource.
     * <p>
     * Also, add <code>#define</code>s which can be added to the code at a given location using the
     * custom <code>#pragma {{DEFINES}}</code>.
     */
    private static int createShader(String resource, int type, Map<String, String> defines) throws IOException {
        int shader = glCreateShader(type);
        try (InputStream is = VoxelGameGL.class.getClassLoader().getResourceAsStream(resource);
                InputStreamReader isr = new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr)) {
            String lines = br.lines().collect(Collectors.joining("\n"));
            lines = lines.replace("#pragma {{DEFINES}}",
                    defines.entrySet().stream().map(e -> "#define " + e.getKey() + " " + e.getValue()).collect(Collectors.joining("\n")));
            glShaderSource(shader, lines);
        }
        glCompileShader(shader);
        if (DEBUG || GLDEBUG) {
            int compiled = glGetShaderi(shader, GL_COMPILE_STATUS);
            String log = glGetShaderInfoLog(shader);
            if (log.trim().length() > 0)
                System.err.println(log);
            if (compiled == 0)
                throw new AssertionError("Could not compile shader: " + resource);
        }
        return shader;
    }

    /**
     * Create the shader program used to render the chunks.
     */
    private void createChunksProgram() throws IOException {
        Map<String, String> defines = new HashMap<>();
        defines.put("AO_FACTORS", AO_FACTORS);
        defines.put("MDI", useMultiDrawIndirect ? "1" : "0");
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/game/voxelgame/chunk" + (drawPointsWithGS ? "-points" : "") + ".vs.glsl", GL_VERTEX_SHADER, defines);
        int gshader = 0;
        if (drawPointsWithGS) {
            gshader = createShader("org/lwjgl/demo/game/voxelgame/chunk.gs.glsl", GL_GEOMETRY_SHADER, defines);
        }
        int fshader = createShader("org/lwjgl/demo/game/voxelgame/chunk.fs.glsl", GL_FRAGMENT_SHADER, defines);
        glAttachShader(program, vshader);
        if (drawPointsWithGS) {
            glAttachShader(program, gshader);
        }
        glAttachShader(program, fshader);
        glLinkProgram(program);
        glDeleteShader(vshader);
        if (drawPointsWithGS) {
            glDeleteShader(gshader);
        }
        glDeleteShader(fshader);
        if (DEBUG || GLDEBUG) {
            int linked = glGetProgrami(program, GL_LINK_STATUS);
            String programLog = glGetProgramInfoLog(program);
            if (programLog.trim().length() > 0)
                System.err.println(programLog);
            if (linked == 0)
                throw new AssertionError("Could not link program");
        }
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "materials"), 0);
        glUniform1i(glGetUniformLocation(program, "chunkInfo"), 1);
        chunksProgramUboBlockIndex = glGetUniformBlockIndex(program, "Uniforms");
        glUseProgram(0);
        chunksProgram = program;
    }

    /**
     * Create the (multi-buffered) uniform buffer object to hold uniforms needed by the chunks program.
     */
    private void createChunksProgramUbo() {
        chunksProgramUbo = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, chunksProgramUbo);
        int size = roundUpToNextMultiple(chunksProgramUboSize, uniformBufferOffsetAlignment);
        if (useBufferStorage) {
            glBufferStorage(GL_UNIFORM_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size, GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT);
            chunksProgramUboAddr = nglMapBufferRange(GL_UNIFORM_BUFFER, 0L, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size,
                    GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_FLUSH_EXPLICIT_BIT);
        } else {
            glBufferData(GL_UNIFORM_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size, GL_DYNAMIC_DRAW);
        }
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    /**
     * Create the UBO for the bounding boxes program.
     */
    private void createBoundingBoxesProgramUbo() {
        boundingBoxesProgramUbo = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, boundingBoxesProgramUbo);
        int size = roundUpToNextMultiple(boundingBoxesProgramUboSize, uniformBufferOffsetAlignment);
        if (useBufferStorage) {
            glBufferStorage(GL_UNIFORM_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size, GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT);
            boundingBoxesProgramUboAddr = nglMapBufferRange(GL_UNIFORM_BUFFER, 0L, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size,
                    GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_FLUSH_EXPLICIT_BIT);
        } else {
            glBufferData(GL_UNIFORM_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size, GL_DYNAMIC_DRAW);
        }
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    /**
     * Create the shader program used to render the selection rectangle.
     */
    private void createSelectionProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/game/voxelgame/selection.vs.glsl", GL_VERTEX_SHADER, Collections.emptyMap());
        int fshader = createShader("org/lwjgl/demo/game/voxelgame/selection.fs.glsl", GL_FRAGMENT_SHADER, Collections.emptyMap());
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        if (DEBUG || GLDEBUG) {
            int linked = glGetProgrami(program, GL_LINK_STATUS);
            String programLog = glGetProgramInfoLog(program);
            if (programLog.trim().length() > 0)
                System.err.println(programLog);
            if (linked == 0)
                throw new AssertionError("Could not link program");
        }
        glUseProgram(program);
        selectionProgramUboBlockIndex = glGetUniformBlockIndex(program, "Uniforms");
        glUseProgram(0);
        selectionProgram = program;
    }

    /**
     * Create buffer objects for occlusion culling, such as:
     * <ul>
     * <li>SSBO as a visibility buffer to store a flag whether a given chunk's bounding box is visible
     * or not
     * <li>Atomic counter buffer for collecting MDI structs for visible chunks
     * <li>SSBO to hold the final MDI structs for drawing the scene
     * <li>if temporal coherence occlusion culling: Another SSBO to hold MDI structs for drawing newly
     * disoccluded chunks
     * </ul>
     */
    private void createOcclusionCullingBufferObjects() {
        /*
         * We need a "visibility flags" buffer to remember which of the in-frustum chunks are visible in any
         * frame.
         */
        visibilityFlagsBuffer = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, visibilityFlagsBuffer);
        /* Allocate buffer storage and pre-initialize with zeroes. */
        if (useBufferStorage) {
            /*
             * If we can't use ARB_clear_buffer_object, then it must be a dynamic storage buffer for client-side
             * uploads to clear the buffer.
             */
            glBufferStorage(GL_SHADER_STORAGE_BUFFER, DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * Integer.BYTES,
                    useClearBuffer ? 0 : GL_DYNAMIC_STORAGE_BIT);
        } else {
            glBufferData(GL_SHADER_STORAGE_BUFFER, DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * Integer.BYTES, GL_DYNAMIC_DRAW);
        }
        /* We need an atomic counter (to collect MDI draw structs for visible chunks) */
        atomicCounterBuffer = glGenBuffers();
        glBindBuffer(GL_ATOMIC_COUNTER_BUFFER, atomicCounterBuffer);
        /*
         * If we use temporal coherence occlusion culling, we need twice as many atomics, because we need
         * one to count the MDI draws for all non-occluded in-frustum chunks per frame as well as one for
         * newly disoccluded chunks per frame.
         */
        long multiplier = useTemporalCoherenceOcclusionCulling ? 2 : 1;
        int atomicCounterBufferIntSize = (int) (multiplier * DYNAMIC_BUFFER_OBJECT_REGIONS);
        try (MemoryStack stack = stackPush()) {
            /* Allocate buffer storage and pre-initialize with zeroes. */
            if (useBufferStorage) {
                /*
                 * If we can't use ARB_clear_buffer_object, then it must be a dynamic storage buffer for client-side
                 * uploads.
                 */
                glBufferStorage(GL_ATOMIC_COUNTER_BUFFER, stack.callocInt(atomicCounterBufferIntSize), useClearBuffer ? 0 : GL_DYNAMIC_STORAGE_BIT);
            } else {
                glBufferData(GL_ATOMIC_COUNTER_BUFFER, stack.callocInt(atomicCounterBufferIntSize), GL_DYNAMIC_DRAW);
            }
        }
        /* and we need an "indirect" buffer to store the MDI draw structs into */
        indirectDrawCulledBuffer = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, indirectDrawCulledBuffer);
        /*
         * If we use temporal coherence occlusion culling, we need twice the buffer size for MDI draw
         * structs, because we need a region to hold the MDI draws for all non-occluded chunks per frame as
         * well as one for newly disoccluded chunks per frame.
         */
        long indirectDrawCulledBufferSize = multiplier * DYNAMIC_BUFFER_OBJECT_REGIONS * MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES;
        if (useBufferStorage) {
            glBufferStorage(GL_SHADER_STORAGE_BUFFER, indirectDrawCulledBufferSize, 0);
        } else {
            glBufferData(GL_SHADER_STORAGE_BUFFER, indirectDrawCulledBufferSize, GL_DYNAMIC_DRAW);
        }
    }

    /**
     * Create a program used to draw chunks' bounding boxes by expanding points to cubes in a geometry
     * shader.
     */
    private void createBoundingBoxesProgram() throws IOException {
        Map<String, String> defines = new HashMap<>();
        /* The geometry shader needs to know the chunk size to scale the base axes vectors */
        defines.put("CHUNK_SIZE", Integer.toString(CHUNK_SIZE));
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/game/voxelgame/boundingboxes.vs.glsl", GL_VERTEX_SHADER, defines);
        int gshader = createShader("org/lwjgl/demo/game/voxelgame/boundingboxes.gs.glsl", GL_GEOMETRY_SHADER, defines);
        int fshader = createShader("org/lwjgl/demo/game/voxelgame/boundingboxes.fs.glsl", GL_FRAGMENT_SHADER, defines);
        glAttachShader(program, vshader);
        glAttachShader(program, gshader);
        glAttachShader(program, fshader);
        glLinkProgram(program);
        glDeleteShader(vshader);
        glDeleteShader(gshader);
        glDeleteShader(fshader);
        if (DEBUG || GLDEBUG) {
            int linked = glGetProgrami(program, GL_LINK_STATUS);
            String programLog = glGetProgramInfoLog(program);
            if (programLog.trim().length() > 0)
                System.err.println(programLog);
            if (linked == 0)
                throw new AssertionError("Could not link program");
        }
        glUseProgram(program);
        boundingBoxesProgramUboBlockIndex = glGetUniformBlockIndex(program, "Uniforms");
        glUseProgram(0);
        boundingBoxesProgram = program;
    }

    /**
     * Create the vertex-shader-only program to generate MDI calls into an output SSBO from all chunks'
     * MDI calls in an input SSBO depending on the visibility flag generated by the bounding boxes draw.
     */
    private void createCollectDrawCallsProgram() throws IOException {
        Map<String, String> defines = new HashMap<>();
        defines.put("VERTICES_PER_FACE", verticesPerFace + "u");
        defines.put("INDICES_PER_FACE", indicesPerFace + "u");
        defines.put("TEMPORAL_COHERENCE", useTemporalCoherenceOcclusionCulling ? "1" : "0");
        int program = glCreateProgram();
        int vshader = createShader("org/lwjgl/demo/game/voxelgame/collectdrawcalls.vs.glsl", GL_VERTEX_SHADER, defines);
        glAttachShader(program, vshader);
        glLinkProgram(program);
        glDeleteShader(vshader);
        if (DEBUG || GLDEBUG) {
            int linked = glGetProgrami(program, GL_LINK_STATUS);
            String programLog = glGetProgramInfoLog(program);
            if (programLog.trim().length() > 0)
                System.err.println(programLog);
            if (linked == 0)
                throw new AssertionError("Could not link program");
        }
        collectDrawCallsProgram = program;
    }

    /**
     * Round the <em>positive</em> number <code>num</code> up to be a multiple of <code>factor</code>.
     */
    private static int roundUpToNextMultiple(int num, int factor) {
        return num + factor - 1 - (num + factor - 1) % factor;
    }

    /**
     * Create the (multi-buffered) uniform buffer object to hold uniforms needed by the selection
     * program.
     */
    private void createSelectionProgramUbo() {
        selectionProgramUbo = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, selectionProgramUbo);
        int size = roundUpToNextMultiple(selectionProgramUboSize, uniformBufferOffsetAlignment);
        if (useBufferStorage) {
            glBufferStorage(GL_UNIFORM_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size, GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT);
            selectionProgramUboAddr = nglMapBufferRange(GL_UNIFORM_BUFFER, 0L, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size,
                    GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_FLUSH_EXPLICIT_BIT);
        } else {
            glBufferData(GL_UNIFORM_BUFFER, (long) DYNAMIC_BUFFER_OBJECT_REGIONS * size, GL_DYNAMIC_DRAW);
        }
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    private static boolean isPositiveSide(int side) {
        return (side & 1) != 0;
    }

    /**
     * Write vertex/index data for the given face.
     * <p>
     * A face will either be triangulated for GL_TRIANGLE_STRIPS or written as a single point for
     * GL_POINTS rendering.
     */
    public void appendFaceVertexAndIndexData(Chunk chunk, int fi, int u0, int v0, int u1, int v1, int p, int s, int v, DynamicByteBuffer vertexData,
            DynamicByteBuffer indices) {
        int n00 = v >>> 8 & 7, n10 = v >>> 11 & 7;
        int n01 = v >>> 14 & 7, n11 = v >>> 17 & 7;
        switch (s >>> 1) {
        case 0:
            if (drawPointsWithGS) {
                fillPositionTypeSideAndAoFactorsX(p, u0, v0, u1, v1, s, n00, n10, n01, n11, v, vertexData);
            } else {
                fillPositionsTypesSideAndAoFactorsX(chunk.index, p, u0, v0, u1, v1, s, n00, n10, n01, n11, v, vertexData);
            }
            break;
        case 1:
            if (drawPointsWithGS) {
                fillPositionTypeSideAndAoFactorsY(p, u0, v0, u1, v1, s, n00, n10, n01, n11, v, vertexData);
            } else {
                fillPositionsTypesSideAndAoFactorsY(chunk.index, p, u0, v0, u1, v1, s, n00, n10, n01, n11, v, vertexData);
            }
            break;
        case 2:
            if (drawPointsWithGS) {
                fillPositionTypeSideAndAoFactorsZ(p, u0, v0, u1, v1, s, n00, n10, n01, n11, v, vertexData);
            } else {
                fillPositionsTypesSideAndAoFactorsZ(chunk.index, p, u0, v0, u1, v1, s, n00, n10, n01, n11, v, vertexData);
            }
            break;
        }
        if (drawPointsWithGS) {
            fillIndex(fi, indices);
        } else {
            fillIndices(s, fi, indices);
        }
    }

    /**
     * Writes indices of the face at index <code>i</code> for TRIANGLE_STRIP rendering.
     * <p>
     * This will also write a {@link #PRIMITIVE_RESTART_INDEX} token.
     * 
     * @param s       the side of the face
     * @param i       the index of the face
     * @param indices will receive the indices for TRIANGLE_STRIP rendering
     */
    private static void fillIndices(int s, int i, DynamicByteBuffer indices) {
        if (isPositiveSide(s))
            indices.putInt((i << 2) + 1 | (i << 2) + 3 << 16).putInt(i << 2 | (i << 2) + 2 << 16).putShort(PRIMITIVE_RESTART_INDEX);
        else
            indices.putInt((i << 2) + 2 | (i << 2) + 3 << 16).putInt(i << 2 | (i << 2) + 1 << 16).putShort(PRIMITIVE_RESTART_INDEX);
    }

    /**
     * Write a single short for the given index (when drawing faces as points).
     */
    private static void fillIndex(int i, DynamicByteBuffer indices) {
        indices.putShort(i);
    }

    /**
     * Encode the four ambient occlusion factors into a single byte.
     */
    private static byte aoFactors(int n00, int n10, int n01, int n11) {
        return (byte) (aoFactor(n00) | aoFactor(n10) << 2 | aoFactor(n01) << 4 | aoFactor(n11) << 6);
    }

    /**
     * Compute the ambient occlusion factor from a vertex's neighbor configuration <code>n</code>.
     */
    private static int aoFactor(int n) {
        return (n & 1) == 1 && (n & 4) == 4 ? 0 : 3 - Integer.bitCount(n);
    }

    /**
     * Write the face position, extents, type, side and ambient occlusion factors for GL_POINTS
     * rendering of an X face.
     * <p>
     * Since the Y coordinate can have values from 0-256, we will reserve 8 bits for Y (which is always
     * encoded first) and 5 bits (for 0-31) for X and Z.
     */
    private static void fillPositionTypeSideAndAoFactorsX(int p, int u0, int v0, int u1, int v1, int s, int n00, int n10, int n01, int n11, int v,
            DynamicByteBuffer vertexData) {
        vertexData.putInt(u0 | p << 8 | v0 << 14 | (u1 - u0 - 1) << 20 | (v1 - v0 - 1) << 25).putInt((byte) v | s << 8 | aoFactors(n00, n10, n01, n11) << 16);
    }

    /**
     * Write the face position, extents, type, side and ambient occlusion factors for GL_POINTS
     * rendering of an Y face.
     * <p>
     * Since the Y coordinate can have values from 0-256, we will reserve 8 bits for Y (which is always
     * encoded first) and 5 bits (for 0-31) for X and Z.
     */
    private static void fillPositionTypeSideAndAoFactorsY(int p, int u0, int v0, int u1, int v1, int s, int n00, int n10, int n01, int n11, int v,
            DynamicByteBuffer vertexData) {
        vertexData.putInt(p | v0 << 8 | u0 << 14 | (u1 - u0 - 1) << 20 | (v1 - v0 - 1) << 25).putInt((byte) v | s << 8 | aoFactors(n00, n10, n01, n11) << 16);
    }

    /**
     * Write the face position, extents, type, side and ambient occlusion factors for GL_POINTS
     * rendering of a Z face.
     * <p>
     * Since the Y coordinate can have values from 0-256, we will reserve 8 bits for Y (which is always
     * encoded first) and 5 bits (for 0-31) for X and Z.
     */
    private static void fillPositionTypeSideAndAoFactorsZ(int p, int u0, int v0, int u1, int v1, int s, int n00, int n10, int n01, int n11, int v,
            DynamicByteBuffer vertexData) {
        vertexData.putInt(v0 | u0 << 8 | p << 14 | (u1 - u0 - 1) << 20 | (v1 - v0 - 1) << 25).putInt((byte) v | s << 8 | aoFactors(n00, n10, n01, n11) << 16);
    }

    private void fillPositionsTypesSideAndAoFactorsZ(int idx, int p, int u0, int v0, int u1, int v1, int s, int n00, int n10, int n01, int n11, int v,
            DynamicByteBuffer vertexData) {
        int sideAndAoFactors = s | aoFactors(n00, n10, n01, n11) << 8;
        vertexData.putInt(u0 | v0 << 8 | p << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(u1 | v0 << 8 | p << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(u0 | v1 << 8 | p << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(u1 | v1 << 8 | p << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
    }

    private void fillPositionsTypesSideAndAoFactorsY(int idx, int p, int u0, int v0, int u1, int v1, int s, int n00, int n10, int n01, int n11, int v,
            DynamicByteBuffer vertexData) {
        int sideAndAoFactors = s | aoFactors(n00, n10, n01, n11) << 8;
        vertexData.putInt(v0 | p << 8 | u0 << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(v0 | p << 8 | u1 << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(v1 | p << 8 | u0 << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(v1 | p << 8 | u1 << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
    }

    private void fillPositionsTypesSideAndAoFactorsX(int idx, int p, int u0, int v0, int u1, int v1, int s, int n00, int n10, int n01, int n11, int v,
            DynamicByteBuffer vertexData) {
        int sideAndAoFactors = s | aoFactors(n00, n10, n01, n11) << 8;
        vertexData.putInt(p | u0 << 8 | v0 << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(p | u1 << 8 | v0 << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(p | u0 << 8 | v1 << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
        vertexData.putInt(p | u1 << 8 | v1 << 16 | (byte) v << 24).putShort(sideAndAoFactors);
        if (!useMultiDrawIndirect)
            vertexData.putInt(idx);
    }

    /**
     * Allocate a free buffer region with enough space to hold all buffer data for the given number of
     * voxel faces.
     */
    private FreeListAllocator.Region allocatePerFaceBufferRegion(int faceCount) {
      return allocator.allocate(faceCount);
    }

    /**
     * Update the chunk's buffer objects with the given voxel field.
     */
    private void updateChunk(Chunk c, VoxelField f) {
        activeFaceCount -= c.r.len;
        deallocatePerFaceBufferRegion(c);
        meshChunkFacesAndWriteToBuffers(c, f);
    }

    /**
     * Enlarge the per-face buffer objects and build a VAO for it.
     */
    private void enlargePerFaceBuffers(int perFaceBufferCapacity, int newPerFaceBufferCapacity) {
        int vao;
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vertexDataBufferObject;
        long vertexDataBufferSize = (long) voxelVertexSize * verticesPerFace * newPerFaceBufferCapacity;
        /* Create the new vertex buffer */
        if (useBufferStorage) {
            vertexDataBufferObject = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vertexDataBufferObject);
            glBufferStorage(GL_ARRAY_BUFFER, vertexDataBufferSize, GL_DYNAMIC_STORAGE_BIT);
        } else {
            vertexDataBufferObject = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vertexDataBufferObject);
            glBufferData(GL_ARRAY_BUFFER, vertexDataBufferSize, GL_STATIC_DRAW);
        }
        /* Setup the vertex specifications */
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glVertexAttribIPointer(0, drawPointsWithGS ? 1 : 4, drawPointsWithGS ? GL_UNSIGNED_INT : GL_UNSIGNED_BYTE, voxelVertexSize, 0L);
        glVertexAttribIPointer(1, drawPointsWithGS ? 4 : 2, GL_UNSIGNED_BYTE, voxelVertexSize, 4L);
        if (useMultiDrawIndirect) {
            glBindBuffer(GL_ARRAY_BUFFER, chunkInfoBufferObject);
            glVertexAttribIPointer(2, 4, GL_INT, 0, 0L);
            glVertexAttribDivisor(2, 1);
        } else {
            glVertexAttribIPointer(2, 1, GL_UNSIGNED_INT, voxelVertexSize, drawPointsWithGS ? 8L : 6L);
        }
        /* Setup the index buffer */
        long indexBufferSize = (long) Short.BYTES * indicesPerFace * newPerFaceBufferCapacity;
        int indexBufferObject;
        indexBufferObject = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBufferObject);
        if (useBufferStorage) {
            glBufferStorage(GL_ELEMENT_ARRAY_BUFFER, indexBufferSize, GL_DYNAMIC_STORAGE_BIT);
        } else {
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBufferSize, GL_STATIC_DRAW);
        }
        if (chunksVao != 0) {
            if (DEBUG) {
                System.out.println("Copying old buffer objects [" + perFaceBufferCapacity + "] to new");
            }
            /* Copy old buffer objects to new buffer objects */
            glBindBuffer(GL_COPY_READ_BUFFER, this.vertexDataBufferObject);
            glBindBuffer(GL_COPY_WRITE_BUFFER, vertexDataBufferObject);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, (long) voxelVertexSize * perFaceBufferCapacity * verticesPerFace);
            glBindBuffer(GL_COPY_READ_BUFFER, this.indexBufferObject);
            glBindBuffer(GL_COPY_WRITE_BUFFER, indexBufferObject);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, (long) Short.BYTES * perFaceBufferCapacity * indicesPerFace);
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
            /* Delete old buffers */
            glDeleteBuffers(new int[] { this.vertexDataBufferObject, this.indexBufferObject });
            glDeleteVertexArrays(chunksVao);
        }
        /* Remember new vao and buffer objects */
        this.vertexDataBufferObject = vertexDataBufferObject;
        this.indexBufferObject = indexBufferObject;
        this.chunksVao = vao;
        perFaceBufferCapacity = newPerFaceBufferCapacity;
        if (DEBUG) {
            System.out.println("Total size of face buffers: "
                    + INT_FORMATTER.format(newPerFaceBufferCapacity * ((4L + 2) * verticesPerFace + (long) Short.BYTES * indicesPerFace) / 1024 / 1024)
                    + " MB");
        }
    }

    /**
     * When a chunk got destroyed, then this method is called to deallocate that chunk's per-face buffer
     * region.
     * <p>
     * Care must be taken when we use temporal coherence occlusion culling: We do not
     * <em>immediately</em> want to mark the buffer region as free and allocate a new chunk to it when
     * we still want to render the last frame's chunks with the MDI structs already recorded in the
     * indirect draw buffer with their respective offsets/sizes.
     * <p>
     * Instead, we delay marking buffer regions as free for one frame.
     */
    private void deallocatePerFaceBufferRegion(Chunk chunk) {
        int chunkFaceOffset = chunk.r.off;
        int chunkFaceCount = chunk.r.len;
        /*
         * If we use temporal coherence occlusion culling, we must delay deallocating the buffer region by 1
         * frame, because the next frame still wants to potentially draw the chunk when it was visible last
         * frame, so we must not allocate this region to another chunk immediately.
         */
        int delayFrames = useOcclusionCulling && useTemporalCoherenceOcclusionCulling ? 1 : 0;
        updateAndRenderRunnables.add(new DelayedRunnable(() -> {
            if (DEBUG) {
                System.out.println("Deallocate buffer region for chunk: " + chunk);
            }
            allocator.free(new FreeListAllocator.Region(chunkFaceOffset, chunkFaceCount));
            return null;
        }, "Deallocate buffer region for chunk " + chunk, delayFrames));
    }

    /**
     * Mesh the given chunk and schedule writing the faces into buffer objects.
     * 
     * @return the point in monotonic time when the meshing and appending to vertex/index data byte
     *         buffers completed
     */
    private long meshChunkFacesAndWriteToBuffers(Chunk chunk, VoxelField vf) {
        DynamicByteBuffer vertexData = new DynamicByteBuffer(vf.num / 4);
        DynamicByteBuffer indices = new DynamicByteBuffer(vf.num / 4);
        int faceCount = new GreedyMeshing(vf.ny, vf.py, CHUNK_SIZE, CHUNK_SIZE).mesh(vf.field, new FaceConsumer() {
            private int i;

            public void consume(int u0, int v0, int u1, int v1, int p, int s, int v) {
                appendFaceVertexAndIndexData(chunk, i++, u0, v0, u1, v1, p, s, v, vertexData, indices);
            }
        });
        FreeListAllocator.Region r = allocatePerFaceBufferRegion(faceCount);
        long time = System.nanoTime();

        /* Issue render thread task to update the buffer objects */
        updateAndRenderRunnables.add(new DelayedRunnable(() -> {
            chunk.minY = vf.ny;
            chunk.maxY = vf.py;
            chunk.r = r;
            activeFaceCount += chunk.r.len;
            updateChunkVertexAndIndexDataInBufferObjects(chunk, vertexData, indices);
            vertexData.free();
            indices.free();
            return null;
        }, "Update chunk vertex data", 0));
        return time;
    }

    /**
     * Update the chunk's per-face buffer region with the given vertex and index data.
     */
    private void updateChunkVertexAndIndexDataInBufferObjects(Chunk chunk, DynamicByteBuffer vertexData, DynamicByteBuffer indices) {
        long vertexOffset = (long) chunk.r.off * voxelVertexSize * verticesPerFace;
        glBindBuffer(GL_ARRAY_BUFFER, vertexDataBufferObject);
        nglBufferSubData(GL_ARRAY_BUFFER, vertexOffset, vertexData.pos, vertexData.addr);
        updateChunkInfo(chunk);
        long indexOffset = (long) chunk.r.off * Short.BYTES * indicesPerFace;
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBufferObject);
        nglBufferSubData(GL_ELEMENT_ARRAY_BUFFER, indexOffset, indices.pos, indices.addr);
        if (DEBUG) {
            System.out.println("Number of chunks: " + INT_FORMATTER.format(allChunks.size()) + " ("
                    + INT_FORMATTER.format(computePerFaceBufferObjectSize() / 1024 / 1024) + " MB)");
            System.out.println("Number of faces:  " + INT_FORMATTER.format(activeFaceCount));
        }
        chunkBuildTasksCount.decrementAndGet();
        chunk.ready = true;
    }

    /**
     * Update the chunk info buffer with the (new) minY/maxY of the chunk, or write the initial data for
     * a newly created chunk.
     */
    private void updateChunkInfo(Chunk chunk) {
        try (MemoryStack stack = stackPush()) {
            int bindTarget = useMultiDrawIndirect ? GL_ARRAY_BUFFER : GL_TEXTURE_BUFFER;
            glBindBuffer(bindTarget, chunkInfoBufferObject);
            IntBuffer data = stack.mallocInt(4);
            data.put(0, chunk.cx << CHUNK_SIZE_SHIFT).put(1, chunk.minY | chunk.maxY << 16).put(2, chunk.cz << CHUNK_SIZE_SHIFT).put(3, chunk.index);
            glBufferSubData(bindTarget, (long) chunk.index * 4 * Integer.BYTES, data);
        }
    }

    private void createMaterialsTexture() {
        int materialsBufferObject = glGenBuffers();
        try (MemoryStack stack = stackPush()) {
            long materialsBuffer = stack.nmalloc(Integer.BYTES * materials.length);
            for (int i = 0; i < materials.length; i++) {
                Material mat = materials[i];
                memPutInt(materialsBuffer + i * Integer.BYTES, mat == null ? 0 : mat.col);
            }
            glBindBuffer(GL_TEXTURE_BUFFER, materialsBufferObject);
            if (useBufferStorage) {
                nglBufferStorage(GL_TEXTURE_BUFFER, materials.length * Integer.BYTES, materialsBuffer, 0);
            } else {
                nglBufferData(GL_TEXTURE_BUFFER, materials.length * Integer.BYTES, materialsBuffer, GL_STATIC_DRAW);
            }
        }
        glBindBuffer(GL_TEXTURE_BUFFER, 0);
        materialsTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, materialsTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA8, materialsBufferObject);
        glBindTexture(GL_TEXTURE_BUFFER, 0);
    }

    private void handleKeyboardInput() {
        float factor = fly ? 20f : 5f;
        playerVelocity.x = 0f;
        if (fly) {
            playerVelocity.y = 0f;
        }
        playerVelocity.z = 0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = fly ? 180f : 20f;
        if (keydown[GLFW_KEY_W])
            playerVelocity.sub(vMat.positiveZ(tmpv3f).mul(factor, fly ? factor : 0, factor));
        if (keydown[GLFW_KEY_S])
            playerVelocity.add(vMat.positiveZ(tmpv3f).mul(factor, fly ? factor : 0, factor));
        if (keydown[GLFW_KEY_A])
            playerVelocity.sub(vMat.positiveX(tmpv3f).mul(factor, fly ? factor : 0, factor));
        if (keydown[GLFW_KEY_D])
            playerVelocity.add(vMat.positiveX(tmpv3f).mul(factor, fly ? factor : 0, factor));
        if (keydown[GLFW_KEY_SPACE] && fly)
            playerVelocity.add(vMat.positiveY(tmpv3f).mul(fly ? factor : 1));
        if (keydown[GLFW_KEY_LEFT_CONTROL] && fly)
            playerVelocity.sub(vMat.positiveY(tmpv3f).mul(fly ? factor : 0));
        if (!fly && keydown[GLFW_KEY_SPACE] && !jumping) {
            jumping = true;
            playerVelocity.add(0, 13, 0);
        } else if (!keydown[GLFW_KEY_SPACE]) {
            jumping = false;
        }
    }

    private void updatePlayerPositionAndMatrices(float dt) {
        handleKeyboardInput();
        angx += dangx * 0.002f;
        angy += dangy * 0.002f;
        dangx *= 0.0994f;
        dangy *= 0.0994f;
        if (!fly) {
            playerVelocity.add(playerAcceleration.mul(dt, tmpv3f));
            handleCollisions(dt, playerVelocity, playerPosition);
        } else {
            playerPosition.add(playerVelocity.mul(dt, tmpv3f));
        }
        vMat.rotation(tmpq.rotationX(angx).rotateY(angy));
        vMat.translate((float) -(playerPosition.x - floor(playerPosition.x)), (float) -(playerPosition.y - floor(playerPosition.y)),
                (float) -(playerPosition.z - floor(playerPosition.z)));
        pMat.setPerspective((float) toRadians(FOV_DEGREES), (float) width / height, useInverseDepth ? FAR : NEAR, useInverseDepth ? NEAR : FAR,
                useInverseDepth);
        pMat.mulPerspectiveAffine(vMat, mvpMat);
        mvpMat.invert(imvpMat);
        updateFrustumPlanes();
    }

    /**
     * Update the plane equation coefficients for the frustum planes from the {@link #mvpMat}.
     */
    private void updateFrustumPlanes() {
        Matrix4f m = mvpMat;
        nxX = m.m03() + m.m00();
        nxY = m.m13() + m.m10();
        nxZ = m.m23() + m.m20();
        nxW = m.m33() + m.m30();
        pxX = m.m03() - m.m00();
        pxY = m.m13() - m.m10();
        pxZ = m.m23() - m.m20();
        pxW = m.m33() - m.m30();
        nyX = m.m03() + m.m01();
        nyY = m.m13() + m.m11();
        nyZ = m.m23() + m.m21();
        nyW = m.m33() + m.m31();
        pyX = m.m03() - m.m01();
        pyY = m.m13() - m.m11();
        pyZ = m.m23() - m.m21();
        pyW = m.m33() - m.m31();
    }

    /**
     * Determine whether the given chunk does <i>not</i> intersect the view frustum.
     */
    private boolean chunkNotInFrustum(Chunk chunk) {
        float xf = (chunk.cx << CHUNK_SIZE_SHIFT) - (float) floor(playerPosition.x);
        float ymin = chunk.minY - (float) floor(playerPosition.y), ymax = chunk.maxY - (float) floor(playerPosition.y);
        float zf = (chunk.cz << CHUNK_SIZE_SHIFT) - (float) floor(playerPosition.z);
        return culledXY(xf, ymin, zf, xf + CHUNK_SIZE, ymax + 1, zf + CHUNK_SIZE);
    }

    /**
     * Test whether the box <code>(minX, minY, minZ)</code> - <code>(maxX, maxY, maxZ)</code> is culled
     * by either of the four X, Y planes of the current view frustum.
     * <p>
     * We don't test for near/far planes.
     */
    private boolean culledXY(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return nxX * (nxX < 0 ? minX : maxX) + nxY * (nxY < 0 ? minY : maxY) + nxZ * (nxZ < 0 ? minZ : maxZ) < -nxW
                || pxX * (pxX < 0 ? minX : maxX) + pxY * (pxY < 0 ? minY : maxY) + pxZ * (pxZ < 0 ? minZ : maxZ) < -pxW
                || nyX * (nyX < 0 ? minX : maxX) + nyY * (nyY < 0 ? minY : maxY) + nyZ * (nyZ < 0 ? minZ : maxZ) < -nyW
                || pyX * (pyX < 0 ? minX : maxX) + pyY * (pyY < 0 ? minY : maxY) + pyZ * (pyZ < 0 ? minZ : maxZ) < -pyW;
    }

    /**
     * Determine whether the player's eye is currently inside the given chunk.
     */
    private boolean playerInsideChunk(Chunk chunk) {
        float margin = CHUNK_SIZE * 0.5f;
        int minX = chunk.cx << CHUNK_SIZE_SHIFT, maxX = minX + CHUNK_SIZE;
        int minZ = chunk.cz << CHUNK_SIZE_SHIFT, maxZ = minZ + CHUNK_SIZE;
        return playerPosition.x + margin >= minX && playerPosition.x - margin <= maxX && playerPosition.z + margin >= minZ && playerPosition.z - margin <= maxZ;
    }

    /**
     * Create new visible chunks and destroy chunks that are outside of render distance.
     * 
     * @return <code>true</code> if this method caused chunks to be created and whether the called
     *         should call this method again
     */
    private boolean createInRenderDistanceAndDestroyOutOfRenderDistanceChunks() {
        /* First, check for chunks to delete */
        destroyOutOfRenderDistanceFrontierChunks();
        /* Then, create tasks for new chunks to be created */
        return createNewInRenderDistanceFrontierChunks();
    }

    /**
     * Based on the current frontier chunks, check if any of their four neighbors is within the
     * {@link #MAX_RENDER_DISTANCE_CHUNKS} and need to be created.
     * 
     * @return <code>true</code> if any new chunks have been created; <code>false</code> otherwise
     */
    private boolean createNewInRenderDistanceFrontierChunks() {
        /* Then check for new chunks to generate */
        frontierChunks.sort(inViewAndDistance);
        for (int i = 0, frontierChunksSize = frontierChunks.size(); i < frontierChunksSize; i++) {
            /* iterate index-based because we modify the list by appending new elements at the end! */
            Chunk c = frontierChunks.get(i);
            if (chunkBuildTasksCount.get() >= MAX_NUMBER_OF_CHUNK_TASKS) {
                break;
            }
            if (ensureChunkIfVisible(c.cx - 1, c.cz) || ensureChunkIfVisible(c.cx + 1, c.cz) || ensureChunkIfVisible(c.cx, c.cz - 1)
                    || ensureChunkIfVisible(c.cx, c.cz + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Iterate through all current frontier chunks and check, whether any of them is further than the
     * {@link #MAX_RENDER_DISTANCE_CHUNKS} aways, in which case those will be destroyed.
     */
    private void destroyOutOfRenderDistanceFrontierChunks() {
        for (int i = 0, s = frontierChunks.size(); i < s; i++) {
            /*
             * iterate index-based because we modify the list by removing the i-th element and optionally append
             * new elements at the end!
             */
            Chunk c = frontierChunks.get(i);
            if (chunkInRenderDistance(c.cx, c.cz))
                continue;
            if (DEBUG) {
                System.out.println("Frontier chunk is not in view anymore: " + c);
            }
            s += destroyChunk(c);
            i--;
        }
    }

    /**
     * Ensure that a frontier neighbor chunk is created if it is visible.
     * 
     * @param x the x offset to the frontier chunk
     * @param z the z offset to the frontier chunk
     * @return true if a new chunk was generated
     */
    private boolean ensureChunkIfVisible(int x, int z) {
        if (!chunkInRenderDistance(x, z))
            return false;
        Chunk chunk;
        if ((chunk = ensureChunk(x, z)) != null) {
            if (DEBUG) {
                System.out.println("New frontier neighbor chunk is in view: " + chunk);
            }
            return true;
        }
        return false;
    }

    /**
     * Determine whether the chunk at <code>(x, z)</code> is within render distance.
     */
    private boolean chunkInRenderDistance(int x, int z) {
        return distToChunk(x, z) < MAX_RENDER_DISTANCE_METERS * MAX_RENDER_DISTANCE_METERS;
    }

    /**
     * Compute the distance from the player's position to the center of the chunk at
     * <code>(cx, cz)</code>.
     */
    private double distToChunk(int cx, int cz) {
        double dx = playerPosition.x - (cx + 0.5) * CHUNK_SIZE;
        double dz = playerPosition.z - (cz + 0.5) * CHUNK_SIZE;
        return dx * dx + dz * dz;
    }

    /**
     * Handle any collisions with the player and the voxels.
     */
    private void handleCollisions(float dt, Vector3f v, Vector3d p) {
        List<Contact> contacts = new ArrayList<>();
        collisionDetection(dt, v, contacts);
        collisionResponse(dt, v, p, contacts);
    }

    /**
     * Detect possible collision candidates.
     */
    private void collisionDetection(float dt, Vector3f v, List<Contact> contacts) {
        float dx = v.x * dt, dy = v.y * dt, dz = v.z * dt;
        int minX = (int) floor(playerPosition.x - PLAYER_WIDTH + (dx < 0 ? dx : 0));
        int maxX = (int) floor(playerPosition.x + PLAYER_WIDTH + (dx > 0 ? dx : 0));
        int minY = (int) floor(playerPosition.y - PLAYER_EYE_HEIGHT + (dy < 0 ? dy : 0));
        int maxY = (int) floor(playerPosition.y + PLAYER_HEIGHT - PLAYER_EYE_HEIGHT + (dy > 0 ? dy : 0));
        int minZ = (int) floor(playerPosition.z - PLAYER_WIDTH + (dz < 0 ? dz : 0));
        int maxZ = (int) floor(playerPosition.z + PLAYER_WIDTH + (dz > 0 ? dz : 0));
        /* Just loop over all voxels that could possibly collide with the player */
        for (int y = min(CHUNK_HEIGHT - 1, maxY); y >= 0 && y >= minY; y--) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (load(x, y, z) == EMPTY_VOXEL)
                        continue;
                    /* and perform swept-aabb intersection */
                    intersectSweptAabbAabb(x, y, z, (float) (playerPosition.x - x), (float) (playerPosition.y - y), (float) (playerPosition.z - z), dx, dy, dz,
                            contacts);
                }
            }
        }
    }

    /**
     * Compute the exact collision point between the player and the voxel at <code>(x, y, z)</code>.
     */
    private void intersectSweptAabbAabb(int x, int y, int z, float px, float py, float pz, float dx, float dy, float dz, List<Contact> contacts) {
        /*
         * https://www.gamedev.net/tutorials/programming/general-and-gameplay-programming/swept-aabb-
         * collision-detection-and-response-r3084/
         */
        float pxmax = px + PLAYER_WIDTH, pxmin = px - PLAYER_WIDTH, pymax = py + PLAYER_HEIGHT - PLAYER_EYE_HEIGHT, pymin = py - PLAYER_EYE_HEIGHT,
                pzmax = pz + PLAYER_WIDTH, pzmin = pz - PLAYER_WIDTH;
        float xInvEntry = dx > 0f ? -pxmax : 1 - pxmin, xInvExit = dx > 0f ? 1 - pxmin : -pxmax;
        boolean xNotValid = dx == 0 || load(x + (dx > 0 ? -1 : 1), y, z) != EMPTY_VOXEL;
        float xEntry = xNotValid ? NEGATIVE_INFINITY : xInvEntry / dx, xExit = xNotValid ? POSITIVE_INFINITY : xInvExit / dx;
        float yInvEntry = dy > 0f ? -pymax : 1 - pymin, yInvExit = dy > 0f ? 1 - pymin : -pymax;
        boolean yNotValid = dy == 0 || load(x, y + (dy > 0 ? -1 : 1), z) != EMPTY_VOXEL;
        float yEntry = yNotValid ? NEGATIVE_INFINITY : yInvEntry / dy, yExit = yNotValid ? POSITIVE_INFINITY : yInvExit / dy;
        float zInvEntry = dz > 0f ? -pzmax : 1 - pzmin, zInvExit = dz > 0f ? 1 - pzmin : -pzmax;
        boolean zNotValid = dz == 0 || load(x, y, z + (dz > 0 ? -1 : 1)) != EMPTY_VOXEL;
        float zEntry = zNotValid ? NEGATIVE_INFINITY : zInvEntry / dz, zExit = zNotValid ? POSITIVE_INFINITY : zInvExit / dz;
        float tEntry = max(max(xEntry, yEntry), zEntry), tExit = min(min(xExit, yExit), zExit);
        if (tEntry < -.5f || tEntry > tExit) {
            return;
        }
        Contact c;
        contacts.add(c = new Contact(tEntry, x, y, z));
        if (xEntry == tEntry) {
            c.nx = dx > 0 ? -1 : 1;
        } else if (yEntry == tEntry) {
            c.ny = dy > 0 ? -1 : 1;
        } else {
            c.nz = dz > 0 ? -1 : 1;
        }
    }

    /**
     * Respond to all found collision contacts.
     */
    private void collisionResponse(float dt, Vector3f v, Vector3d p, List<Contact> contacts) {
        sort(contacts);
        int minX = Integer.MIN_VALUE, maxX = Integer.MAX_VALUE, minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE, minZ = Integer.MIN_VALUE,
                maxZ = Integer.MAX_VALUE;
        float elapsedTime = 0f;
        float dx = v.x * dt, dy = v.y * dt, dz = v.z * dt;
        for (int i = 0; i < contacts.size(); i++) {
            Contact contact = contacts.get(i);
            if (contact.x <= minX || contact.y <= minY || contact.z <= minZ || contact.x >= maxX || contact.y >= maxY || contact.z >= maxZ)
                continue;
            float t = contact.t - elapsedTime;
            p.add(dx * t, dy * t, dz * t);
            elapsedTime += t;
            if (contact.nx != 0) {
                minX = dx < 0 ? max(minX, contact.x) : minX;
                maxX = dx < 0 ? maxX : min(maxX, contact.x);
                v.x = 0f;
                dx = 0f;
            } else if (contact.ny != 0) {
                minY = dy < 0 ? max(minY, contact.y) : contact.y - (int) PLAYER_HEIGHT;
                maxY = dy < 0 ? contact.y + (int) ceil(PLAYER_HEIGHT) + 1 : min(maxY, contact.y);
                v.y = 0f;
                dy = 0f;
            } else if (contact.nz != 0) {
                minZ = dz < 0 ? max(minZ, contact.z) : minZ;
                maxZ = dz < 0 ? maxZ : min(maxZ, contact.z);
                v.z = 0f;
                dz = 0f;
            }
        }
        float trem = 1f - elapsedTime;
        p.add(dx * trem, dy * trem, dz * trem);
    }

    /**
     * Setup GL state prior to drawing the chunk's bounding boxes invisibly, but still tested against
     * the current depth buffer.
     */
    private void preDrawBoundingBoxesForVisibilityBufferState() {
        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        glColorMask(debugBoundingBoxes, debugBoundingBoxes, debugBoundingBoxes, debugBoundingBoxes);
        if (useRepresentativeFragmentTest) {
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        }
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(useInverseDepth ? 1 : -1, useInverseDepth ? 1 : -1);
        glBindVertexArray(boundingBoxesVao);
        glUseProgram(boundingBoxesProgram);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, visibilityFlagsBuffer, (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * Integer.BYTES,
                MAX_ACTIVE_CHUNKS * Integer.BYTES);
        int uboSize = roundUpToNextMultiple(boundingBoxesProgramUboSize, uniformBufferOffsetAlignment);
        glBindBufferRange(GL_UNIFORM_BUFFER, boundingBoxesProgramUboBlockIndex, boundingBoxesProgramUbo, (long) currentDynamicBufferIndex * uboSize, uboSize);
    }

    /**
     * Draw all in-frustum chunks' bounding boxes.
     * <p>
     * This is to test those bounding boxes against the current depth buffer and, if any fragment gets
     * generated, write a visibility flag to an SSBO for that chunk.
     * <p>
     * We also always flag a chunk to be visible when we MUST draw it, because the player's eye location
     * is inside of the chunk and with the bounding box not being visible.
     * <p>
     * This also makes use of NV_representative_fragment_test (if available) to generate fewer fragments
     * (and thus perform fewer SSBO writes).
     */
    private void drawBoundingBoxesOfInFrustumChunks() {
        /*
         * Fill buffer objects to draw in-frustum chunks' bounding boxes.
         */
        updateBoundingBoxesInputBuffersForInFrustumChunks();
        /*
         * Update the uniform buffer object for drawing the bounding boxes.
         */
        updateBoundingBoxesProgramUbo();
        /*
         * Setup (global) OpenGL state for drawing the bounding boxes.
         */
        preDrawBoundingBoxesForVisibilityBufferState();
        /*
         * Clear the flags in the visibility buffer. This buffer will then be filled in the fragment shader
         * for all visible chunks.
         */
        try (MemoryStack stack = stackPush()) {
            long clearOffset = (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * Integer.BYTES;
            long clearSize = (long) MAX_ACTIVE_CHUNKS * Integer.BYTES;
            if (useClearBuffer) {
                glClearBufferSubData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, clearOffset, clearSize, GL_RED_INTEGER, GL_UNSIGNED_INT, stack.ints(0));
            } else {
                glBufferSubData(GL_SHADER_STORAGE_BUFFER, clearOffset, stack.callocInt((int) (clearSize / Integer.BYTES)));
            }
        }
        /*
         * Start drawing from the correct array buffer offset/region. We don't use the 'firstVertex' in the
         * glDrawArrays() call but offset the vertex attrib pointer, because we want gl_VertexID to start
         * from zero in the shader later.
         */
        long vertexByteOffset = (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 4 * Integer.BYTES;
        glVertexAttribIPointer(0, 4, GL_UNSIGNED_INT, 0, vertexByteOffset);
        glDrawArrays(GL_POINTS, 0, numChunksInFrustum);
        postDrawBoundingBoxesForVisibilityBufferState();
    }

    /**
     * Reset of critical global GL state, that we cannot assume the next draw call to reset itself,
     * after {@link #drawBoundingBoxesOfInFrustumChunks()}.
     */
    private void postDrawBoundingBoxesForVisibilityBufferState() {
        if (useRepresentativeFragmentTest) {
            glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        }
        glDisable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(0, 0);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glColorMask(true, true, true, true);
    }

    /**
     * Setup global GL state prior to collecting effective MDI structs via
     * {@link #collectDrawCommands()}.
     */
    private void preCollectDrawCommandsState() {
        glEnable(GL_RASTERIZER_DISCARD);
        glBindVertexArray(boundingBoxesVao);
        glUseProgram(collectDrawCallsProgram);
        /* Bind atomic counter for counting _all_ non-occluded in-frustum chunks */
        glBindBufferRange(GL_ATOMIC_COUNTER_BUFFER, 0, atomicCounterBuffer, (long) currentDynamicBufferIndex * Integer.BYTES, Integer.BYTES);
        clearAtomicCounter(0);
        /* Bind buffer for the visibility flags of non-occluded in-frustum chunks */
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, visibilityFlagsBuffer, (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * Integer.BYTES,
                (long) MAX_ACTIVE_CHUNKS * Integer.BYTES);
        /* Bind buffer for MDI draw structs input */
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, indirectDrawBuffer, (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 2 * Integer.BYTES,
                (long) MAX_ACTIVE_CHUNKS * 2 * Integer.BYTES);
        /* Bind buffer to output MDI draw structs for _all_ non-occluded in-frustum chunks */
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 2, indirectDrawCulledBuffer, (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES,
                (long) MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES);
        if (useTemporalCoherenceOcclusionCulling) {
            /*
             * If we use temporal coherence occlusion culling, we split the buffers into two distinct regions:
             * One for all non-occluded in-frustum chunks, and one for newly disoccluded in-frustum chunks.
             */
            /* Bind atomic counter for counting newly disoccluded in-frustum chunks */
            glBindBufferRange(GL_ATOMIC_COUNTER_BUFFER, 1, atomicCounterBuffer,
                    (long) (DYNAMIC_BUFFER_OBJECT_REGIONS + currentDynamicBufferIndex) * Integer.BYTES, Integer.BYTES);
            clearAtomicCounter(1);
            /* Bind buffer for the visibility flags of last frame's chunks */
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 3, visibilityFlagsBuffer, (long) lastFrameDynamicBufferIndex() * MAX_ACTIVE_CHUNKS * Integer.BYTES,
                    (long) MAX_ACTIVE_CHUNKS * Integer.BYTES);
            /* Bind buffer to output MDI draw structs for newly disoccluded in-frustum chunks */
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 4, indirectDrawCulledBuffer,
                    (long) (DYNAMIC_BUFFER_OBJECT_REGIONS + currentDynamicBufferIndex) * MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES,
                    (long) MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES);
        }
    }

    /**
     * Zero-out the given section {0, 1} of the currently bound atomic counter buffer.
     */
    private void clearAtomicCounter(int section) {
        try (MemoryStack stack = stackPush()) {
            long atomicOffset = ((long) DYNAMIC_BUFFER_OBJECT_REGIONS * section + currentDynamicBufferIndex) * Integer.BYTES;
            long atomicSize = Integer.BYTES;
            if (useClearBuffer) {
                glClearBufferSubData(GL_ATOMIC_COUNTER_BUFFER, GL_R32UI, atomicOffset, atomicSize, GL_RED_INTEGER, GL_UNSIGNED_INT, stack.ints(0));
            } else {
                glBufferSubData(GL_ATOMIC_COUNTER_BUFFER, atomicOffset, stack.callocInt(1));
            }
        }
    }

    /**
     * After the visibility SSBO was filled by {@link #drawBoundingBoxesOfInFrustumChunks()}, we append
     * MDI draw commands from an input SSBO to an output SSBO for visible chunks.
     */
    private void collectDrawCommands() {
        preCollectDrawCommandsState();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        /*
         * Start drawing from the correct array buffer offset/region. We don't use the 'firstVertex' in the
         * glDrawArrays() call but offset the vertex attrib pointer, because we want gl_VertexID to start
         * from zero in the shader later.
         */
        long vertexByteOffset = (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 4 * Integer.BYTES;
        glVertexAttribIPointer(0, 4, GL_UNSIGNED_INT, 0, vertexByteOffset);
        glDrawArrays(GL_POINTS, 0, numChunksInFrustum);
        postCollectDrawCommandsState();
    }

    /**
     * Reset of critical global GL state, that we cannot assume the next draw call to reset itself,
     * after {@link #collectDrawCommands()}.
     */
    private void postCollectDrawCommandsState() {
        glDisable(GL_RASTERIZER_DISCARD);
    }

    /**
     * Insert a fence sync to be notified when the GPU has finished rendering from the indirect draw
     * buffer.
     * <p>
     * We will wait for this to happen in {@link #updateIndirectBufferWithInFrustumChunks()} when
     * {@link #useBufferStorage}.
     */
    private void insertFenceSync() {
        if (dynamicBufferUpdateFences[currentDynamicBufferIndex] != 0L)
            glDeleteSync(dynamicBufferUpdateFences[currentDynamicBufferIndex]);
        dynamicBufferUpdateFences[currentDynamicBufferIndex] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        currentDynamicBufferIndex = (currentDynamicBufferIndex + 1) % DYNAMIC_BUFFER_OBJECT_REGIONS;
    }

    /**
     * Update the two main input buffers for rendering chunks' bounding boxes and collecting MDI draw
     * structs from visible/non-occluded chunks:
     * <ul>
     * <li>an array buffer containing the position and size of in-frustum chunks (this will be used with
     * a simple glDrawArrays() to draw points which will be expanded by a geometry shader to bounding
     * boxes)
     * <li>the face offsets and counts for all such in-frustum chunks
     * </ul>
     */
    private void updateBoundingBoxesInputBuffersForInFrustumChunks() {
        long faceOffsetsAndCounts, bb;
        long faceOffsetsAndCountsPos, bbPos;
        if (useBufferStorage) {
            faceOffsetsAndCounts = indirectDrawBufferAddr;
            faceOffsetsAndCountsPos = currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 2 * Integer.BYTES;
            bb = boundingBoxesVertexBufferObjectAddr;
            bbPos = currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 4 * Integer.BYTES;
        } else {
            faceOffsetsAndCounts = nmemAlloc(MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES);
            faceOffsetsAndCountsPos = 0L;
            bb = nmemAlloc(MAX_ACTIVE_CHUNKS * 4 * Integer.BYTES);
            bbPos = 0L;
        }
        int numVisible = 0;
        for (int i = 0; i < allChunks.size(); i++) {
            Chunk c = allChunks.get(i);
            boolean chunkMustBeDrawn = playerInsideChunk(c);
            if (!c.ready || chunkNotInFrustum(c) && !chunkMustBeDrawn)
                continue;
            faceOffsetsAndCountsPos += putChunkFaceOffsetAndCount(c, faceOffsetsAndCounts + faceOffsetsAndCountsPos);
            memPutInt(bb + bbPos, c.cx << CHUNK_SIZE_SHIFT);
            memPutInt(bb + bbPos + Integer.BYTES, c.cz << CHUNK_SIZE_SHIFT);
            memPutInt(bb + bbPos + 2 * Integer.BYTES, c.minY | c.maxY << 16);
            memPutInt(bb + bbPos + 3 * Integer.BYTES, c.index | (chunkMustBeDrawn ? 1 << 31 : 0));
            bbPos += 4 * Integer.BYTES;
            numVisible++;
        }
        long faceOffsetsAndCountsOffset = (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 2 * Integer.BYTES;
        long faceOffsetsAndCountsSize = (long) numVisible * 2 * Integer.BYTES;
        long boundingBoxesOffset = (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 4 * Integer.BYTES;
        long boundingBoxesSize = (long) numVisible * 4 * Integer.BYTES;
        if (useBufferStorage) {
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBuffer);
            glBindBuffer(GL_ARRAY_BUFFER, boundingBoxesVertexBufferObject);
            glFlushMappedBufferRange(GL_DRAW_INDIRECT_BUFFER, faceOffsetsAndCountsOffset, faceOffsetsAndCountsSize);
            glFlushMappedBufferRange(GL_ARRAY_BUFFER, boundingBoxesOffset, boundingBoxesSize);
        } else {
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBuffer);
            glBindBuffer(GL_ARRAY_BUFFER, boundingBoxesVertexBufferObject);
            nglBufferSubData(GL_DRAW_INDIRECT_BUFFER, faceOffsetsAndCountsOffset, faceOffsetsAndCountsPos, faceOffsetsAndCounts);
            nglBufferSubData(GL_ARRAY_BUFFER, boundingBoxesOffset, bbPos, bb);
            nmemFree(faceOffsetsAndCounts);
            nmemFree(bb);
        }
        numChunksInFrustum = numVisible;
    }

    /**
     * Write face offset and count to later build an MDI glMultiDrawElementsIndirect struct (to draw the
     * given chunk).
     */
    private int putChunkFaceOffsetAndCount(Chunk c, long faceOffsetsAndCounts) {
        memPutInt(faceOffsetsAndCounts, c.r.off);
        memPutInt(faceOffsetsAndCounts + Integer.BYTES, c.r.len);
        return Integer.BYTES << 1;
    }

    /**
     * Fill the current region of the dynamic indirect draw buffer with draw commands for in-frustum
     * chunks and return the number of such chunks.
     */
    private int updateIndirectBufferWithInFrustumChunks() {
        int numChunks = 0;
        long indirect, indirectPos;
        long offset = (long) currentDynamicBufferIndex * MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES;
        if (useBufferStorage) {
            indirect = indirectDrawBufferAddr;
            indirectPos = offset;
        } else {
            indirect = nmemAlloc(5 * Integer.BYTES * allChunks.size());
            indirectPos = 0L;
        }
        for (int i = 0; i < allChunks.size(); i++) {
            Chunk c = allChunks.get(i);
            if (!c.ready || chunkNotInFrustum(c))
                continue;
            memPutInt(indirect + indirectPos, c.r.len * indicesPerFace);
            memPutInt(indirect + indirectPos + Integer.BYTES, 1);
            memPutInt(indirect + indirectPos + Integer.BYTES * 2, c.r.off * indicesPerFace);
            memPutInt(indirect + indirectPos + Integer.BYTES * 3, c.r.off * verticesPerFace);
            memPutInt(indirect + indirectPos + Integer.BYTES * 4, c.index);
            indirectPos += Integer.BYTES * 5;
            numChunks++;
        }
        if (useBufferStorage) {
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBuffer);
            glFlushMappedBufferRange(GL_DRAW_INDIRECT_BUFFER, offset, (long) numChunks * 5 * Integer.BYTES);
        } else {
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBuffer);
            nglBufferSubData(GL_DRAW_INDIRECT_BUFFER, offset, indirectPos, indirect);
            nmemFree(indirect);
        }
        return numChunks;
    }

    /**
     * Wait for the fence sync before we can modify the current mapped buffer storage region.
     * <p>
     * We inserted a fence sync in {@link #runUpdateAndRenderLoop()} when {@link #useBufferStorage},
     * because with client-mapped memory it's our own responsibility to now touch that memory until the
     * GPU has finished using it.
     */
    private void waitForFenceSync() {
        if (dynamicBufferUpdateFences[currentDynamicBufferIndex] == 0L)
            return;
        /* wait for fence sync before we can modify the mapped buffer range */
        int waitReturn = glClientWaitSync(dynamicBufferUpdateFences[currentDynamicBufferIndex], 0, 0L);
        while (waitReturn != GL_ALREADY_SIGNALED && waitReturn != GL_CONDITION_SATISFIED) {
            if (DEBUG) {
                System.out.println("Need to wait for fence sync!");
            }
            waitReturn = glClientWaitSync(dynamicBufferUpdateFences[currentDynamicBufferIndex], GL_SYNC_FLUSH_COMMANDS_BIT, 1L);
        }
    }

    /**
     * Setup GL state prior to drawing the chunks.
     */
    private void preDrawChunksState() {
        if (wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        } else {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }
        if (drawPointsWithGS)
            glDisable(GL_CULL_FACE);
        else
            glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_BUFFER, materialsTexture);
        if (!useMultiDrawIndirect) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_BUFFER, chunkInfoTexture);
        }
        glBindVertexArray(chunksVao);
        glUseProgram(chunksProgram);
        /*
         * Bind the UBO holding camera matrices for drawing the chunks.
         */
        int uboSize = roundUpToNextMultiple(chunksProgramUboSize, uniformBufferOffsetAlignment);
        glBindBufferRange(GL_UNIFORM_BUFFER, chunksProgramUboBlockIndex, chunksProgramUbo, (long) currentDynamicBufferIndex * uboSize, uboSize);
        if (canSourceIndirectDrawCallCountFromBuffer) {
            /*
             * Bind the atomic counter buffer to the indirect parameter count binding point, to source the
             * drawcall count from that buffer.
             */
            glBindBuffer(GL_PARAMETER_BUFFER_ARB, atomicCounterBuffer);
        } else {
            /*
             * Bind the atomic counter buffer to the atomic counter buffer binding point. We will use
             * glGetBufferSubData() to actually read-back the value of the counter from the buffer.
             */
            glBindBuffer(GL_ATOMIC_COUNTER_BUFFER, atomicCounterBuffer);
        }
        /*
         * Bind the indirect buffer containing the final MDI draw structs for all chunks that are visible.
         */
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawCulledBuffer);
    }

    /**
     * Setup GL state prior to drawing the chunks with an MDI call where the MDI structs are generated
     * by the CPU.
     */
    private void preDrawChunksIndirectCpuGeneratedState() {
        preDrawChunksState();
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBuffer);
        int uboSize = roundUpToNextMultiple(chunksProgramUboSize, uniformBufferOffsetAlignment);
        glBindBufferRange(GL_UNIFORM_BUFFER, chunksProgramUboBlockIndex, chunksProgramUbo, (long) currentDynamicBufferIndex * uboSize, uboSize);
    }

    /**
     * Draw the voxels via the non-MDI render path with glMultiDrawElementsBaseVertex().
     */
    private void drawChunksWithMultiDrawElementsBaseVertex() {
        if (chunksVao == 0)
            return;
        preDrawChunksState();
        try (MemoryStack stack = stackPush()) {
            PointerBuffer indices = stack.mallocPointer(allChunks.size());
            IntBuffer count = stack.mallocInt(allChunks.size());
            IntBuffer basevertex = stack.mallocInt(allChunks.size());
            for (Chunk c : allChunks) {
                if (!c.ready || chunkNotInFrustum(c))
                    continue;
                indices.put((long) Short.BYTES * c.r.off * indicesPerFace);
                count.put(c.r.len * indicesPerFace);
                basevertex.put(c.r.off * verticesPerFace);
            }
            indices.flip();
            count.flip();
            basevertex.flip();
            updateChunksProgramUbo();
            glMultiDrawElementsBaseVertex(GL_TRIANGLE_STRIP, count, GL_UNSIGNED_SHORT, indices, basevertex);
        }
    }

    /**
     * Draw all chunks via a single MDI glMultiDrawElementsIndirect() call with MDI structs written by
     * the CPU.
     */
    private void drawChunksWithMultiDrawElementsIndirectCpuGenerated(int numChunks) {
        if (chunksVao == 0)
            return;
        updateChunksProgramUbo();
        preDrawChunksIndirectCpuGeneratedState();
        glMultiDrawElementsIndirect(drawPointsWithGS ? GL_POINTS : GL_TRIANGLE_STRIP, GL_UNSIGNED_SHORT,
                (long) MAX_ACTIVE_CHUNKS * currentDynamicBufferIndex * 5 * Integer.BYTES, numChunks, 0);
    }

    /**
     * Draw all chunks via a single MDI glMultiDrawElementsIndirectCount() call (or
     * glMultiDrawElementsIndirect()) with MDI structs written by the GPU and sourcing the draw count
     * from a GL buffer.
     * <p>
     * This takes in the dynamic buffer index we want to use for drawing, to be able to draw the last
     * frame's chunks again for temporal coherence occlusion culling. Additionally, it allows to draw
     * only the newly disoccluded chunks
     * 
     * @param dynamicBufferIndex determines whether we want to draw last frame's or this frame's dynamic
     *                           data
     * @param onlyDisoccluded    determines whether we want to draw _all_ non-occluded in-frustum chunks
     *                           (<code>false</code>), or only newly disoccluded chunks
     *                           (<code>true</code>)
     */
    private void drawChunksWithMultiDrawElementsIndirectGpuGenerated(int dynamicBufferIndex, boolean onlyDisoccluded) {
        if (chunksVao == 0)
            return;
        updateChunksProgramUbo();
        preDrawChunksState();
        /*
         * Add barrier for shader writes to buffer objects that we use as GL_DRAW_INDIRECT_BUFFER (and
         * GL_PARAMETER_BUFFER_ARB) which the GL_COMMAND_BARRIER_BIT covers, done by the collection step.
         */
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
        /*
         * If we use temporal coherence occlusion culling, we have two sections in each buffer. One for all
         * non-occluded in-frustum chunks, and one for newly disoccluded chunks.
         */
        if (canSourceIndirectDrawCallCountFromBuffer) {
            /*
             * We use the atomic counter buffer bound to GL_PARAMETER_BUFFER_ARB to source the drawcall count.
             */
            glMultiDrawElementsIndirectCountARB(drawPointsWithGS ? GL_POINTS : GL_TRIANGLE_STRIP, GL_UNSIGNED_SHORT,
                    ((long) DYNAMIC_BUFFER_OBJECT_REGIONS * (onlyDisoccluded ? 1 : 0) + dynamicBufferIndex) * MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES,
                    ((long) DYNAMIC_BUFFER_OBJECT_REGIONS * (onlyDisoccluded ? 1 : 0) + dynamicBufferIndex) * Integer.BYTES, numChunksInFrustum, 0);
        } else {
            /*
             * TODO: Do we _really_ want to support this path? if ARB_indirect_parameters is not available, we
             * MUST CPU-readback the atomic counter value to provide to glMultiDrawElementsIndirect(), which
             * stalls everything.
             */
            try (MemoryStack stack = stackPush()) {
                IntBuffer counter = stack.mallocInt(1);
                glGetBufferSubData(GL_ATOMIC_COUNTER_BUFFER,
                        ((long) DYNAMIC_BUFFER_OBJECT_REGIONS * (onlyDisoccluded ? 1 : 0) + currentDynamicBufferIndex) * Integer.BYTES, counter);
                glMultiDrawElementsIndirect(drawPointsWithGS ? GL_POINTS : GL_TRIANGLE_STRIP, GL_UNSIGNED_SHORT,
                        ((long) DYNAMIC_BUFFER_OBJECT_REGIONS * (onlyDisoccluded ? 1 : 0) + dynamicBufferIndex) * MAX_ACTIVE_CHUNKS * 5 * Integer.BYTES,
                        counter.get(0), 0);
            }
        }
    }

    /**
     * Update the current region of the UBO for the voxels program.
     */
    private void updateChunksProgramUbo() {
        int size = roundUpToNextMultiple(chunksProgramUboSize, uniformBufferOffsetAlignment);
        try (MemoryStack stack = stackPush()) {
            long ubo, uboPos;
            if (useBufferStorage) {
                ubo = chunksProgramUboAddr;
                uboPos = currentDynamicBufferIndex * size;
            } else {
                ubo = stack.nmalloc(size);
                uboPos = 0L;
            }
            mvpMat.getToAddress(ubo + uboPos);
            uboPos += 16 * Float.BYTES;
            mvpMat.getRow(3, tmpv4f).getToAddress(ubo + uboPos);
            uboPos += 4 * Float.BYTES;
            memPutInt(ubo + uboPos, (int) floor(playerPosition.x));
            memPutInt(ubo + uboPos + Integer.BYTES, (int) floor(playerPosition.y));
            memPutInt(ubo + uboPos + Integer.BYTES * 2, (int) floor(playerPosition.z));
            uboPos += 3 * Float.BYTES;
            glBindBuffer(GL_UNIFORM_BUFFER, chunksProgramUbo);
            if (useBufferStorage) {
                glFlushMappedBufferRange(GL_UNIFORM_BUFFER, (long) currentDynamicBufferIndex * size, size);
            } else {
                nglBufferSubData(GL_UNIFORM_BUFFER, (long) currentDynamicBufferIndex * size, uboPos, ubo);
            }
        }
    }

    /**
     * Update the uniform buffer object for the bounding boxes program.
     */
    private void updateBoundingBoxesProgramUbo() {
        int size = roundUpToNextMultiple(boundingBoxesProgramUboSize, uniformBufferOffsetAlignment);
        try (MemoryStack stack = stackPush()) {
            long ubo, uboPos;
            if (useBufferStorage) {
                ubo = boundingBoxesProgramUboAddr;
                uboPos = currentDynamicBufferIndex * size;
            } else {
                ubo = stack.nmalloc(size);
                uboPos = 0L;
            }
            mvpMat.getToAddress(ubo + uboPos);
            uboPos += 16 * Float.BYTES;
            memPutInt(ubo + uboPos, (int) floor(playerPosition.x));
            memPutInt(ubo + uboPos + Integer.BYTES, (int) floor(playerPosition.y));
            memPutInt(ubo + uboPos + Integer.BYTES * 2, (int) floor(playerPosition.z));
            uboPos += 3 * Integer.BYTES;
            glBindBuffer(GL_UNIFORM_BUFFER, boundingBoxesProgramUbo);
            if (useBufferStorage) {
                glFlushMappedBufferRange(GL_UNIFORM_BUFFER, (long) currentDynamicBufferIndex * size, size);
            } else {
                nglBufferSubData(GL_UNIFORM_BUFFER, (long) currentDynamicBufferIndex * size, uboPos, ubo);
            }
        }
    }

    private static int rgb(int r, int g, int b) {
        return r | g << 8 | b << 16 | 0xFF << 24;
    }

    /**
     * Evaluate a heightmap/terrain noise function at the given global <code>(x, z)</code> position.
     */
    private static float terrainNoise(int x, int z) {
        float xzScale = 0.0018f;
        float ampl = 255;
        float y = 0;
        float groundLevel = BASE_Y + noise(x * xzScale, z * xzScale) * ampl * 0.1f;
        for (int i = 0; i < 4; i++) {
            y += ampl * (noise(x * xzScale, z * xzScale) * 0.5f + 0.2f);
            ampl *= 0.42f;
            xzScale *= 2.2f;
        }
        y = min(CHUNK_HEIGHT - 2, max(y, groundLevel));
        return y;
    }

    /**
     * Return the flattened voxel field index for a local voxel at <code>(x, y, z)</code>.
     */
    private static int idx(int x, int y, int z) {
        return (x + 1) + (CHUNK_SIZE + 2) * ((z + 1) + (y + 1) * (CHUNK_SIZE + 2));
    }

    /**
     * Create a voxel field for a chunk at the given chunk position.
     * 
     * @param cx the x coordinate of the chunk position (in whole chunks)
     * @param cz the z coordinate of the chunk position (in whole chunks)
     */
    private static VoxelField createVoxelField(int cx, int cz) {
        int gx = (cx << CHUNK_SIZE_SHIFT) + GLOBAL_X, gz = (cz << CHUNK_SIZE_SHIFT) + GLOBAL_Z;
        byte[] field = new byte[(CHUNK_SIZE + 2) * (CHUNK_HEIGHT + 2) * (CHUNK_SIZE + 2)];
        int maxY = Integer.MIN_VALUE, minY = Integer.MAX_VALUE;
        int num = 0;
        for (int z = -1; z < CHUNK_SIZE + 1; z++) {
            for (int x = -1; x < CHUNK_SIZE + 1; x++) {
                int y = (int) terrainNoise(gx + x, gz + z);
                y = min(max(y, 0), CHUNK_HEIGHT - 1);
                maxY = max(maxY, y);
                minY = min(minY, y);
                for (int y0 = -1; y0 <= y; y0++) {
                    field[idx(x, y0, z)] = (byte) (y0 == y ? 1 : 2);
                    num++;
                }
            }
        }
        VoxelField res = new VoxelField();
        res.ny = minY;
        res.py = maxY;
        res.num = num;
        res.field = field;
        return res;
    }

    /**
     * Loop in the main thread to only process OS/window event messages.
     * <p>
     * See {@link #registerWindowCallbacks()} for all callbacks that may fire due to events.
     */
    private void runWndProcLoop() {
        glfwShowWindow(window);
        while (!glfwWindowShouldClose(window)) {
            glfwWaitEvents();
            if (updateWindowTitle) {
                glfwSetWindowTitle(window, windowStatsString);
                updateWindowTitle = false;
            }
        }
    }

    /**
     * Compute last frame's dynamic buffer index.
     * <p>
     * This will be used for temporal coherence occlusion culling to draw last frame's visible chunks.
     */
    private int lastFrameDynamicBufferIndex() {
        return (currentDynamicBufferIndex + DYNAMIC_BUFFER_OBJECT_REGIONS - 1) % DYNAMIC_BUFFER_OBJECT_REGIONS;
    }

    /**
     * Run the "update and render" loop in a separate thread.
     * <p>
     * This is to decouple rendering from possibly long-blocking polling of OS/window messages (via
     * {@link GLFW#glfwPollEvents()}).
     */
    private void runUpdateAndRenderLoop() {
        glfwMakeContextCurrent(window);
        GL.setCapabilities(caps);
        long lastTime = System.nanoTime();
        while (!glfwWindowShouldClose(window)) {
            /*
             * Compute time difference between this and last frame.
             */
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) * 1E-9f;
            lastTime = thisTime;
            if (!FULLSCREEN) {
                /*
                 * Update stats in window title if we run in windowed mode.
                 */
                updateStatsInWindowTitle(dt);
            }
            /*
             * Execute any runnables that have accumulated in the render queue. These are GL calls for
             * created/updated chunks.
             */
            drainRunnables();
            /*
             * Bind FBO to which we will render.
             */
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            if (useBufferStorage) {
                /*
                 * If we use ARB_buffer_storage and with it persistently mapped buffers, we must explicitly sync
                 * between updating those buffers and rendering from those buffers ourselves, because OpenGL won't
                 * do that anymore (which is good!).
                 */
                waitForFenceSync();
            }
            if (useOcclusionCulling) {
                if (useTemporalCoherenceOcclusionCulling) {
                    /*
                     * Clear color and depth now, because we will draw last frame's chunks for priming depth and color
                     * buffers.
                     */
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    /*
                     * Update player's position and matrices.
                     */
                    updatePlayerPositionAndMatrices(dt);
                    /*
                     * Create new in-view chunks and destroy out-of-view chunks.
                     */
                    while (createInRenderDistanceAndDestroyOutOfRenderDistanceChunks())
                      ;
                    // Determine the selected voxel in the center of the viewport.
                    determineSelectedVoxel();
                    /*
                     * Draw the same chunks that were visible last frame by using last frame's dynamic buffer index, so
                     * we use last frame's MDI draw structs. Newly disoccluded chunks that were not visible last frame
                     * will be rendered with another draw call below.
                     */
                    drawChunksWithMultiDrawElementsIndirectGpuGenerated(lastFrameDynamicBufferIndex(), false);
                    /*
                     * Render bounding boxes of in-frustum chunks to set visibility flags via SSBO writes in the
                     * fragment shader.
                     */
                    drawBoundingBoxesOfInFrustumChunks();
                    /*
                     * Collect MDI structs for in-frustum chunks marked visible by the bounding boxes draw.
                     * Additionally, collect newly disoccluded chunks that were not visible last frame.
                     */
                    collectDrawCommands();
                    /*
                     * Now draw all newly disoccluded chunks which were not visible last frame for which we collected
                     * the MDI structs in the collect call above.
                     */
                    drawChunksWithMultiDrawElementsIndirectGpuGenerated(currentDynamicBufferIndex, true);
                } else {
                    /*
                     * If we don't want to use temporal coherence occlusion culling, we simply draw the chunks' bounding
                     * boxes tested against the last frame's depth buffer using the last view matrices. This will lead
                     * to visible popping of disoccluded chunks.
                     */
                    drawBoundingBoxesOfInFrustumChunks();
                    /*
                     * Clear color and depth buffers now after we've tested the bounding boxes against last frame's
                     * depth buffer.
                     */
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    /*
                     * Update player's position and matrices.
                     */
                    updatePlayerPositionAndMatrices(dt);
                    /*
                     * Create new in-view chunks and destroy out-of-view chunks.
                     */
                    while (createInRenderDistanceAndDestroyOutOfRenderDistanceChunks())
                      ;
                    // Determine the selected voxel in the center of the viewport.
                    determineSelectedVoxel();
                    /*
                     * Collect MDI structs for in-frustum chunks marked visible by the bounding boxes draw.
                     */
                    collectDrawCommands();
                    /*
                     * Draw all chunks for which we collected the MDI structs.
                     */
                    drawChunksWithMultiDrawElementsIndirectGpuGenerated(currentDynamicBufferIndex, false);
                }
            } else {
                /*
                 * If we don't want to do any sort of occlusion culling, we clear color and depth buffers and update
                 * the player's position and matrices.
                 */
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                /*
                 * Update player's position and matrices.
                 */
                updatePlayerPositionAndMatrices(dt);
                
                /*
                 * Check if we support MDI.
                 */
                if (useMultiDrawIndirect) {
                    int numChunks = updateIndirectBufferWithInFrustumChunks();
                    drawChunksWithMultiDrawElementsIndirectCpuGenerated(numChunks);
                } else {
                    /*
                     * If not, we will just use multi-draw without indirect.
                     */
                    drawChunksWithMultiDrawElementsBaseVertex();
                }
            }
            /*
             * Draw highlighting of selected voxel face.
             */
            drawSelection();
            /*
             * Insert GPU fence sync that we will wait for a few frames later when we come back at updating the
             * same dynamic buffer region.
             */
            if (useBufferStorage) {
                insertFenceSync();
            }
            /*
             * Blit FBO to the window.
             */
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glfwSwapBuffers(window);
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(2000L, TimeUnit.MILLISECONDS))
                throw new AssertionError();
        } catch (Exception e) {
            throw new AssertionError();
        }
        drainRunnables();
        GL.setCapabilities(null);
    }

    private int statsFrames;
    private float statsTotalFramesTime;
    private volatile boolean updateWindowTitle;
    private String windowStatsString;

    /**
     * When in windowed mode, this method will be called to update certain statistics that are shown in the window
     * title.
     */
    private void updateStatsInWindowTitle(float dt) {
        if (statsTotalFramesTime >= 0.5f) {
            int px = (int) floor(playerPosition.x);
            int py = (int) floor(playerPosition.y);
            int pz = (int) floor(playerPosition.z);
            windowStatsString = statsFrames * 2 + " FPS, " + INT_FORMATTER.format(allChunks.size()) + " act. chunks, " + INT_FORMATTER.format(numChunksInFrustum)
                + " chunks in frustum, GPU mem. " + INT_FORMATTER.format(computePerFaceBufferObjectSize() / 1024 / 1024) + " MB @ " + px + " , "
                + py + " , " + pz;
            statsFrames = 0;
            statsTotalFramesTime = 0f;
            updateWindowTitle = true;
            glfwPostEmptyEvent();
        }
        statsFrames++;
        statsTotalFramesTime += dt;
    }

    /**
     * Process all update/render thread tasks in the {@link #updateAndRenderRunnables} queue.
     */
    private void drainRunnables() {
        Iterator<DelayedRunnable> it = updateAndRenderRunnables.iterator();
        while (it.hasNext()) {
            DelayedRunnable dr = it.next();
            /* Check if we want to delay this runnable */
            if (dr.delay > 0) {
                if (DEBUG) {
                    System.out.println("Delaying runnable [" + dr.name + "] for " + dr.delay + " frames");
                }
                dr.delay--;
                continue;
            }
            try {
                /* Remove from queue and execute */
                it.remove();
                dr.runnable.call();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Configure OpenGL state for drawing the selected voxel face.
     */
    private void preDrawSelectionState() {
        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(useInverseDepth ? 1 : -1, useInverseDepth ? 1 : -1);
        glBindVertexArray(nullVao);
        glUseProgram(selectionProgram);
    }

    /**
     * Update the current region of the UBO for the drawing the selection quad.
     */
    private void updateSelectionProgramUbo(Matrix4f mvp, float r, float g, float b) {
        /* Round up to the next multiple of the UBO alignment */
        int size = roundUpToNextMultiple(selectionProgramUboSize, uniformBufferOffsetAlignment);
        try (MemoryStack stack = stackPush()) {
            long ubo, uboPos;
            if (useBufferStorage) {
                ubo = selectionProgramUboAddr;
                uboPos = currentDynamicBufferIndex * size;
            } else {
                ubo = stack.nmalloc(size);
                uboPos = 0L;
            }
            mvp.getToAddress(ubo + uboPos);
            uboPos += 16 * Float.BYTES;
            glBindBufferRange(GL_UNIFORM_BUFFER, selectionProgramUboBlockIndex, selectionProgramUbo, (long) currentDynamicBufferIndex * size, size);
            if (useBufferStorage) {
                glFlushMappedBufferRange(GL_UNIFORM_BUFFER, (long) currentDynamicBufferIndex * size, size);
            } else {
                nglBufferSubData(GL_UNIFORM_BUFFER, (long) currentDynamicBufferIndex * size, uboPos, ubo);
            }
        }
    }

    /**
     * Draw the highlighting of the selected voxel face.
     */
    private void drawSelection() {
        if (!hasSelection)
            return;
        preDrawSelectionState();
        /* compute a player-relative position. The MVP matrix is already player-centered */
        double dx = selectedVoxelPosition.x - floor(playerPosition.x);
        double dy = selectedVoxelPosition.y - floor(playerPosition.y);
        double dz = selectedVoxelPosition.z - floor(playerPosition.z);
        tmpMat.set(mvpMat).translate((float) dx, (float) dy, (float) dz);
        /* translate and rotate based on face side */
        if (sideOffset.x != 0) {
            tmpMat.translate(sideOffset.x > 0 ? 1 : 0, 0, 1).mul3x3(0, 0, -1, 0, 1, 0, 1, 0, 0);
        } else if (sideOffset.y != 0) {
            tmpMat.translate(0, sideOffset.y > 0 ? 1 : 0, 1).mul3x3(1, 0, 0, 0, 0, -1, 0, 1, 0);
        } else if (sideOffset.z != 0) {
            tmpMat.translate(0, 0, sideOffset.z > 0 ? 1 : 0).mul3x3(1, 0, 0, 0, 1, 0, 0, 0, 1);
        }
        /* animate it a bit */
        float s = (float) sin(System.currentTimeMillis() / 4E2);
        tmpMat.translate(0.5f, 0.5f, 0f).scale(0.3f + 0.1f * s * s);
        updateSelectionProgramUbo(tmpMat, 0.2f, 0.3f, 0.6f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        postDrawSelectionState();
    }

    /**
     * Reset of critical global GL state, that we cannot assume the next draw call to reset itself,
     * after {@link #drawSelection()}.
     */
    private void postDrawSelectionState() {
        glDisable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(0, 0);
    }

    /**
     * Create a dedicated thread to process updates and perform rendering.
     * <p>
     * This is <em>only</em> for decoupling the render thread from potentially long-blocking
     * {@link GLFW#glfwPollEvents()} calls (when e.g. many mouse move events occur).
     * <p>
     * Instead, whenever a OS/window event is received, it is enqueued into the
     * {@link #updateAndRenderRunnables} queue.
     */
    private Thread createAndStartUpdateAndRenderThread() {
        Thread renderThread = new Thread(this::runUpdateAndRenderLoop);
        renderThread.setName("Render Thread");
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.start();
        return renderThread;
    }

    /**
     * Initialize and run the game/demo.
     */
    private void run() throws InterruptedException, IOException {
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        createWindow();
        registerWindowCallbacks();
        setWindowPosition();
        queryFramebufferSizeForHiDPI();

        initGLResources();

        /* Run logic updates and rendering in a separate thread */
        Thread updateAndRenderThread = createAndStartUpdateAndRenderThread();
        /* Process OS/window event messages in this main thread */
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
        /* Wait for the latch to signal that init render thread actions are done */
        runWndProcLoop();
        /*
         * After the wnd loop exited (because the window was closed), wait for render thread to complete
         * finalization.
         */
        updateAndRenderThread.join();
        if (debugProc != null)
            debugProc.free();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void initGLResources() throws IOException {
        glfwMakeContextCurrent(window);

        /* Determine, which additional OpenGL capabilities we have. */
        determineOpenGLCapabilities();

        /*
         * Compute number of vertices per face and number of bytes per vertex. These depend on the features
         * we are going to use.
         */
        verticesPerFace = drawPointsWithGS ? 1 : 4;
        indicesPerFace = drawPointsWithGS ? 1 : 5;
        voxelVertexSize = drawPointsWithGS ? 2 * Integer.BYTES : Integer.BYTES + Short.BYTES + (!useMultiDrawIndirect ? Integer.BYTES : 0);

        if (DEBUG || GLDEBUG) {
            installDebugCallback();
        }
        glfwSwapInterval(VSYNC ? 1 : 0);

        /* Configure OpenGL state and create all necessary resources */
        configureGlobalGlState();
        createSelectionProgram();
        createSelectionProgramUbo();
        createNullVao();
        createMaterials();
        createMultiDrawIndirectBuffer();
        createChunkInfoBuffers();
        createChunksProgram();
        createChunksProgramUbo();
        if (canGenerateDrawCallsViaShader) {
            createOcclusionCullingBufferObjects();
            createBoundingBoxesProgram();
            createCollectDrawCallsProgram();
            createBoundingBoxesProgramUbo();
            createBoundingBoxesVao();
        }
        createFramebufferObject();

        /* Make sure everything is ready before we show the window */
        glFlush();
        glfwMakeContextCurrent(NULL);
        GL.setCapabilities(null);
    }

    public static void main(String[] args) throws Exception {
        new VoxelGameGL().run();
    }
}
