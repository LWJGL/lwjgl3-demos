/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.util;

/**
 * Simple binary-tree rectangle bin-packing.
 *
 * @author Kai Burjack
 */
public class BinPack {

    public static class Rectangle<T> {
        public T userdata;
        public int w, h;
        public Node n;

        public Rectangle(T userdata, int w, int h) {
            this.userdata = userdata;
            this.w = w;
            this.h = h;
        }

        @Override
        public String toString() {
            if (n == null)
                return "{x:?, y:?, w:" + w + ", h:" + h + "}";
            return "{x:" + n.x + ", y:" + n.y + ", w:" + w + ", h:" + h + "}";
        }
    }

    public static class Node {
        public boolean used;
        public int x, y, w, h;
        public Node down, right;
        Node(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private final Node root;

    public BinPack(int w, int h) {
        this.root = new Node(0, 0, w, h);
    }

    public boolean fit(Rectangle<?> r) {
        Node n;
        if ((n = find(root, r.w, r.h)) != null) {
            n.used  = true;
            n.down  = new Node(n.x, n.y + r.h, n.w, n.h - r.h);
            n.right = new Node(n.x + r.w, n.y, n.w - r.w, r.h);
            r.n = n;
            return true;
        }
        return false;
    }

    private Node find(Node root, int w, int h) {
        if (root.used) {
            Node n = this.find(root.right, w, h);
            if (n != null)
                return n;
            return this.find(root.down, w, h);
        }
        if (w <= root.w && h <= root.h)
            return root;
        return null;
    }

}
