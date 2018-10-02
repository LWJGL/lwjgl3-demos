/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

/**
 * The framebuffer image we write to.
 */
layout(binding = 0, rgba32f) uniform image2D framebufferImage;

/**
 * Describes the view frustum of the camera via its world-space corner
 * edge vectors which we perform bilinear interpolation on to get the
 * world-space direction vector of a work item's framebuffer pixel.
 * See function main().
 */
uniform vec3 eye, ray00, ray01, ray10, ray11;

#define LARGE_FLOAT 1E+10
#define NUM_BOXES 10
#define EPSILON 0.0001

/**
 * Describes an axis-aligned box by its minimum and maximum corner 
 * oordinates.
 */
struct box {
  vec3 min, max;
};

/**
 * Our scene description is very simple. We just use a static array
 * of boxes, each defined by its minimum and maximum corner coordinates.
 */
const box boxes[NUM_BOXES] = {
  {vec3(-5.0, -0.1, -5.0), vec3(5.0, 0.0, 5.0)}, // <- bottom
  {vec3(-5.1, 0.0, -5.0), vec3(-5.0, 5.0, 5.0)}, // <- left
  {vec3(5.0, 0.0, -5.0), vec3(5.1, 5.0, 5.0)},   // <- right
  {vec3(-5.0, 0.0, -5.1), vec3(5.0, 5.0, -5.0)}, // <- back
  {vec3(-5.0, 0.0, 5.0), vec3(5.0, 5.0, 5.1)},   // <- front
  {vec3(-1.0, 1.0, -1.0), vec3(1.0, 1.1, 1.0)},   // <- table top
  {vec3(-1.0, 0.0, -1.0), vec3(-0.8, 1.0, -0.8)},   // <- table foot
  {vec3(-1.0, 0.0,  0.8), vec3(-0.8, 1.0, 1.0)},   // <- table foot
  {vec3(0.8, 0.0, -1.0), vec3(1.0, 1.0, -0.8)},   // <- table foot
  {vec3(0.8, 0.0,  0.8), vec3(1.0, 1.0, 1.0)}   // <- table foot
};

/**
 * Describes the first intersection of a ray with a box.
 */
struct hitinfo {
  /*
   * The value of the parameter 't' in the ray equation
   * `p = origin + dir * t` at which p is a point on one of the boxes
   * intersected by the ray.
   */
  float near;
  /*
   * The index of the box into the 'boxes' array.
   */
  int i;
};

/**
 * Compute whether the given ray `origin + t * dir` intersects the given
 * box 'b' and return the values of the parameter 't' at which the ray
 * enters and exists the box, called (tNear, tFar). If there is no
 * intersection then tNear > tFar or tFar < 0.
 */
vec2 intersectBox(vec3 origin, vec3 dir, const box b) {
  vec3 tMin = (b.min - origin) / dir;
  vec3 tMax = (b.max - origin) / dir;
  vec3 t1 = min(tMin, tMax);
  vec3 t2 = max(tMin, tMax);
  float tNear = max(max(t1.x, t1.y), t1.z);
  float tFar = min(min(t2.x, t2.y), t2.z);
  return vec2(tNear, tFar);
}

/**
 * Compute the closest intersection of the given ray `origin + t * dir`
 * with all boxes and return whether there was any intersection and
 * store the value of 't' at the intersection as well as the index of
 * the intersected box into the out-parameter 'info'.
 */
bool intersectBoxes(vec3 origin, vec3 dir, out hitinfo info) {
  float smallest = LARGE_FLOAT;
  bool found = false;
  for (int i = 0; i < NUM_BOXES; i++) {
    vec2 lambda = intersectBox(origin, dir, boxes[i]);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.near = lambda.x;
      info.i = i;
      smallest = lambda.x;
      found = true;
    }
  }
  return found;
}

/**
 * Given the ray `origin + t * dir` trace it through the scene, 
 * checking for the nearest collision with any of the above defined
 * boxes, and return a computed color.
 *
 * @param origin the origin of the ray
 * @param dir the direction vector of the ray
 * @returns the computed color
 */
vec3 trace(vec3 origin, vec3 dir) {
  hitinfo hinfo;
  /* Intersect the ray with all boxes */
  if (!intersectBoxes(origin, dir, hinfo))
    return vec3(0.0); // <- nothing hit, return black
  /*
   * hitinfo will give use the index of the box.
   * So, get the actual box with that index.
   */
  box b = boxes[hinfo.i];
  /*
   * And compute some gray scale color based on the index to
   * just allow us to visually differentiate the boxes. 
   */
  return vec3(float(hinfo.i+1) / NUM_BOXES);
}

layout (local_size_x = 16, local_size_y = 16) in;

/**
 * Entry point of this GLSL compute shader.
 */
void main(void) {
  /*
   * Obtain the 2D index of the current compute shader work item via
   * the built-in gl_GlobalInvocationID variable and store it in a 'px'
   * variable because we need it later.
   */
  ivec2 px = ivec2(gl_GlobalInvocationID.xy);
  /*
   * Also obtain the size of the framebuffer image. We could have used 
   * a custom uniform for that as well. But GLSL already provides it as
   * a built-in function.
   */
  ivec2 size = imageSize(framebufferImage);
  /*
   * Because we have to execute our compute shader with a global work
   * size that is a power of two, we need to check whether the current
   * work item is still within our actual framebuffer dimension so that
   * we do not accidentally write to or read from unallocated memory
   * later.
   */
  if (any(greaterThanEqual(px, size)))
    return; // <- no work to do, return.
  /*
   * Now we take our rayNN uniforms declared above to determine the
   * world-space direction from the eye position through the current
   * work item's pixel's center in the framebuffer image. We use the
   * 'px' variable, cast it to a floating-point vector, offset it by
   * half a pixel's width (in whole pixel units) and then transform that
   * position relative to our framebuffer size to get values in the
   * interval [(0, 0), (1, 1)] for all work items covering our
   * framebuffer.
   */
  vec2 p = (vec2(px) + vec2(0.5)) / vec2(size);
  /*
   * Use bilinear interpolation based on the X and Y fraction
   * (within 0..1) with our rayNN vectors defining the world-space
   * vectors along the corner edges of the camera's view frustum. The
   * result is the world-space direction of the ray originating from the
   * camera/eye center through the work item's framebuffer pixel center.
   */
  vec3 dir = mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x);
  /*
   * Now, trace the list of boxes with the ray `eye + t * dir`.
   * The result is a computed color which we will write at the work
   * item's framebuffer pixel.
   */
  vec3 color = trace(eye, normalize(dir));
  /*
   * Store the final color in the framebuffer's pixel of the current
   * work item.
   */
  imageStore(framebufferImage, px, vec4(color, 1.0));
}
