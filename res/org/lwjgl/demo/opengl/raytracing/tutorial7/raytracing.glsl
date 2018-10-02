/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

/**
 * The color image we will write to.
 */
layout(binding = 0, rgba32f) uniform image2D framebufferImage;
/**
 * This will hold the rasterized world-space normals and view distance.
 * We rasterized the scene before running this compute shader in order
 * to obtain the primary rays quickly. The information in this image now
 * allows us to start shooting secondary rays from the point of
 * intersection of the primary rays with scene geometry.
 */
layout(binding = 1, rgba32f) readonly uniform image2D normalAndViewDistImage;

uniform float time, blendFactor;
uniform vec3 eye, ray00, ray01, ray10, ray11;

/**
 * Describes the node of a kd-tree. This will help us traverse through 
 * the scene without unnecessarily testing ray-triangle intersections.
 */
struct node {
  vec3 min, max;
  int splitAxis;
  float split;
  int ropes[6]; // <- indices/pointer to adjacent nodes
  int left, right; // <- indices/pointers to the left/right child
  int firstTri, numTris;
};
/**
 * Declaration of the SSBO. The name "Nodes" is the interface name used
 * by the host/Java program to associate an Object buffer object with
 * this.
 */
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
 * We hold the framebuffer pixel coordinate of the current work item to
 * not drag it as parameter all over the place. In addition to "time"
 * this parameter provides spatial variance for generated pseudo-random
 * numbers.
 */
ivec2 px;

/**
 * Forward-declaration of the same structure in geometry.glsl. 
 */
struct trianglehitinfo {
  float t, u, v; // <- u, v = barycentric coordinates
};
bool intersectTriangle(vec3 origin, vec3 dir, vec3 v0, vec3 v1, vec3 v2, out trianglehitinfo thinfo);
float exitSide(vec3 origin, vec3 invdir, vec3 boxMin, vec3 boxMax, out int exitSide);

/* Forward-declarations of functions in random.glsl */
float random(vec3 f);
vec4 randomCosineWeightedHemispherePoint(vec3 n, vec2 rand);

/**
 * Just like in the previous tutorial parts, we need a way to obtain
 * random vectors. In this part, we only need this for generating
 * spatial direction vectors depending on two variables.
 * 
 * @param s this is the "bounce index" to get different pseudo-random
 *          numbers each bounce
 * @returns a vector containing two floating-point pseudo-random
 *          numbers, each in the range [0, 1)
 */
vec2 randvec2(int s) {
  return vec2(
    random(vec3(px + ivec2(s), time)),
    random(vec3(px + ivec2(s), time + 1.1)));
}

#define NO_INTERSECTION -1

/**
 * Intersect all triangles in the given node 'n' and return the
 * information about the closest triangle into 'thinfo'.
 *
 * @param origin the ray's origin
 * @param dir the ray's direction
 * @param n the node whose triangles to check
 * @param[out] thinfo will store the information of the closest triangle
 * @returns the index of the closest triangle into the 'Triangles' SSBO
 *          or NO_INTERSECTION if there was no intersection
 */
int intersectTriangles(vec3 origin, vec3 dir, const node n, out trianglehitinfo thinfo) {
  int idx = NO_INTERSECTION;
  float t = 1.0/0.0;
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
 * Output structure for intersectScene() (see below).
 * It describes the triangle which intersected with the ray by its
 * barycentric coordinates `u` and `v`, as well as the distance `t` in
 * the ray equation `p = origin + t * dir` where `p` is the point of
 * intersection.
 * In addition, the index of the triangle `tridx` as well as the kd-tree
 * node index `nodeIdx` the triangle resides in is stored.
 */
struct scenehitinfo {
  float t, u, v;
  int tridx;
  int nodeIdx;
};

#define MAX_DESCEND 200u
#define MAX_ROPES 100u
#define NO_NEIGHBOR -1
#define NO_CHILD -1
#define ERR_TOO_MANY_ROPES 1
#define ERR_TOO_MANY_DESCENDS 2
#define ERR_NOT_EXITED_BOX 3
#define ERR_NO_NEIGHBOR 4
#define EPSILON 1E-6

/**
 * Intersect the given ray `origin + t * dir` with the scene geometry
 * starting at the kd-tree node with the given index `nodeIdx` and
 * return the information about a potential intersection into the given
 * `shinfo` output parameter.
 *
 * For the very first intersection, `nodeIdx` must be 0 to obtain the 
 * scene's root kd-tree node. For further intersection tests the node
 * index returned by the last intersection test will be used to
 * accelerate ray traversal through the scene by not having to descend
 * into a leaf node anymore.
 *
 * @param nodeIdx the index of the kd-tree node to start with the
 *                tree traversal
 * @param origin the ray's origin
 * @param dir the ray's direction
 * @param invdir 1.0/dir
 * @param[out] will hold information about the nearest intersection
 * @returns true if there was any intersection; false otherwise
 */
bool intersectScene(int nodeIdx, vec3 origin, vec3 dir, vec3 invdir, out scenehitinfo shinfo) {
  float t = 0.0;
  int descends = 0, ropes = 0, exit, tridx;
  trianglehitinfo thinfo;
  /*
   * Obtain the kd-tree node to start with the traversal.
   */
  node n = nodes[nodeIdx];
  while (true) {
    /*
     * Determine the current position on the ray.
     */
    vec3 pEntry = origin + dir * t;
    /*
     * And descend into the kd-tree to a leaf node.
     */
    while (n.left != NO_CHILD) {
      /*
       * We use the split factor and the axis to determine on which side
       * of the current kd-tree split our point is and therefore into 
       * which child we have to descend.
       */
      nodeIdx = int(mix(n.right, n.left, n.split >= pEntry[n.splitAxis]));
      /*
       * We have the index, now dereference the actual node.
       */
      n = nodes[nodeIdx];
      /*
       * Safety net: Do not descend too far.
       */
      if (descends++ > MAX_DESCEND)
        return false;
    }
    /*
     * We know that we are in a leaf node. Now check for triangle
     * intersections.
     */
    tridx = intersectTriangles(origin, dir, n, thinfo);
    /*
     * And obtain the side and the distance at which the ray exits this
     * leaf node. This is an essential part of the "stacklessness" of
     * the tree traversal algorithm.
     */
    t = exitSide(origin, invdir, n.min, n.max, exit);
    /*
     * Write the intersection information to the out-parameter.
     */
    shinfo.t = thinfo.t;
    shinfo.u = thinfo.u;
    shinfo.v = thinfo.v;
    shinfo.nodeIdx = nodeIdx;
    shinfo.tridx = tridx;
    /*
     * And check whether there cannot be any closer intersections, in
     * which case we are done and can return.
     * There cannot be any closer intersections if the ray exit distance
     * is at least as great as the triange intersection distance.
     */
    if (tridx != NO_INTERSECTION && t > thinfo.t)
      return true;
    /*
     * Follow the rope at the exit side to get the index of the adjacent
     * neighbor node.
     */
    nodeIdx = n.ropes[exit];
    /*
     * If there was no such neighbor, it means we left the kd-tree and
     * there is no intersection.
     */
    if (nodeIdx == NO_NEIGHBOR)
      return false;
    else
      n = nodes[nodeIdx];
    /*
     * Safety net: Do not follow too many ropes.
     */
    if (ropes++ > MAX_ROPES)
      return false;
  }
  /*
   * Should not reach here, actually.
   */
  return false;
}

/**
 * Compute the interpolated normal of the given triangle.
 *
 * @param u the barycentric `u` coordinate
 * @param v the barycentric `v` coordinate
 * @param tridx the index of the triangle into the 'Triangles' SSBO
 * @returns the interpolated normal
 */
vec3 normalForTriangle(float u, float v, int tridx) {
  const triangle tri = triangles[tridx];
  float w = 1.0 - u - v;
  return vec3(tri.n0 * w + tri.n1 * u + tri.n2 * v);
}

#define LIGHT_INTENSITY 10.0
#define SKY_COLOR vec3(0.7, 0.8, 0.9)
#define PI 3.14159265359
#define ONE_OVER_PI (1.0 / PI)

/**
 * Evaluate a Lambertian/diffuse BRDF.
 *
 * @param i the incoming light direction
 *          (by convention this points away from the surface)
 * @param o the outgoing light direction
 * @param n the surface normal
 * @returns the attenuation factor
 */
vec3 brdfDiffuse(vec3 i, vec3 o, vec3 n) {
  return vec3(ONE_OVER_PI);
}

/**
 * Our simple Monte Carlo estimator. This time it looks a bit different
 * because the primary ray has already been shot into the scene and 
 * intersected with scene geometry via rasterization. So, `origin` and
 * `normal` are already the position and normal at the point of the
 * primary ray intersection with the scene, and `dir` is the primary ray
 * direction.
 *
 * We can now immediately generate a new random direction vector in the
 * hemisphere around the `normal`, and proceed normally like in the 
 * previous tutorial parts.
 *
 * @param origin the point of intersection with the rasterized primary
 *               ray and the scene
 * @param dir the direction of the primary ray
 * @param normal the normal of the scene geometry at the point of
 *               intersection with the rasterized primary ray
 * @returns the color
 */
vec3 trace(vec3 origin, vec3 dir, vec3 normal) {
  vec3 att = vec3(1.0);
  /*
   * Immediately generate a new random vector in the hemisphere.
   */
  scenehitinfo shinfo;
  vec4 s = randomCosineWeightedHemispherePoint(normal, randvec2(0));
  /*
   * Remember the kd-tree node to start descending from into our scene.
   * The stackless kd-tree traversal algorithm actually has a nice
   * property of not requiring "ray restart". Everytime the traversal
   * detected an intersection with the ray and a nearest triangle, that
   * intersection happened inside of a kd-tree node. And the index of
   * that node can then be used to shoot further rays from the point
   * of intersection. This avoids having to descend into the kd-tree at
   * that point every time!
   */
  int nodeIdx = 0;
  /*
   * Perform two additional bounces.
   */
  for (int bounce = 1; bounce < 3; bounce++) {
    /*
     * Attenuate by the probability density value of the chosen sample
     * direction.
     */
    att /= s.w;
    /*
     * Evaluate a simple Lambertian BRDF with the given incoming light
     * direction in `s.xyz` and the given outgoing light direction (i.e.
     * our current `dir`, but negated because by convention the vectors
     * expected by the BRDF function all point away from the point to
     * evaluate.
     */
    att *= brdfDiffuse(s.xyz, -dir, normal);
    /*
     * Set `dir` to be our new sample direction now. 
     */
    dir = s.xyz;
    /*
     * Attenuate by the cosine fall-off term, given the new incoming
     * light direction `dir` and the surface normal.
     */
    att *= dot(dir, normal);
    /*
     * Check intersection with the scene using our newly computed sample
     * direction vector.
     */
    if (!intersectScene(nodeIdx, origin, dir, 1.0/dir, shinfo)) {
      /*
       * We did not hit anything. So, this was the sky. Return the 
       * attenuated color of the "sky light".
       */
      return LIGHT_INTENSITY * SKY_COLOR * att;
    }
    /*
     * Compute the point of intersection.
     */
    vec3 point = origin + shinfo.t * dir;
    /*
     * The kd-tree traversal gave us the index of the kd-tree node the
     * detected intersection happened in. We will start tracing the
     * scene and traversing the kd-tree in this node, by following its
     * ropes/neighbors.
     */
    nodeIdx = shinfo.nodeIdx;
    /*
     * Obtain the interpolated triangle's normal using the barycentric
     * coordinates returned by intersectScene().
     */
    normal = normalForTriangle(shinfo.u, shinfo.v, shinfo.tridx);
    /*
     * The next ray origin will be the point of intersection plus some
     * offset along the normal.
     */
    origin = point + normal * EPSILON;
    /*
     * Generate a new random direction vector to trace next.
     */
    s = randomCosineWeightedHemispherePoint(normal, randvec2(bounce));
  }
  return vec3(0.0);
}

layout (local_size_x = 16, local_size_y = 8) in;

/**
 * Entry point of this GLSL compute shader.
 */
void main(void) {
  /* Known stuff from previous parts. */
  px = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (any(greaterThanEqual(px, size)))
    return;
  vec2 p = (vec2(px) + vec2(0.5)) / vec2(size);
  vec3 dir = normalize(mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x));
  /*
   * Now this is new. We now sample the texture we previously rasterized
   * the world-space normal and the view distance into. This allows us
   * to start shooting the secondary rays from a known intersection
   * point of the rasterized primary rays with the scene geometry.
   */
  vec4 normalAndViewDist = imageLoad(normalAndViewDistImage, px);
  /*
   * The view distance is encoded into the `w` component.
   */
  float dist = normalAndViewDist.w;
  if (dist == 0.0) {
    /*
     * We rasterize/glClear to zero to denote no intersection.
     * In this case we can abort and write zero to the path tracer
     * framebuffer.
     */
    imageStore(framebufferImage, px, vec4(0.0));
    return;
  }
  /*
   * Get the normal at the point of intersection from the `xyz`
   * components.
   */
  vec3 normal = normalAndViewDist.xyz;
  /*
   * The point of intersection is now just the eye position followed
   * `dist` units along the view `dir`.
   */
  vec3 point = eye + (dist * 0.99) * dir;
  /*
   * Now, compute the color using the path tracer algorithm.
   */
  vec3 color = trace(point, dir, normal);
  /*
   * and blend the new color with previous accumulated results.
   */
  vec3 oldColor = vec3(0.0);
  if (blendFactor > 0.0)
    oldColor = imageLoad(framebufferImage, px).rgb;
  vec3 finalColor = mix(color, oldColor, blendFactor);
  imageStore(framebufferImage, px, vec4(finalColor, 1.0));
}
