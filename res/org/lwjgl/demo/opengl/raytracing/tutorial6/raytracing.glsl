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
  vec3 min, max;
  /**
   * Index of the node to visit next in the even of a hit.
   */
  int hitNext;
  /**
   * Index of the node to visit next in the even of a miss.
   */
  int missNext;
  /*
   * When we are a leaf node, this stores the offset and count of the
   * triangles in this node.
   */
  int firstTri, numTris;
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
#define MAX_FOLLOWS 1000

/**
 * This is our custom "stackless" BVH traversal using "hitNext" and
 * "missNext" pointers to traverse the tree in-order.
 *
 * @param origin the ray's origin
 * @param dir the ray's direction
 * @param invdir 1.0/dir
 * @returns the normal of the closest triangle hit by the ray (if any)
 */
vec3 trace(vec3 origin, vec3 dir, vec3 invdir) {
  int nextIdx = 0; // <- start with the root node
  float nearestTri = 1.0/0.0; // <- +inf
  vec3 normal = vec3(0.0);
  int iterations = 0;
  while (nextIdx != NO_NODE) {
    /*
     * Safety net: Check for possible endless loop.
     */
    iterations++;
    if (iterations > MAX_FOLLOWS)
      return ERROR_COLOR;
    /*
     * Dereference the next node to visit.
     */
    const node next = nodes[nextIdx];
    /*
     * And check for ray-box intersection with it.
     */
    float t;
    if (!intersectBox(origin, invdir, next.min, next.max, t)) {
      /*
       * The ray does not intersect this box, so follow the node's
       * 'miss' pointer, which will not bring us further down into
       * any possible children but continue either with its right
       * sibling (if it was a left child node itself) or go further up
       * the tree for next in-order traversal.
       */
      nextIdx = next.missNext;
    } else {
      /*
       * The ray did intersect the box, so first check if the node or
       * any of its descendants can actually contain triangles that are
       * nearer than our current nearest triangle (if any).
       * This can only happen if the distance of the ray-box
       * intersection is not greater than the current triangle's 
       * intersection distance (if any).
       */
      if (t > nearestTri) {
        /*
         * This node is farther away than the currently nearest triangle
         * intersection. We can skip visiting its potential children 
         * and instead following its skip pointer.
         */
        nextIdx = next.missNext;
        continue;
      }
      if (next.numTris > 0) {
        /*
         * We have triangles we need to check.
         */
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
      }
      /*
       * Follow the 'hit' pointer, which might bring us to a child
       * or to the next node to be visibled in-order.
       */
      nextIdx = next.hitNext;
    }
  }
  /*
   * We are done traversing the BVH tree.
   */
  return normal;
}

layout (local_size_x = 16, local_size_y = 8) in;

/**
 * Entry point of this GLSL compute shader.
 */
void main(void) {
  ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (any(greaterThanEqual(pix, size))) {
    return;
  }
  vec2 p = (vec2(pix) + vec2(0.5)) / vec2(size);
  vec3 dir = normalize(mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x));
  vec3 color = trace(eye, dir, 1.0 / dir);
  imageStore(framebufferImage, pix, vec4(color, 1.0));
}
