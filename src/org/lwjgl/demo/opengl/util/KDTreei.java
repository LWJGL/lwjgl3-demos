package org.lwjgl.demo.opengl.util;

import java.lang.Math;
import java.nio.*;
import java.util.*;
import org.joml.*;

public class KDTreei<T extends Boundable<T>> {

    public Node<T> root;
    private int maxVoxelCount = 2;
    private float nodeIntersectCosts = 1.0f;
    private float voxelIntersectCosts = 1.0f;

    private class IntervalBoundary {
        int type;
        int pos;

        IntervalBoundary(int t, int p) {
            type = t;
            pos = p;
        }

        int compareTo(IntervalBoundary sib) {
            return Integer.compare(pos, sib.pos);
        }
    }

    public static class Box implements Boundable<Box> {
        public int minX, minY, minZ;
        public int maxX, maxY, maxZ;

        public Box() {
        }

        public Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public Box(Box bbox) {
            this.minX = bbox.minX;
            this.minY = bbox.minY;
            this.minZ = bbox.minZ;
            this.maxX = bbox.maxX;
            this.maxY = bbox.maxY;
            this.maxZ = bbox.maxZ;
        }

        public float diagonal() {
            float dx = maxX - minX;
            float dy = maxY - minY;
            float dz = maxZ - minZ;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public int min(int axis) {
            switch (axis) {
            case 0:
                return minX;
            case 1:
                return minY;
            case 2:
                return minZ;
            default:
                throw new IllegalArgumentException();
            }
        }

        public int max(int axis) {
            switch (axis) {
            case 0:
                return maxX;
            case 1:
                return maxY;
            case 2:
                return maxZ;
            default:
                throw new IllegalArgumentException();
            }
        }

        public void setMax(int splitAxis, int split) {
            switch (splitAxis) {
            case 0:
                maxX = split;
                break;
            case 1:
                maxY = split;
                break;
            case 2:
                maxZ = split;
                break;
            }
        }

        public void setMin(int splitAxis, int split) {
            switch (splitAxis) {
            case 0:
                minX = split;
                break;
            case 1:
                minY = split;
                break;
            case 2:
                minZ = split;
                break;
            }
        }

        public boolean intersectsWithBox(Boundable<?> vx) {
            return maxX >= vx.min(0) && maxY >= vx.min(1) && maxZ >= vx.min(2) && minX <= vx.max(0) && minY <= vx.max(1)
                    && minZ <= vx.max(2);
        }

        @Override
        public void extend(int axis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Box splitLeft(int splitAxis, int splitPos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Box splitRight(int splitAxis, int splitPos) {
            throw new UnsupportedOperationException();
        }
    }

    public static class Voxel implements Boundable<Voxel>, Comparable<Voxel> {
        public byte x, y, z;
        public byte ex, ey, ez;
        public int morton;
        public byte paletteIndex;
        public int nindex;

        public Voxel() {
        }

        public Voxel(byte x, byte y, byte z, byte paletteIndex) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.paletteIndex = paletteIndex;
        }

        public Voxel(byte x, byte y, byte z, byte ex, byte ey, byte ez, byte paletteIndex) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            this.paletteIndex = paletteIndex;
        }

        public int min(int axis) {
            switch (axis) {
            case 0:
                return x & 0xFF;
            case 1:
                return y & 0xFF;
            case 2:
                return z & 0xFF;
            default:
                throw new IllegalArgumentException();
            }
        }

        public int max(int axis) {
            switch (axis) {
            case 0:
                return (x & 0xFF) + 1 + (ex & 0xFF);
            case 1:
                return (y & 0xFF) + 1 + (ey & 0xFF);
            case 2:
                return (z & 0xFF) + 1 + (ez & 0xFF);
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public void extend(int axis) {
            switch (axis) {
            case 0:
                this.ex++;
                break;
            case 1:
                this.ey++;
                break;
            case 2:
                this.ez++;
                break;
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public Voxel splitLeft(int axis, int pos) {
            switch (axis) {
            case 0:
                return new Voxel(x, y, z, (byte) (pos - (x & 0xFF) - 1), ey, ez, paletteIndex);
            case 1:
                return new Voxel(x, y, z, ex, (byte) (pos - (y & 0xFF) - 1), ez, paletteIndex);
            case 2:
                return new Voxel(x, y, z, ex, ey, (byte) (pos - (z & 0xFF) - 1), paletteIndex);
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public Voxel splitRight(int axis, int pos) {
            switch (axis) {
            case 0:
                return new Voxel((byte) pos, y, z, (byte) ((ex & 0xFF) - (pos - (x & 0xFF))), ey, ez, paletteIndex);
            case 1:
                return new Voxel(x, (byte) pos, z, ex, (byte) ((ey & 0xFF) - (pos - (y & 0xFF))), ez, paletteIndex);
            case 2:
                return new Voxel(x, y, (byte) pos, ex, ey, (byte) ((ez & 0xFF) - (pos - (z & 0xFF))), paletteIndex);
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public int compareTo(Voxel o) {
            return Long.compare(morton, o.morton);
        }

        @Override
        public String toString() {
            return "[" + x + ", " + y + ", " + z + "]";
        }
    }

    public static class Node<B> {
        private static final int SIDE_X_POS = 0;
        private static final int SIDE_X_NEG = 1;
        private static final int SIDE_Y_POS = 2;
        private static final int SIDE_Y_NEG = 3;
        private static final int SIDE_Z_POS = 4;
        private static final int SIDE_Z_NEG = 5;

        public int splitAxis = -1;
        public int splitPos;
        public Box boundingBox;
        public Node<B> left;
        public Node<B> right;
        public List<B> voxels = new ArrayList<>();
        public Node<B>[] ropes;
        public int index;
        public int leafIndex;
        public int first;
        public int count;

        int isParallelTo(int side) {
            switch (splitAxis) {
            case 0:
                return side == SIDE_X_NEG ? -1 : side == SIDE_X_POS ? +1 : 0;
            case 1:
                return side == SIDE_Y_NEG ? -1 : side == SIDE_Y_POS ? +1 : 0;
            case 2:
                return side == SIDE_Z_NEG ? -1 : side == SIDE_Z_POS ? +1 : 0;
            default:
                throw new IllegalArgumentException();
            }
        }

        public final boolean isLeafNode() {
            return splitAxis == -1;
        }

        @SuppressWarnings("unchecked")
        protected void processNode(Node<B>[] ropes) {
            if (isLeafNode()) {
                this.ropes = ropes;
            } else {
                int sideLeft;
                int sideRight;
                if (splitAxis == 0) {
                    sideLeft = SIDE_X_NEG;
                    sideRight = SIDE_X_POS;
                } else if (splitAxis == 1) {
                    sideLeft = SIDE_Y_NEG;
                    sideRight = SIDE_Y_POS;
                } else if (splitAxis == 2) {
                    sideLeft = SIDE_Z_NEG;
                    sideRight = SIDE_Z_POS;
                } else {
                    throw new AssertionError();
                }
                this.left.ropes = new Node[6];
                System.arraycopy(ropes, 0, this.left.ropes, 0, 6);
                this.left.ropes[sideRight] = this.right;
                this.left.processNode(this.left.ropes);
                this.right.ropes = new Node[6];
                System.arraycopy(ropes, 0, this.right.ropes, 0, 6);
                this.right.ropes[sideLeft] = this.left;
                this.right.processNode(this.right.ropes);
            }
        }

        protected void optimizeRopes() {
            for (int i = 0; i < 6; i++) {
                ropes[i] = optimizeRope(ropes[i], i);
            }
            if (left != null)
                left.optimizeRopes();
            if (right != null)
                right.optimizeRopes();
        }

        protected Node<B> optimizeRope(Node<B> rope, int side) {
            if (rope == null) {
                return rope;
            }
            Node<B> r = rope;
            while (!r.isLeafNode()) {
                int parallelSide = r.isParallelTo(side);
                if (parallelSide == +1) {
                    r = r.left;
                } else if (parallelSide == -1) {
                    r = r.right;
                } else {
                    if (r.splitPos < boundingBox.min(r.splitAxis)) {
                        r = r.right;
                    } else if (r.splitPos > boundingBox.max(r.splitAxis)) {
                        r = r.left;
                    } else {
                        break;
                    }
                }
            }
            return r;
        }

        public void frustumCull(Matrix4d viewProjection, Vector3d p, int depth, int maxDepth, int maxNodes,
                PriorityQueue<Node<B>> nodes) {
            Box b = boundingBox;
            if (nodes.size() > maxNodes)
                return;
            if (!viewProjection.testAab(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ))
                return;
            if (left == null || depth >= maxDepth) {
                if (this.count > 0)
                    nodes.add(this);
            } else if (left != null) {
                if (p.get(splitAxis) < splitPos) {
                    left.frustumCull(viewProjection, p, depth + 1, maxDepth, maxNodes, nodes);
                    right.frustumCull(viewProjection, p, depth + 1, maxDepth, maxNodes, nodes);
                } else {
                    right.frustumCull(viewProjection, p, depth + 1, maxDepth, maxNodes, nodes);
                    left.frustumCull(viewProjection, p, depth + 1, maxDepth, maxNodes, nodes);
                }
            }
        }

        public Node<B> findNode(Vector3d cameraPosition) {
            Vector3d p = cameraPosition;
            Box b = boundingBox;
            if (p.x < b.minX || p.x > b.maxX || p.y < b.minY || p.y > b.maxY || p.z < b.minZ || p.z > b.maxZ
                    || left == null)
                return this;
            if (cameraPosition.get(splitAxis) < splitPos)
                return left.findNode(cameraPosition);
            return right.findNode(cameraPosition);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Boundable<T>> KDTreei<T> build(List<T> voxels, int maxDepth) {
        return build(voxels, new Node[6], maxDepth);
    }

    public static <T extends Boundable<T>> KDTreei<T> build(List<T> voxels, Node<T>[] neighbors, int maxDepth) {
        Box b = new Box(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE);
        for (T v : voxels) {
            b.minX = b.minX < v.min(0) ? b.minX : v.min(0);
            b.minY = b.minY < v.min(1) ? b.minY : v.min(1);
            b.minZ = b.minZ < v.min(2) ? b.minZ : v.min(2);
            b.maxX = b.maxX > v.max(0) ? b.maxX : v.max(0);
            b.maxY = b.maxY > v.max(1) ? b.maxY : v.max(1);
            b.maxZ = b.maxZ > v.max(2) ? b.maxZ : v.max(2);
        }
        KDTreei<T> root = new KDTreei<T>();
        root.buildTree(voxels, b, neighbors, maxDepth);
        return root;
    }

    public void frustumCull(Matrix4d viewProjection, Vector3d p, int maxDepth, int maxNodes,
            PriorityQueue<Node<T>> nodes) {
        root.frustumCull(viewProjection, p, 0, maxDepth, maxNodes, nodes);
    }

    public Node<T> findNode(Vector3d cameraPosition) {
        return root.findNode(cameraPosition);
    }

    public void level(int level, ShortBuffer nodes) {
        level(root, level, nodes);
    }

    private void level(Node<T> n, int level, ShortBuffer nodes) {
        if (level == 0 || n.left == null) {
            nodes.put((short) n.index);
        } else {
            level(n.left, level - 1, nodes);
            level(n.right, level - 1, nodes);
        }
    }

    public void buildTree(List<T> list, Box bbox, Node<T>[] neighbors, int maxDepth) {
        if (root != null)
            root = null;
        root = new Node<T>();
        root.voxels = list;
        root.boundingBox = bbox;
        buildTree(root, 0, 0, maxDepth);
        root.processNode(root.ropes = neighbors);
        root.optimizeRopes();
    }

    private void buildTree(Node<T> node, int axis, int depth, int maxDepth) {
        if (node.voxels.size() > maxVoxelCount && depth < maxDepth) {
            node.splitAxis = axis;
            node.splitPos = findSplitPlane(node);
        } else {
            node.splitAxis = -1;
        }
        if (node.splitAxis == -1) {
            return;
        }
        if (node.voxels.size() > maxVoxelCount) {
            node.left = new Node<T>();
            node.right = new Node<T>();
            node.left.boundingBox = new Box(node.boundingBox);
            node.left.boundingBox.setMax(node.splitAxis, node.splitPos);
            node.right.boundingBox = new Box(node.boundingBox);
            node.right.boundingBox.setMin(node.splitAxis, node.splitPos);
            node.voxels.forEach(vx -> {
                if (vx.min(node.splitAxis) >= node.splitPos) {
                    node.right.voxels.add(vx);
                } else if (vx.max(node.splitAxis) <= node.splitPos) {
                    node.left.voxels.add(vx);
                } else {
                    T left = vx.splitLeft(node.splitAxis, node.splitPos);
                    T right = vx.splitRight(node.splitAxis, node.splitPos);
                    if (left.max(node.splitAxis) > node.splitPos)
                        throw new AssertionError();
                    if (right.min(node.splitAxis) < node.splitPos)
                        throw new AssertionError();
                    node.left.voxels.add(left);
                    node.right.voxels.add(right);
                }
            });
            node.voxels.clear();
            int nextAxis = (axis + 1) % 3;
            buildTree(node.left, nextAxis, depth + 1, maxDepth);
            buildTree(node.right, nextAxis, depth + 1, maxDepth);
        }
    }

    private int findSplitPlane(Node<T> node) {
        if (node == null)
            return -1;
        Box bb = node.boundingBox;
        int nPrims = node.voxels.size();
        int xw = bb.maxX - bb.minX;
        int yw = bb.maxY - bb.minY;
        int zw = bb.maxZ - bb.minZ;
        int box_width;
        int ax;
        if (xw > yw && xw > zw) {
            ax = 0;
            box_width = xw;
        } else if (yw > zw) {
            ax = 1;
            box_width = yw;
        } else {
            ax = 2;
            box_width = zw;
        }
        float inv_box_width = 1.0f / box_width;
        List<IntervalBoundary> intervals = new ArrayList<IntervalBoundary>();
        final int count = node.voxels.size();
        final int divisor = (int) Math.ceil(count / 100.0);
        nPrims /= divisor;
        for (int i = 0; i < count; i += divisor) {
            T vx = node.voxels.get(i);
            if (!bb.intersectsWithBox(vx)) {
                throw new IllegalStateException("!!! KDTree.findSplitPlane: no intersection of boxes");
            }
            intervals.add(new IntervalBoundary(0, vx.min(ax)));
            intervals.add(new IntervalBoundary(1, vx.max(ax)));
        }
        Collections.sort(intervals, (sib1, sib2) -> sib1.compareTo(sib2));
        int done_intervals = 0;
        int open_intervals = 0;
        float alpha;
        int minid = 0;
        float mincost = Float.MAX_VALUE;
        for (int i = 0; i < intervals.size(); i++) {
            IntervalBoundary in = intervals.get(i);
            if (in.type == 1) {
                open_intervals--;
                done_intervals++;
            }
            alpha = (in.pos - bb.min(ax)) * inv_box_width;
            float cost = voxelIntersectCosts + nodeIntersectCosts
                    * ((done_intervals + open_intervals) * alpha + (nPrims - done_intervals) * (1.0f - alpha));
            if (cost < mincost) {
                minid = i;
                mincost = cost;
            }
            if (in.type == 0) {
                open_intervals++;
            }
        }
        int splitPlane = intervals.get(minid).pos;
        if (splitPlane == bb.min(ax) || splitPlane == bb.max(ax)) {
            node.splitAxis = -1;
            return -1;
        }
        node.splitAxis = ax;
        return intervals.get(minid).pos;
    }

}
