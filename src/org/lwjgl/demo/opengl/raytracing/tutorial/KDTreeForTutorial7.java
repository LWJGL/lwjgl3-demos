/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.raytracing.tutorial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.joml.Vector3f;

/**
 * A very nice and actually working KD-tree implementation taken from the
 * <a href=
 * "https://graphics.cg.uni-saarland.de/fileadmin/cguds/courses/ws0809/cg/rc/web_sites/Andreas_Zins/java/KDTree.java"
 * >KDTree.java sources of University of Saarland, Germany</a>.
 * 
 * Additionally I augmented it with the ropes for <a href=
 * "https://graphics.cg.uni-saarland.de/fileadmin/cguds/papers/2007/popov_07_GPURT/Popov_et_al._-_Stackless_KD-Tree_Traversal_for_High_Performance_GPU_Ray_Tracing.pdf"
 * >Stackless KD-Tree Traversal</a> and optimized it here and there.
 * 
 * @author University of Saarland, Germany
 * @author Kai Burjack (added ropes)
 */
public class KDTreeForTutorial7 {

    private static final float EPSILON = 1E-7f;

    public Node mRootNode;
    private Box mBoundingBox;
    private int mMinPrim = 32;
    private float mSahIntCosts = 1.0f;
    private float mSahTrvCosts = 0.1f;
    private int mTriCount = 0;

    private class IntervalBoundary {
        int type;
        float pos;

        IntervalBoundary(int t, float p) {
            type = t;
            pos = p;
        }

        int compareTo(IntervalBoundary sib) {
            return Float.compare(pos, sib.pos);
        }
    }

    public static class Box {
        public Vector3f min;
        public Vector3f max;

        public Box() {
        }

        public Box(Vector3f min, Vector3f max) {
            this.min = new Vector3f(min);
            this.max = new Vector3f(max);
        }

        public Box(Box bbox) {
            this.min = new Vector3f(bbox.min);
            this.max = new Vector3f(bbox.max);
        }

        public void setMax(int splitAxis, float splitPlane) {
            max.setComponent(splitAxis, splitPlane);
        }

        public void setMin(int splitAxis, float splitPlane) {
            min.setComponent(splitAxis, splitPlane);
        }

        public boolean intersectsWithBox(Box box) {
            if (box == null) {
                return false;
            }
            if (max.x < box.min.x) {
                return false;
            }
            if (max.y < box.min.y) {
                return false;
            }
            if (max.z < box.min.z) {
                return false;
            }
            if (min.x > box.max.x) {
                return false;
            }
            if (min.y > box.max.y) {
                return false;
            }
            if (min.z > box.max.z) {
                return false;
            }
            return true;
        }
    }

    public static interface Boundable {
        Box getBounds();
    }

    public class Node {
        private static final int SIDE_X_POS = 0;
        private static final int SIDE_X_NEG = 1;
        private static final int SIDE_Y_POS = 2;
        private static final int SIDE_Y_NEG = 3;
        private static final int SIDE_Z_POS = 4;
        private static final int SIDE_Z_NEG = 5;

        public int splitAxis;
        public float splitPlane;
        public Box boundingBox;
        public Node left;
        public Node right;
        public List<Boundable> triangles;
        public Node[] ropes;

        Node() {
            left = right = null;
            splitAxis = -1;
            splitPlane = 0.0f;
            triangles = new ArrayList<Boundable>();
        }

        int isParallelTo(int side) {
            if (splitAxis == 0) {
                return side == SIDE_X_NEG ? -1 : side == SIDE_X_POS ? +1 : 0;
            } else if (splitAxis == 1) {
                return side == SIDE_Y_NEG ? -1 : side == SIDE_Y_POS ? +1 : 0;
            }
            return side == SIDE_Z_NEG ? -1 : side == SIDE_Z_POS ? +1 : 0;
        }

        public final boolean isLeafNode() {
            return splitAxis == -1;
        }

        protected void processNode(Node[] ropes) {
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

        protected Node optimizeRope(Node rope, int side) {
            if (rope == null) {
                return rope;
            }
            Node r = rope;
            while (!r.isLeafNode()) {
                int parallelSide = r.isParallelTo(side);
                if (parallelSide == +1) {
                    r = r.left;
                } else if (parallelSide == -1) {
                    r = r.right;
                } else {
                    if (r.splitPlane < boundingBox.min.get(r.splitAxis)) {
                        r = r.right;
                    } else if (r.splitPlane > boundingBox.max.get(r.splitAxis)) {
                        r = r.left;
                    } else {
                        break;
                    }
                }
            }
            return r;
        }
    }

    public void buildTree(List<Boundable> list, Box bbox) {
        long time = System.currentTimeMillis();
        if (mRootNode != null) {
            mRootNode = null;
        }
        mRootNode = new Node();
        mBoundingBox = new Box(bbox);
        mRootNode.triangles = list;
        mRootNode.boundingBox = bbox;
        mTriCount = list.size();
        buildTree(mRootNode, 0, 0);
        mRootNode.processNode(mRootNode.ropes = new Node[6]);
        mRootNode.optimizeRopes();
        statistics(time);
    }

    private void statistics(long time) {
        long lTime = System.currentTimeMillis() - time;
        String statistics = "";
        statistics += "\nKD-Tree:";
        statistics += "\n  root bounding box [" + mBoundingBox.min.toString() + " - " + mBoundingBox.max.toString()
                + "]";
        statistics += "\n  triangles (in tree) : " + mTriCount + " (" + TriangleCount(mRootNode) + ")";
        statistics += "\n  build time (ms) : " + lTime;
        statistics += "\n\n";
        System.out.print(statistics);
    }

    private int TriangleCount(Node node) {
        if (!node.isLeafNode())
            return TriangleCount(node.left) + TriangleCount(node.right);
        return node.triangles.size();
    }

    private void buildTree(Node node, int axis, int depth) {
        if (node.triangles.size() > mMinPrim) {
            node.splitAxis = axis;
            node.splitPlane = findSplitPlane(node);
        } else {
            node.splitAxis = -1;
            return;
        }
        if (node.splitAxis == -1) {
            return;
        }
        if (node.triangles.size() > mMinPrim) {
            node.left = new Node();
            node.right = new Node();
            node.left.boundingBox = new Box(node.boundingBox);
            node.left.boundingBox.setMax(node.splitAxis, node.splitPlane);
            node.right.boundingBox = new Box(node.boundingBox);
            node.right.boundingBox.setMin(node.splitAxis, node.splitPlane);
            for (int i = 0; i < node.triangles.size(); i++) {
                Box box = node.triangles.get(i).getBounds();
                if (box.min.get(node.splitAxis) > node.splitPlane) {
                    node.right.triangles.add(node.triangles.get(i));
                } else {
                    if (box.max.get(node.splitAxis) < node.splitPlane) {
                        node.left.triangles.add(node.triangles.get(i));
                    } else {
                        node.left.triangles.add(node.triangles.get(i));
                        node.right.triangles.add(node.triangles.get(i));
                    }
                }
            }
            node.triangles.clear();
            int nextAxis = (axis + 1) % 3;
            buildTree(node.left, nextAxis, depth + 1);
            buildTree(node.right, nextAxis, depth + 1);
            return;
        }
        node.splitAxis = -1;
    }

    private float findSplitPlane(Node node) {
        if (node == null) {
            return Float.POSITIVE_INFINITY;
        }
        Box bb = node.boundingBox;
        int nPrims = node.triangles.size();
        Vector3f costvector = new Vector3f(bb.max).sub(bb.min);
        int ax = costvector.maxComponent();
        float box_width = costvector.get(ax);
        if (box_width <= EPSILON) {
            node.splitAxis = -1;
            return Float.POSITIVE_INFINITY;
        }
        float inv_box_width = 1.0f / box_width;
        if (box_width <= EPSILON) {
            throw new IllegalStateException("!!! KDTree.findSplitPlane: box to small");
        }
        List<IntervalBoundary> intervals = new ArrayList<IntervalBoundary>(nPrims * 2);
        for (int i = 0; i < nPrims; i++) {
            Box b = node.triangles.get(i).getBounds();
            if (!bb.intersectsWithBox(b)) {
                throw new IllegalStateException("!!! KDTree.findSplitPlane: no intersection of boxes");
            }
            intervals.add(new IntervalBoundary(0, b.min.get(ax)));
            intervals.add(new IntervalBoundary(1, b.max.get(ax)));
        }
        Collections.sort(intervals, new Comparator<IntervalBoundary>() {
            public int compare(IntervalBoundary sib1, IntervalBoundary sib2) {
                return sib1.compareTo(sib2);
            }
        });
        int done_intervals = 0;
        int open_intervals = 0;
        float alpha;
        int minid = 0;
        float mincost = Float.MAX_VALUE;
        for (int i = 0; i < intervals.size(); i++) {
            if (intervals.get(i).type == 1) {
                open_intervals--;
                done_intervals++;
            }
            alpha = (intervals.get(i).pos - bb.min.get(ax)) * inv_box_width;
            float cost = mSahTrvCosts + mSahIntCosts
                    * ((done_intervals + open_intervals) * alpha + (nPrims - done_intervals) * (1.0f - alpha));
            if (cost < mincost) {
                minid = i;
                mincost = cost;
            }
            if (intervals.get(i).type == 0) {
                open_intervals++;
            }
        }
        float splitPlane = intervals.get(minid).pos;
        if (splitPlane == bb.min.get(ax) || splitPlane == bb.max.get(ax)) {
            node.splitAxis = -1;
            return Float.POSITIVE_INFINITY;
        }
        node.splitAxis = ax;
        return intervals.get(minid).pos;
    }

    public int TriangleCount() {
        return mTriCount;
    }
}
