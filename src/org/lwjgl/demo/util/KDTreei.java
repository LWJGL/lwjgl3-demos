package org.lwjgl.demo.util;

import java.util.*;

import org.joml.Vector3d;

public class KDTreei<T extends Boundable<T>> {

    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;

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

        @Override
        public int min(int axis) {
            switch (axis) {
            case X:
                return minX;
            case Y:
                return minY;
            case Z:
                return minZ;
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public int max(int axis) {
            switch (axis) {
            case X:
                return maxX;
            case Y:
                return maxY;
            case Z:
                return maxZ;
            default:
                throw new IllegalArgumentException();
            }
        }

        public void setMax(int splitAxis, int split) {
            switch (splitAxis) {
            case X:
                maxX = split;
                break;
            case Y:
                maxY = split;
                break;
            case Z:
                maxZ = split;
                break;
            }
        }

        public void setMin(int splitAxis, int split) {
            switch (splitAxis) {
            case X:
                minX = split;
                break;
            case Y:
                minY = split;
                break;
            case Z:
                minZ = split;
                break;
            }
        }

        @Override
        public boolean intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return this.maxX >= minX && this.maxY >= minY && this.maxZ >= minZ && this.minX <= maxX && this.minY <= maxY
                    && this.minZ <= maxZ;
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

    public static class Voxel implements Boundable<Voxel> {
        public byte x, y, z;
        public byte ex, ey, ez;
        public byte paletteIndex;
        public byte sides;
        public int nindex;

        public Voxel(int x, int y, int z, int paletteIndex) {
            this.x = (byte) x;
            this.y = (byte) y;
            this.z = (byte) z;
            this.paletteIndex = (byte) paletteIndex;
        }

        public Voxel(int x, int y, int z, int paletteIndex, int sides) {
            this.x = (byte) x;
            this.y = (byte) y;
            this.z = (byte) z;
            this.paletteIndex = (byte) paletteIndex;
            this.sides = (byte) sides;
        }

        public Voxel(int x, int y, int z, int ex, int ey, int ez, int paletteIndex) {
            this.x = (byte) x;
            this.y = (byte) y;
            this.z = (byte) z;
            this.ex = (byte) ex;
            this.ey = (byte) ey;
            this.ez = (byte) ez;
            this.paletteIndex = (byte) paletteIndex;
        }

        public Voxel(int x, int y, int z, int ex, int ey, int ez, int paletteIndex, int sides) {
            this.x = (byte) x;
            this.y = (byte) y;
            this.z = (byte) z;
            this.ex = (byte) ex;
            this.ey = (byte) ey;
            this.ez = (byte) ez;
            this.paletteIndex = (byte) paletteIndex;
            this.sides = (byte) sides;
        }

        @Override
        public int min(int axis) {
            switch (axis) {
            case X:
                return x & 0xFF;
            case Y:
                return y & 0xFF;
            case Z:
                return z & 0xFF;
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public int max(int axis) {
            switch (axis) {
            case X:
                return (x & 0xFF) + 1 + (ex & 0xFF);
            case Y:
                return (y & 0xFF) + 1 + (ey & 0xFF);
            case Z:
                return (z & 0xFF) + 1 + (ez & 0xFF);
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public Voxel splitLeft(int axis, int pos) {
            switch (axis) {
            case X:
                return new Voxel(x, y, z, (byte) (pos - (x & 0xFF) - 1), ey, ez, paletteIndex);
            case Y:
                return new Voxel(x, y, z, ex, (byte) (pos - (y & 0xFF) - 1), ez, paletteIndex);
            case Z:
                return new Voxel(x, y, z, ex, ey, (byte) (pos - (z & 0xFF) - 1), paletteIndex);
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public Voxel splitRight(int axis, int pos) {
            switch (axis) {
            case X:
                return new Voxel((byte) pos, y, z, (byte) ((ex & 0xFF) - (pos - (x & 0xFF))), ey, ez, paletteIndex);
            case Y:
                return new Voxel(x, (byte) pos, z, ex, (byte) ((ey & 0xFF) - (pos - (y & 0xFF))), ez, paletteIndex);
            case Z:
                return new Voxel(x, y, (byte) pos, ex, ey, (byte) ((ez & 0xFF) - (pos - (z & 0xFF))), paletteIndex);
            default:
                throw new IllegalArgumentException();
            }
        }

        @Override
        public boolean intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return max(X) >= minX && max(Y) >= minY && max(Z) >= minZ && min(X) <= maxX && min(Y) <= maxY
                    && min(Z) <= maxZ;
        }

        @Override
        public String toString() {
            return "[" + x + ", " + y + ", " + z + "]";
        }
    }

    public static class Node<B extends Boundable<B>> {
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
        public List<B> boundables = new ArrayList<>();
        public Node<B>[] ropes;
        public int index;
        public int leafIndex;
        public int first;
        public int count;

        private int isParallelTo(int side) {
            switch (splitAxis) {
            case X:
                return side == SIDE_X_NEG ? -1 : side == SIDE_X_POS ? +1 : 0;
            case Y:
                return side == SIDE_Y_NEG ? -1 : side == SIDE_Y_POS ? +1 : 0;
            case Z:
                return side == SIDE_Z_NEG ? -1 : side == SIDE_Z_POS ? +1 : 0;
            default:
                throw new IllegalArgumentException();
            }
        }

        private final boolean isLeafNode() {
            return splitAxis == -1;
        }

        @SuppressWarnings("unchecked")
        private void processNode(Node<B>[] ropes) {
            if (isLeafNode()) {
                this.ropes = ropes;
            } else {
                int sideLeft;
                int sideRight;
                if (splitAxis == X) {
                    sideLeft = SIDE_X_NEG;
                    sideRight = SIDE_X_POS;
                } else if (splitAxis == Y) {
                    sideLeft = SIDE_Y_NEG;
                    sideRight = SIDE_Y_POS;
                } else if (splitAxis == Z) {
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

        private void optimizeRopes() {
            for (int i = 0; i < 6; i++) {
                ropes[i] = optimizeRope(ropes[i], i);
            }
            if (left != null)
                left.optimizeRopes();
            if (right != null)
                right.optimizeRopes();
        }

        private Node<B> optimizeRope(Node<B> rope, int side) {
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

        private Node<B> findNode(Vector3d cameraPosition) {
            Vector3d p = cameraPosition;
            Box b = boundingBox;
            if (p.x < b.minX || p.x > b.maxX || p.y < b.minY || p.y > b.maxY || p.z < b.minZ || p.z > b.maxZ
                    || left == null)
                return this;
            if (cameraPosition.get(splitAxis) < splitPos)
                return left.findNode(cameraPosition);
            return right.findNode(cameraPosition);
        }

        private void intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                List<B> boundables) {
            Box b = boundingBox;
            if (!b.intersects(minX, minY, minZ, maxX, maxY, maxZ))
                return;
            if (isLeafNode()) {
                for (B voxel : boundables) {
                    if (!voxel.intersects(minX, minY, minZ, maxX, maxY, maxZ))
                        continue;
                    boundables.add(voxel);
                }
            } else {
                left.intersects(minX, minY, minZ, maxX, maxY, maxZ, boundables);
                right.intersects(minX, minY, minZ, maxX, maxY, maxZ, boundables);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Boundable<T>> KDTreei<T> build(List<T> boundables, int maxDepth) {
        return build(boundables, new Node[6], maxDepth);
    }

    private static <T extends Boundable<T>> KDTreei<T> build(List<T> boundables, Node<T>[] neighbors, int maxDepth) {
        Box b = new Box(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE);
        for (T v : boundables) {
            b.minX = b.minX < v.min(X) ? b.minX : v.min(X);
            b.minY = b.minY < v.min(Y) ? b.minY : v.min(Y);
            b.minZ = b.minZ < v.min(Z) ? b.minZ : v.min(Z);
            b.maxX = b.maxX > v.max(X) ? b.maxX : v.max(X);
            b.maxY = b.maxY > v.max(Y) ? b.maxY : v.max(Y);
            b.maxZ = b.maxZ > v.max(Z) ? b.maxZ : v.max(Z);
        }
        KDTreei<T> root = new KDTreei<T>();
        root.buildTree(boundables, b, neighbors, maxDepth);
        return root;
    }

    public void intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, List<T> boundables) {
        root.intersects(minX, minY, minZ, maxX, maxY, maxZ, boundables);
    }

    public Node<T> findNode(Vector3d cameraPosition) {
        return root.findNode(cameraPosition);
    }

    private void buildTree(List<T> list, Box bbox, Node<T>[] neighbors, int maxDepth) {
        if (root != null)
            root = null;
        root = new Node<T>();
        root.boundables = list;
        root.boundingBox = bbox;
        buildTree(root, 0, 0, maxDepth);
        root.processNode(root.ropes = neighbors);
        root.optimizeRopes();
    }

    private void buildTree(Node<T> node, int axis, int depth, int maxDepth) {
        if (node.boundables.size() > maxVoxelCount && depth < maxDepth) {
            node.splitAxis = axis;
            node.splitPos = findSplitPlane(node);
        } else {
            node.splitAxis = -1;
        }
        if (node.splitAxis == -1) {
            return;
        }
        if (node.boundables.size() > maxVoxelCount) {
            node.left = new Node<T>();
            node.right = new Node<T>();
            node.left.boundingBox = new Box(node.boundingBox);
            node.left.boundingBox.setMax(node.splitAxis, node.splitPos);
            node.right.boundingBox = new Box(node.boundingBox);
            node.right.boundingBox.setMin(node.splitAxis, node.splitPos);
            node.boundables.forEach(vx -> {
                if (vx.min(node.splitAxis) >= node.splitPos) {
                    node.right.boundables.add(vx);
                } else if (vx.max(node.splitAxis) <= node.splitPos) {
                    node.left.boundables.add(vx);
                } else {
                    T left = vx.splitLeft(node.splitAxis, node.splitPos);
                    T right = vx.splitRight(node.splitAxis, node.splitPos);
                    if (left.max(node.splitAxis) > node.splitPos)
                        throw new AssertionError();
                    if (right.min(node.splitAxis) < node.splitPos)
                        throw new AssertionError();
                    node.left.boundables.add(left);
                    node.right.boundables.add(right);
                }
            });
            node.boundables.clear();
            int nextAxis = (axis + 1) % 3;
            buildTree(node.left, nextAxis, depth + 1, maxDepth);
            buildTree(node.right, nextAxis, depth + 1, maxDepth);
        }
    }

    private int findSplitPlane(Node<T> node) {
        if (node == null)
            return -1;
        Box bb = node.boundingBox;
        int nPrims = node.boundables.size();
        int xw = bb.maxX - bb.minX;
        int yw = bb.maxY - bb.minY;
        int zw = bb.maxZ - bb.minZ;
        int box_width;
        int ax;
        if (xw > yw && xw > zw) {
            ax = X;
            box_width = xw;
        } else if (yw > zw) {
            ax = Y;
            box_width = yw;
        } else {
            ax = Z;
            box_width = zw;
        }
        float inv_box_width = 1.0f / box_width;
        List<IntervalBoundary> intervals = new ArrayList<IntervalBoundary>();
        final int count = node.boundables.size();
        final int divisor = (int) Math.ceil(count / 100.0);
        nPrims /= divisor;
        for (int i = 0; i < count; i += divisor) {
            T vx = node.boundables.get(i);
            if (!bb.intersects(vx.min(X), vx.min(Y), vx.min(Z), vx.max(X), vx.max(Y), vx.max(Z))) {
                throw new IllegalStateException("!!! KDTree.findSplitPlane: no intersection of boxes");
            }
            intervals.add(new IntervalBoundary(0, vx.min(ax)));
            intervals.add(new IntervalBoundary(1, vx.max(ax)));
        }
        Collections.sort(intervals, IntervalBoundary::compareTo);
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
