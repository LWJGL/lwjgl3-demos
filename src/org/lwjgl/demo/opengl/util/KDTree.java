/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.opengl.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.joml.Vector3f;

/**
 * A very nice and actually working KD-tree implementation taken from the <a href=
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
public class KDTree {

    private static final float EPSILON = 1E-7f;

    // root node of the tree
    public Node mRootNode;
    // bounding box of the whole tree
    public Box mBoundingBox;

    // split strategy
    public Split mSplitStrategy = Split.SAH;

    // KDTree parameters:
    // min primitves in node
    public int mMinPrim = 5;
    // max tree depth
    public int mMaxDepth = 10;

    // SAH parameters:
    // sampling resolution
    public int mSahRes = 16;
    // intersection costs
    public float mSahIntCosts = 4.0f;
    // terversal costs
    public float mSahTrvCosts = 2.0f;
    // threshold
    public int mSahThreshold = 6400;

    // for statistics
    private int mTriCount = 0;

    public enum Axis {
        X_AXIS(0), Y_AXIS(1), Z_AXIS(2), NO_AXIS(-1);
        public final int dim;

        Axis(int dim) {
            this.dim = dim;
        }
    }

    public enum Split {
        MEAN, MEDIAN, SAH
    }

    private enum BoundaryType {
        LOWER_BOUND, UPPER_BOUND
    }

    private class IntervalBoundary {
        BoundaryType type;
        float pos;

        IntervalBoundary(BoundaryType t, float p) {
            type = t;
            pos = p;
        }

        int compareTo(IntervalBoundary sib) {
            return Float.compare(pos, sib.pos);
        }
    }

    public static class Ray {
        public Vector3f org;
        public Vector3f dir;
        public float t;
        public Object hit;
        public float u;
        public float v;
        public Object inObject;
        public Object rec;

        public Ray() {
            super();
        }

        public Ray(Ray ray) {
            this.org = new Vector3f(ray.org);
            this.dir = new Vector3f(ray.dir);
        }
    }

    private static float Vector3f_get(Vector3f v, int dim) {
        switch (dim) {
        case 0:
            return v.x;
        case 1:
            return v.y;
        case 2:
            return v.z;
        default:
            throw new IllegalArgumentException();
        }
    }

    private static int Vector3f_maxDimension(Vector3f v) {
        if (Math.abs(v.x) >= Math.abs(v.y) && Math.abs(v.x) >= Math.abs(v.z)) {
            return 0;
        } else if (Math.abs(v.y) >= Math.abs(v.z)) {
            return 1;
        }
        return 2;
    }

    private static Vector3f Vector3f_set(Vector3f v, int dim, float splitPlane) {
        switch (dim) {
        case 0:
            v.x = splitPlane;
            break;
        case 1:
            v.y = splitPlane;
            break;
        case 2:
            v.z = splitPlane;
            break;
        default:
            throw new IllegalArgumentException();
        }
        return v;
    }

    public static class Box {
        public Vector3f min;
        public Vector3f max;

        public Box() {
        }

        public Box(Box bbox) {
            this.min = new Vector3f(bbox.min);
            this.max = new Vector3f(bbox.max);
        }

        public void setMax(Axis splitAxis, float splitPlane) {
            Vector3f_set(max, splitAxis.dim, splitPlane);
        }

        public void setMin(Axis splitAxis, float splitPlane) {
            Vector3f_set(min, splitAxis.dim, splitPlane);
        }

        public boolean intersectsWithBox(Box box) {
            if (box == null) {
                return false;
            }

            // Use up to 6 separating planes
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

            // otherwise, must be intersecting
            return true;
        }
    }

    public static class Triangle {
        public Vector3f v0;
        public Vector3f v1;
        public Vector3f v2;
        public Box bounds;

        public Box getBounds() {
            if (bounds != null)
                return bounds;
            bounds = new Box();
            bounds.min = new Vector3f(v0).min(v1).min(v2);
            bounds.max = new Vector3f(v0).max(v1).max(v2);
            return bounds;
        }
    }

    // subclass representing a node in the tree
    public class Node {
        private static final int SIDE_X_POS = 0;
        private static final int SIDE_X_NEG = 1;
        private static final int SIDE_Y_POS = 2;
        private static final int SIDE_Y_NEG = 3;
        private static final int SIDE_Z_POS = 4;
        private static final int SIDE_Z_NEG = 5;

        public Axis splitAxis;
        public float splitPlane;
        public Box boundingBox;
        public Node left;
        public Node right;
        public List<Triangle> triangles;
        public Node[] ropes;

        // setup default values
        Node() {
            left = right = null;
            splitAxis = Axis.NO_AXIS;
            splitPlane = 0.0f;
            triangles = new ArrayList<Triangle>();
        }

        int isParallelTo(int side) {
            if (splitAxis == Axis.X_AXIS) {
                return side == SIDE_X_NEG ? -1 : side == SIDE_X_POS ? +1 : 0;
            } else if (splitAxis == Axis.Y_AXIS) {
                return side == SIDE_Y_NEG ? -1 : side == SIDE_Y_POS ? +1 : 0;
            }
            return side == SIDE_Z_NEG ? -1 : side == SIDE_Z_POS ? +1 : 0;
        }

        // check whenever the node is a leaf node
        public final boolean isLeafNode() {
            return splitAxis.equals(Axis.NO_AXIS);
        }

        protected void processNode(Node[] ropes) {
            if (isLeafNode()) {
                this.ropes = ropes;
            } else {
                int sideLeft;
                int sideRight;
                if (splitAxis == Axis.X_AXIS) {
                    sideLeft = SIDE_X_NEG;
                    sideRight = SIDE_X_POS;
                } else if (splitAxis == Axis.Y_AXIS) {
                    sideLeft = SIDE_Y_NEG;
                    sideRight = SIDE_Y_POS;
                } else if (splitAxis == Axis.Z_AXIS) {
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
            /* Optimize ropes */
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
                    /*
                     * The split plane is on the right/positive side of the rope, so connect to its left child!
                     */
                    r = r.left;
                } else if (parallelSide == -1) {
                    /*
                     * The split plane is on the left/negative side of the rope, so connect to its right child!
                     */
                    r = r.right;
                } else {
                    if (r.splitPlane < Vector3f_get(boundingBox.min, r.splitAxis.dim)) {
                        /*
                         * The split plane is below our AABB min point. So, choose the right/positive child.
                         */
                        r = r.right;
                    } else if (r.splitPlane > Vector3f_get(boundingBox.max, r.splitAxis.dim)) {
                        /*
                         * The split plane is above our AABB max point. So, choose the left/negative child.
                         */
                        r = r.left;
                    } else {
                        break;
                    }
                }
            }
            return r;
        }
    }

    /**
     * Build the kd-tree from the given Triangle list.
     **/
    public void buildTree(List<Triangle> list, Box bbox) {
        long time = System.currentTimeMillis();

        // first deleter root node, so that the tree is rebuild
        if (mRootNode != null) {
            mRootNode = null;
        }

        // create empty root node
        mRootNode = new Node();

        // reset the bounding box
        mBoundingBox = new Box(bbox);

        // copy Triangles
        mRootNode.triangles = list;
        mRootNode.boundingBox = bbox;
        mTriCount = list.size();

        // create the tree recursively, the children start as y-axis
        buildTree(mRootNode, Axis.X_AXIS, 0);
        // Build ropes
        mRootNode.processNode(mRootNode.ropes = new Node[6]);
        // Optimize ropes
        mRootNode.optimizeRopes();

        statistics(time);
    }

    /**
     * Initialize KDTree
     */
    public KDTree() {
        mRootNode = null;
    }

    private void statistics(long time) {
        if (mRootNode == null) {
            return;
        }

        long lTime = System.currentTimeMillis() - time;

        String statistics = "";

        statistics += "\nKD-Tree:";
        statistics += "\n  root bounding box [" + mBoundingBox.min.toString() + " - " + mBoundingBox.max.toString()
                + "]";
        statistics += "\n  split strategy : " + mSplitStrategy.name();
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

    // recursive tree building method
    private void buildTree(Node node, Axis axis, int depth) {
        // just for debug
        if (node == null || node.left != null || node.right != null) {
            throw new IllegalStateException("!!! KDTree.buildTree: broken tree");
        }

        // setup node's split axis and plane
        if (node.triangles.size() > mMinPrim) {
            node.splitAxis = axis;
            node.splitPlane = findSplitPlane(node);
        } else {
            node.splitAxis = Axis.NO_AXIS;
            return;
        }

        // maybe FindSplitPlane found out that it's better to not split anymore
        if (node.splitAxis.equals(Axis.NO_AXIS)) {
            return;
        }

        // do only subdivide current node if Triangle number is still over the maximum
        if (node.triangles.size() > mMinPrim && depth < mMaxDepth) {
            // create both childrens
            node.left = new Node();
            node.right = new Node();

            // initiate their bounding box
            node.left.boundingBox = new Box(node.boundingBox);
            node.left.boundingBox.setMax(node.splitAxis, node.splitPlane);
            node.right.boundingBox = new Box(node.boundingBox);
            node.right.boundingBox.setMin(node.splitAxis, node.splitPlane);

            // check based on the split plane where to put the Triangles
            for (int i = 0; i < node.triangles.size(); i++) {
                Box box = node.triangles.get(i).getBounds();

                // if Triangle lies completly on the right side
                if (Vector3f_get(box.min, node.splitAxis.dim) >= node.splitPlane) {
                    node.right.triangles.add(node.triangles.get(i));

                    // if Triangle lies completly on the left side
                } else {
                    if (Vector3f_get(box.max, node.splitAxis.dim) <= node.splitPlane) {
                        node.left.triangles.add(node.triangles.get(i));

                        // for the rest of cases we just put the Triangle in both subtrees
                    } else {
                        node.left.triangles.add(node.triangles.get(i));
                        node.right.triangles.add(node.triangles.get(i));
                    }
                }
            }

            // clear the node's Triangle list, since now the childrens do contain them
            node.triangles.clear();

            Axis nextAxis = Axis.values()[(axis.ordinal() + 1) % 3];

            // setup the subtrees
            buildTree(node.left, nextAxis, depth + 1);
            buildTree(node.right, nextAxis, depth + 1);

            // do return
            return;
        }

        // we can only be here, if we have reached a leaf node
        node.splitAxis = Axis.NO_AXIS;
    }

    // find optimal split
    private float findSplitPlane(Node node) {
        if (node == null) {
            return Float.POSITIVE_INFINITY;
        }
        Split strategy = mSplitStrategy;

        // use arithmetic average
        if (strategy.equals(Split.MEAN)) {
            float avg = 0.0f;
            for (int i = 0; i < node.triangles.size(); i++) {
                Box bounds = node.triangles.get(i).getBounds();
                avg += (Vector3f_get(bounds.min, node.splitAxis.dim) + Vector3f_get(bounds.max, node.splitAxis.dim)) * 0.5f;
            }
            avg /= node.triangles.size();
            return avg;
        }

        // use median
        if (strategy.equals(Split.MEDIAN)) {
            List<Float> vecIntervalMeans = new ArrayList<Float>(node.triangles.size());
            for (int i = 0; i < node.triangles.size(); i++) {
                Box bounds = node.triangles.get(i).getBounds();
                vecIntervalMeans.add(Float.valueOf(0.5f * (Vector3f_get(bounds.min, node.splitAxis.dim) + Vector3f_get(bounds.max, node.splitAxis.dim))));
            }
            Collections.sort(vecIntervalMeans);
            return vecIntervalMeans.get(vecIntervalMeans.size() / 2).floatValue();
        }

        // use surface area heuristic
        if (strategy.equals(Split.SAH)) {
            Box bb = node.boundingBox;
            int nPrims = node.triangles.size();

            // depending on the number of Triangles we use sampling or
            // do a complete scan
            if (nPrims > mSahThreshold) {
                // sampleing
                Vector3f costvector = new Vector3f(bb.max).sub(bb.min);
                int ax = Vector3f_maxDimension(costvector);
                float box_width = Vector3f_get(bb.max, ax) - Vector3f_get(bb.min, ax);
                if (box_width <= EPSILON) {
                    node.splitAxis = Axis.NO_AXIS;
                    return Float.POSITIVE_INFINITY;
                }
                float inv_box_width = 1.0f / box_width;

                if (box_width < 0.0f) {
                    throw new IllegalStateException("!!! KDTree.findSplitPlane: invalid box width < 0");
                }

                // triangle counts for histogram
                int[] p = new int[mSahRes];
                int[] h = new int[mSahRes];
                int[] p_rtl = new int[mSahRes];

                float triangleLeftEdge;

                // build histogram
                for (int i = 0; i < nPrims; i++) {
                    Box bounds = node.triangles.get(i).getBounds();
                    triangleLeftEdge = Math.min(Vector3f_get(bb.max, ax),
                            Math.max(Vector3f_get(bb.min, ax), (Vector3f_get(bounds.min, ax) + Vector3f_get(bounds.max, ax)) * 0.5f))
                            - Vector3f_get(bb.min, ax);
                    if (!bb.intersectsWithBox(bounds)) {
                        throw new IllegalStateException("!!! KDTree.findSplitPlane: no intersection of boxes!");
                    }
                    if (triangleLeftEdge < 0.0 || inv_box_width * triangleLeftEdge > 1.0f + EPSILON) {
                        throw new IllegalStateException("!!! KDTree.findSplitPlane: triangleLeftEdge invalid : "
                                + triangleLeftEdge + ", " + (inv_box_width * triangleLeftEdge));
                    }
                    float hf = inv_box_width * (triangleLeftEdge - EPSILON) * (mSahRes - 1.0f);
                    h[(int) hf] += 1;
                }

                // copy p for right-to-left lookup
                for (int i = 0; i < mSahRes - 1; i++) {
                    p[i] = h[i + 1];
                }
                for (int i = 0; i < mSahRes - 1; i++) {
                    p_rtl[i] = p[i + 1];
                }

                p_rtl[mSahRes - 1] = 0;
                // convert p and p_rtl into cumulative quantities
                for (int i = 1; i < mSahRes; i++) {
                    p[i] += p[i - 1];
                }
                for (int i = mSahRes - 2; i >= 0; i--) {
                    p_rtl[i] += p_rtl[i + 1];
                }

                // splitting costs
                float[] costs = new float[mSahRes - 1];

                // determine costs
                for (int i = 0; i < mSahRes - 1; i++) {
                    costs[i] = mSahTrvCosts + mSahIntCosts * ((i + 1) * p[i] + (mSahRes - i - 1) * p_rtl[i]) / mSahRes;
                }

                int minid = 0;
                float minval = costs[0];

                // find minimum
                for (int i = 0; i < mSahRes - 1; i++) {
                    if (minval > costs[i]) {
                        minval = costs[i];
                        minid = i;
                    }
                }

                float splitPosition = ((minid + 1.0f) / mSahRes) * box_width + Vector3f_get(bb.min, ax);

                node.splitAxis = Axis.values()[ax];
                return splitPosition;
            }
            // complete scan
            Vector3f costvector = new Vector3f(bb.max).sub(bb.min);
            int ax = Vector3f_maxDimension(costvector);
            float box_width = Vector3f_get(costvector, ax);

            // only split if it makes sense
            if (box_width <= EPSILON) {
                node.splitAxis = Axis.NO_AXIS;
                return Float.POSITIVE_INFINITY;
            }

            float inv_box_width = 1.0f / box_width;

            if (box_width <= EPSILON) {
                throw new IllegalStateException("!!! KDTree.findSplitPlane: box to small");
            }
            List<IntervalBoundary> intervals = new ArrayList<IntervalBoundary>();

            // find splitpositions
            for (int i = 0; i < nPrims; i++) {
                Box b = node.triangles.get(i).getBounds();
                if (!bb.intersectsWithBox(b)) {
                    throw new IllegalStateException("!!! KDTree.findSplitPlane: no intersection of boxes");
                }
                intervals.add(new IntervalBoundary(BoundaryType.LOWER_BOUND, Vector3f_get(b.min, ax)));
                intervals.add(new IntervalBoundary(BoundaryType.UPPER_BOUND, Vector3f_get(b.max, ax)));
            }

            Collections.sort(intervals, new Comparator<IntervalBoundary>() {
                @Override
                public int compare(IntervalBoundary sib1, IntervalBoundary sib2) {
                    return sib1.compareTo(sib2);
                }
            });

            int done_intervals = 0;
            int open_intervals = 0;
            float alpha;

            // find minimum cost
            int minid = 0;
            float mincost = Float.MAX_VALUE;
            for (int i = 0; i < intervals.size(); i++) {
                if (intervals.get(i).type.equals(BoundaryType.UPPER_BOUND)) {
                    open_intervals--;
                    done_intervals++;
                }
                alpha = (intervals.get(i).pos - Vector3f_get(bb.min, ax)) * inv_box_width;
                float cost = mSahTrvCosts + mSahIntCosts
                        * ((done_intervals + open_intervals) * alpha + (nPrims - done_intervals) * (1.0f - alpha));
                if (cost < mincost) {
                    minid = i;
                    mincost = cost;
                }
                if (intervals.get(i).type.equals(BoundaryType.LOWER_BOUND)) {
                    open_intervals++;
                }
            }
            float splitPlane = intervals.get(minid).pos;

            // no cuts at the boundaries
            if (splitPlane == Vector3f_get(bb.min, ax) || splitPlane == Vector3f_get(bb.max, ax)) {
                node.splitAxis = Axis.NO_AXIS;
                return Float.POSITIVE_INFINITY;
            }
            node.splitAxis = Axis.values()[ax];
            return intervals.get(minid).pos;
        }

        throw new IllegalStateException("!!! KDTree.findSplitPlane: invalid value");
    }

    public int TriangleCount() {
        return mTriCount;
    }
}
