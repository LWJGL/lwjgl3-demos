/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

/**
 * The image we write to.
 */
layout(binding = 0, rgba8) writeonly uniform image2D framebufferImage;

/*
 * Camera frustum description known from previous tutorials.
 */
uniform vec3 eye, ray00, ray01, ray10, ray11;

/**
 * This describes the structure of a single BVH node.
 * We will read this from the "Nodes" SSBO (see below).
 */
struct node {
  vec3 min; int left;
  vec3 max; int right;
  int parent, firstTri, numTris;
};
layout(std430, binding = 0) readonly buffer Nodes {
  node[] nodes;
};

/**
 * Describes a single triangle with position and normal.
 */
struct triangle {
  vec3 v0, v1, v2;
  vec3 n0, n1, n2;
};
layout(std430, binding = 1) readonly buffer Triangles {
  triangle[] triangles;
};

/**
 * Forward-declaration of the same structure in geometry.glsl. 
 */
struct trianglehitinfo {
  float t, u, v; // <- u, v = barycentric coordinates
};
bool intersectTriangle(vec3 origin, vec3 dir, vec3 v0, vec3 v1, vec3 v2, out trianglehitinfo thinfo);
bool intersectBox(vec3 origin, vec3 invdir, vec3 boxMin, vec3 boxMax, out float t);

#define NO_INTERSECTION -1

/**
 * Intersect all triangles in the given BVH node 'n' and return the 
 * information about the closest triangle into 'thinfo'.
 *
 * @param origin the ray's origin
 * @param dir the ray's direction
 * @param n the node whose triangles to check
 * @param t the upper bound on the distances of any triangle
 * @param[out] thinfo will store the information of the closest triangle
 * @returns the index of the closest triangle into the 'Triangles' SSBO
 *          or NO_INTERSECTION if there was no intersection
 */
int intersectTriangles(vec3 origin, vec3 dir, const node n, float t, out trianglehitinfo thinfo) {
  int idx = NO_INTERSECTION;
  for (int i = n.firstTri; i < n.firstTri + n.numTris; i++) {
    const triangle tri = triangles[i];
    trianglehitinfo info;
    if (intersectTriangle(origin, dir, tri.v0, tri.v1, tri.v2, info) && info.t < t) {
      thinfo.u = info.u;
      thinfo.v = info.v;
      thinfo.t = info.t;
      t = info.t;
      idx = i;
    }
  }
  return idx;
}

/**
 * Compute the interpolated normal of the given triangle.
 *
 * @param thinfo the information returned by 'intersectTriangles()'
 * @param tridx the index of that triangle into the 'Triangles' SSBO
 * @returns the interpolated normal
 */
vec3 normalForTriangle(trianglehitinfo thinfo, int tridx) {
  /*
   * Use the barycentric coordinates in the thinfo to weight the
   * vertices normals accordingly.
   */
  const triangle tri = triangles[tridx];
  float u = thinfo.u, v = thinfo.v, w = 1.0 - u - v;
  return vec3(tri.n0 * w + tri.n1 * u + tri.n2 * v);
}

#define NO_NODE -1
#define ERROR_COLOR vec3(1.0, 0.0, 1.0)
#define MAX_FOLLOWS 150

/**
 * Determine the child of the given node 'n' whose centroid position is closest
 * to the given 'origin'.
 *
 * @param origin the ray's origin
 * @param n      the node whose children to check
 * @param[out] leftRight will hold 0 for 'left' or 1 for 'right'
 * @returns the node index of the nearest child
 */
int closestChild(vec3 origin, const node n, out uint leftRight) {
  if (n.left == NO_NODE)
    return NO_NODE;
  const node left = nodes[n.left];
  const node right = nodes[n.right];
  const vec3 midLeft = (left.min + left.max) * vec3(0.5);
  const vec3 midRight = (right.min + right.max) * vec3(0.5);
  const vec3 dl = origin - midLeft;
  const vec3 dr = origin - midRight;
  if (dot(dl, dl) < dot(dr, dr)) {
    leftRight = 0;
    return n.left;
  } else {
    leftRight = 1;
    return n.right;
  }
}

/**
 * Determine the next ancestor of the given node 'n' whose near child was
 * processed and whose far child needs to be processed now.
 *
 * @param[inout] nearFarStack   the stack holding information about whether the
 *                              near or the far child was visited
 * @param[inout] leftRightStack whether the near child was the left or right
 *                              child
 * @param[inout] depth          the current stack depth
 * @param n                     the node whose ancestors to check
 * @param[inout] nextIdx        the node index of the next node to be processed
 * @returns true if there is a next node to process; false otherwise
 */
bool processNextFarChild(inout uint nearFarStack, inout uint leftRightStack, inout uint depth, const in node n, inout uint nextIdx) {
  node parent = nodes[n.parent];
  while ((nearFarStack & 1u) == 1u) {
    nearFarStack >>= 1;
    leftRightStack >>= 1;
    depth--;
    if (depth == 0u)
      return false;
    parent = nodes[parent.parent];
  }
  nextIdx = (leftRightStack & 1u) == 0u ? parent.right : parent.left;
  nearFarStack |= 1;
  return true;
}

/**
 * This is our custom "bitstack" BVH traversal traversing the BVH tree in a
 * nearest to furthest order.
 *
 * @param origin the ray's origin
 * @param dir the ray's direction
 * @param invdir 1.0/dir
 * @returns the normal of the closest triangle hit by the ray (if any)
 */
vec3 trace(vec3 origin, vec3 dir, vec3 invdir) {
  uint nextIdx = 0u;
  float nearestTri = 1.0/0.0;
  vec3 normal = vec3(0.0);
  uint iterations = 0u;
  uint leftRightStack = 0u, nearFarStack = 0u, depth = 0u, leftRight = 0u;
  while (nextIdx != NO_NODE) {
    iterations++;
    if (iterations > MAX_FOLLOWS)
      return ERROR_COLOR;
    const node next = nodes[nextIdx];
    float t;
    if (!intersectBox(origin, invdir, next.min, next.max, t) || t > nearestTri) {
      if (depth == 0u)
        break;
      /*
       * The following cases are possible:
       * 1. we tried the near child of a parent and next we must try its far child
       * 2. we tried the far child of a parent and next we must move up to the
       *    nearest ancestor for which the far child needs to be processed next
       *
       * To handle this, we use two bitstacks. One to keep track of whether we are
       * currently visiting the near or the far child of a node and one to keep
       * track of whether the left or the right node was the near child of a node.
       *
       * When we are in case 1 we simply set the bit in the first bitstack to 1
       * to indicate that we are going to visit the far child of the current
       * node's parent. Then we use the second bitstack to see which one is the
       * far child of the current node's parent and set nextIdx to its index.
       * The next outer loop iteration will then test that far child.
       * 
       * When we are in case 2 we pop one node off the stack and see whether 
       * the next top bit in the first bitstack is 0. This will indicate that
       * we were visiting that parent's near child and can now proceed with the
       * far child. This essentially brings us to case 1 above, just that now
       * we are one stack item above. So, this process can be implemented in a
       * loop which pops off nodes from the stack until one node is marked
       * as being the near child of its parent, in which case we will visit 
       * the far child next.
       *
       * When we reached the top of the stack, meaning that we reached the root
       * node and we already visited its far child, then we are finished
       * traversing the tree.
       */
       /*
        * Determine next far child to process by moving up the stack and
        * finding the first ancestor node whose near child was processed last.
        */
       if (!processNextFarChild(nearFarStack, leftRightStack, depth, next, nextIdx))
         break;
    } else {
      /*
       * The following cases are possible:
       * 1. This is a leaf node. In this case we test all triangles in that node.
       *    If we find an intersection, we set the current intersected triangle
       *    and its distance. If we do not find any triangle intersections, we 
       *    simply do not update the current intersected triangle and its distance.
       *    Next, we basically have the same cases as the two cases above.
       *    We check if this node was its parent's near child and if so, we
       *    prepare to visit the far child next. If this node was its parent's
       *    far child, we pop off one node from the stack as long as we reach
       *    a processed node which was its parent's near child, in which case we
       *    prepare to visit the far child.
       * 2. This is a non-leaf node. In this case, we need to figure out the 
       *    near child of the current node. This is the child with the centroid 
       *    position nearest to the ray's origin. We push one node onto the stack
       *    to remember that we are now visiting a node's near child.
       */
      if (next.numTris > 0) {
        /* We have triangles we need to check. */
        trianglehitinfo thinfo;
        int tridx = intersectTriangles(origin, dir, next, nearestTri, thinfo);
        if (tridx != NO_INTERSECTION) {
          /*
           * We found a triangle which is nearer than our current
           * nearest hit (if any). So, remember it.
           */
          normal = normalForTriangle(thinfo, tridx);
          nearestTri = thinfo.t;
        }
        /*
         * Determine next far child to process by moving up the stack and
         * finding the first ancestor node whose near child was processed last.
         */
        if (!processNextFarChild(nearFarStack, leftRightStack, depth, next, nextIdx))
          break;
      } else {
        /* Determine nearest child node */
        nextIdx = closestChild(origin, next, leftRight);
        /* Push node on stack */
        nearFarStack <<= 1;
        leftRightStack = leftRightStack << 1 | leftRight;
        depth++;
      }
    }
  }
  /* We are done traversing the BVH tree. */
  return normal;
}

layout (local_size_x = 8, local_size_y = 4) in;

/**
 * Entry point of this GLSL compute shader.
 */
void main(void) {
  ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (any(greaterThanEqual(pix, size)))
    return;
  vec2 p = (vec2(pix) + vec2(0.5)) / vec2(size);
  vec3 dir = normalize(mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x));
  vec3 color = trace(eye, dir, 1.0 / dir);
  imageStore(framebufferImage, pix, vec4(color, 1.0));
}
