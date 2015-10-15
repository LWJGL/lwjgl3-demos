/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.geometry;

import java.nio.IntBuffer;

/**
 * Computes adjacency information suitable for GL_TRIANGLES_ADJACENCY rendering.
 * <p>
 * The algorithm is layed out at:
 * <a href="http://gamedev.stackexchange.com/questions/62097/building-triangle-adjacency-data">gamedev.stackexchange.com
 * </a>.
 * <p>
 * This class is a port of the original C implementation at:
 * <a href="http://prideout.net/blog/?p=54">http://prideout.net/blog/?p=54</a>
 * 
 * @author Kai Burjack
 */
public class Adjacency {

    /**
     * Takes an index/element buffer for normal GL_TRIANGLES rendering and computes another index buffer with adjacency
     * information suitable for rendering via GL_TRIANGLES_ADJACENCY mode.
     * 
     * @param source
     *            the index buffer of a normal GL_TRIANGLES mesh. Neither the position nor the limit are modified by
     *            this method
     * @param dest
     *            will hold the indices of the triangles with adjacency information. It must have at least double the
     *            size of <code>source</code>. Neither the position nor the limit are modified by this method
     */
    public static void computeAdjacency(IntBuffer source, IntBuffer dest) {
        if (source.remaining() % 3 != 0) {
            throw new IllegalArgumentException("source must contain three indices for each triangle");
        }
        if (dest.remaining() < source.remaining() * 2) {
            throw new IllegalArgumentException(
                    "dest must have at least " + (source.remaining() * 2) + " remaining elements");
        }

        final class HalfEdge {
            int vert;
            HalfEdge twin;
            HalfEdge next;
        }

        final class HalfEdgeMap {
            final static long DEAD_KEY = Long.MIN_VALUE;
            long[] keys = new long[16];
            HalfEdge[] values = new HalfEdge[16];
            int size;
            int mask = keys.length - 1;

            HalfEdgeMap() {
                for (int i = 0; i < values.length; i++)
                    keys[i] = DEAD_KEY;
            }

            HalfEdge get(long key) {
                int hash = (int) (key & mask);
                while (true) {
                    long mapKey = keys[hash];
                    if (mapKey == key) {
                        return values[hash];
                    } else if (mapKey == DEAD_KEY) {
                        return null;
                    }
                    hash = (hash + 1) & mask;
                }
            }

            void resize(int newSize) {
                long[] newKeys = new long[newSize];
                HalfEdge[] newValues = new HalfEdge[newSize];
                for (int i = 0; i < newSize; i++) {
                    newKeys[i] = DEAD_KEY;
                }
                mask = newKeys.length - 1;
                for (int i = 0; i < keys.length; i++) {
                    if (keys[i] == DEAD_KEY) {
                        continue;
                    }
                    int hash = (int) keys[i] & mask;
                    while (true) {
                        if (newKeys[hash] == DEAD_KEY) {
                            newKeys[hash] = keys[i];
                            newValues[hash] = values[i];
                            break;
                        }
                        hash = (hash + 1) & mask;
                    }
                }
                keys = newKeys;
                values = newValues;
            }

            HalfEdge put(long key, HalfEdge value) {
                int hash = (int) (key & mask);
                int count = size;
                while (count-- >= 0) {
                    long testKey = keys[hash];
                    if (testKey == DEAD_KEY) {
                        keys[hash] = key;
                        values[hash] = value;
                        size++;
                        if (keys.length <= 2 * size) {
                            resize(2 * keys.length);
                        }
                        return null;
                    } else if (key != testKey) {
                        hash = (hash + 1) & mask;
                        continue;
                    } else {
                        HalfEdge old = values[hash];
                        values[hash] = value;
                        return old;
                    }
                }
                return null;
            }
        }

        int faceCount = source.remaining() / 3;
        // Allocate all pieces of the half-edge data structure
        HalfEdge[] edges = new HalfEdge[faceCount * 3];
        for (int i = 0; i < edges.length; i++)
            edges[i] = new HalfEdge();

        // Declare a map to help build the half-edge structure:
        // - Keys are pairs of vertex indices
        // - Values are half-edges
        HalfEdgeMap edgeTable = new HalfEdgeMap();

        // Plow through faces and fill all half-edge info except twin pointers:
        int srcIdx = 0;
        int edgeIdx = 0;
        for (int faceIndex = 0; faceIndex < faceCount; faceIndex++) {
            int A = source.get(srcIdx++);
            int B = source.get(srcIdx++);
            int C = source.get(srcIdx++);
            HalfEdge edge = edges[edgeIdx];

            // Create the half-edge that goes from C to A:
            edgeTable.put((long) C | ((long) A << 32L), edge);
            edge.vert = A;
            edge.next = edges[1 + edgeIdx];
            edge = edges[++edgeIdx];

            // Create the half-edge that goes from A to B:
            edgeTable.put((long) A | ((long) B << 32L), edge);
            edge.vert = B;
            edge.next = edges[1 + edgeIdx];
            edge = edges[++edgeIdx];

            // Create the half-edge that goes from B to C:
            edgeTable.put((long) B | ((long) C << 32L), edge);
            edge.vert = C;
            edge.next = edges[edgeIdx - 2];
            ++edgeIdx;
        }

        // Verify that the mesh is clean
        int numEntries = edgeTable.size;
        if (numEntries != faceCount * 3) {
            throw new IllegalArgumentException("Bad mesh: duplicated edges or inconsistent winding.");
        }

        // Populate the twin pointers by iterating over the edges
        int boundaryCount = 0;
        long UINT_MASK = 0xFFFFFFFFL;
        for (int index = 0; index < edgeTable.keys.length; index++) {
            if (edgeTable.keys[index] != HalfEdgeMap.DEAD_KEY) {
                long edgeIndex = edgeTable.keys[index];
                HalfEdge edge = (HalfEdge) edgeTable.values[index];
                long twinIndex = ((edgeIndex & UINT_MASK) << 32L) | (edgeIndex >>> 32L);
                HalfEdge twinEdge = edgeTable.get(twinIndex);
                if (twinEdge != null) {
                    twinEdge.twin = edge;
                    edge.twin = twinEdge;
                } else {
                    boundaryCount++;
                }
            }
        }

        // Now that we have a half-edge structure, it's easy to create adjacency info for OpenGL
        int destPos = dest.position();
        if (boundaryCount > 0) {
            // Mesh is not watertight. Contains #boundaryCount boundary edges.
            for (int faceIndex = 0; faceIndex < faceCount; faceIndex++) {
                edgeIdx = faceIndex * 3;
                int destIdx = faceIndex * 6;
                dest.put(edges[edgeIdx + 2].vert);
                HalfEdge twin = edges[edgeIdx].twin;
                dest.put(twin != null ? twin.next.vert : dest.get(destIdx));
                dest.put(edges[edgeIdx].vert);
                twin = edges[edgeIdx + 1].twin;
                dest.put(twin != null ? twin.next.vert : dest.get(destIdx + 1));
                dest.put(edges[edgeIdx + 1].vert);
                twin = edges[edgeIdx + 2].twin;
                dest.put(twin != null ? twin.next.vert : dest.get(destIdx + 2));
            }
        } else {
            for (int faceIndex = 0; faceIndex < faceCount; faceIndex++) {
                edgeIdx = faceIndex * 3;
                dest.put(edges[edgeIdx + 2].vert);
                dest.put(edges[edgeIdx].twin.next.vert);
                dest.put(edges[edgeIdx].vert);
                dest.put(edges[edgeIdx + 1].twin.next.vert);
                dest.put(edges[edgeIdx + 1].vert);
                dest.put(edges[edgeIdx + 2].twin.next.vert);
            }
        }
        dest.position(destPos);
    }

}
