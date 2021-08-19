/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.game;

/**
 * Simple first-fit free-list allocator, allocating integer ranges from within a
 * given capacity.
 * 
 * @author Kai Burjack
 */
public class Allocator {
  @FunctionalInterface
  public interface OutOfCapacityCallback {
    int onCapacityIncrease(int currentCapacity);
  }

  public static class Region {
    public final int off;
    public final int len;

    public Region(int off, int len) {
      this.off = off;
      this.len = len;
    }

    public String toString() {
      return "{off:" + off + ", len:" + len + "}";
    }
  }

  private static class Node {
    public static final int ALIGNMENT = 4096;
    private Node prev;
    private Node next;
    private int off;
    private int len;

    public Node(int off, int len) {
      this.off = off;
      this.len = len;
    }

    @Override
    public String toString() {
      return "{off: " + off + ", len: " + len + "}";
    }
  }

  private final OutOfCapacityCallback callback;
  private Node listStart;
  private Node listEnd;
  private int capacity;

  public Allocator(OutOfCapacityCallback callback) {
    this.callback = callback;
  }

  private static int roundUpToNextMultiple(int num, int factor) {
    return num + factor - 1 - (num + factor - 1) % factor;
  }

  public synchronized Region allocate(int size) {
    Node n = listStart;
    int roundedSize = roundUpToNextMultiple(size, Node.ALIGNMENT);
    while (n != null) {
      if (n.len >= roundedSize) {
        Region r = new Region(n.off, size);
        n.off += roundedSize;
        n.len -= roundedSize;
        if (n.len < Node.ALIGNMENT)
          remove(n);
        return r;
      }
      n = n.next;
    }
    outOfCapacity();
    return allocate(size);
  }

  private void outOfCapacity() {
    int newCapacity = callback.onCapacityIncrease(capacity);
    insertEnd(capacity, roundUpToNextMultiple(newCapacity - capacity, Node.ALIGNMENT));
  }

  private void remove(Node n) {
    if (n.prev != null)
      n.prev.next = n.next;
    if (n.next != null)
      n.next.prev = n.prev;
    if (listStart == n)
      listStart = n.next;
    if (listEnd == n)
      listEnd = n.prev;
  }

  private void insertBefore(int off, int len, Node p) {
    if (p.prev != null) {
      if (p.prev.off + p.prev.len == off) {
        p.prev.len += len;
      } else {
        Node n = p.prev = p.prev.next = new Node(off, len);
        n.next = p;
      }
    } else {
      Node n = listStart = p.prev = new Node(off, len);
      n.next = p;
    }
  }

  private void insertEnd(int off, int len) {
    if (listEnd != null) {
      if (listEnd.off + listEnd.len == off) {
        listEnd.len += len;
      } else {
        Node n = listEnd.next = new Node(off, len);
        n.prev = listEnd;
      }
    } else {
      listStart = listEnd = new Node(off, len);
    }
  }

  public synchronized void free(Region reg) {
    int size = roundUpToNextMultiple(reg.len, Node.ALIGNMENT);
    Node n = listStart;
    while (n != null) {
      if (n.off > reg.off) {
        insertBefore(reg.off, size, n);
        return;
      }
      n = n.next;
    }
    insertEnd(reg.off, size);
  }

  public String toString() {
    Node n = listStart;
    StringBuilder s = new StringBuilder("[");
    while (n != null) {
      s.append(n);
      if (n.next != null) {
        s.append(", ");
      }
      n = n.next;
    }
    s.append("]");
    return s.toString();
  }
}
