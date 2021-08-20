/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.util;

/**
 * Simple first-fit free-list allocator, allocating integer ranges from within a
 * given capacity.
 * 
 * @author Kai Burjack
 */
public class FreeListAllocator {
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

  public static final int DEFAULT_ALIGNMENT = 1024;
  private final OutOfCapacityCallback callback;
  private Node listStart;
  private int capacity;
  private int alignment = DEFAULT_ALIGNMENT;

  public FreeListAllocator(OutOfCapacityCallback callback) {
    this.callback = callback;
  }

  private static int roundUpToNextMultiple(int num, int factor) {
    return num + factor - 1 - (num + factor - 1) % factor;
  }

  public void setAlignment(int alignment) {
    this.alignment = alignment;
  }

  public synchronized Region allocate(int size) {
    Node n = listStart;
    int roundedSize = roundUpToNextMultiple(size, alignment);
    while (n != null) {
      if (n.len >= roundedSize) {
        Region r = new Region(n.off, size);
        n.off += roundedSize;
        n.len -= roundedSize;
        if (n.len < alignment)
          remove(n);
        return r;
      }
      if (n.next == null)
        break;
      n = n.next;
    }
    outOfCapacity(n);
    return allocate(size);
  }

  private void outOfCapacity(Node last) {
    int newCapacity = callback.onCapacityIncrease(capacity);
    insertAfter(capacity, roundUpToNextMultiple(newCapacity - capacity, alignment), last);
    capacity = newCapacity;
  }

  private void remove(Node n) {
    if (n.prev != null)
      n.prev.next = n.next;
    if (n.next != null)
      n.next.prev = n.prev;
    if (listStart == n)
      listStart = n.next;
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

  private void insertAfter(int off, int len, Node p) {
    if (p != null) {
      if (p.off + p.len == off) {
        p.len += len;
      } else {
        Node n = p.next = p.next.prev = new Node(off, len);
        n.prev = p;
      }
    } else {
      Node n = listStart = new Node(off, len);
      n.prev = p;
    }
  }

  public synchronized void free(Region reg) {
    int size = roundUpToNextMultiple(reg.len, alignment);
    Node n = listStart;
    while (n != null) {
      if (n.off > reg.off) {
        insertBefore(reg.off, size, n);
        return;
      }
      if (n.next == null)
        break;
      n = n.next;
    }
    insertAfter(reg.off, size, n);
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
