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

/**
 * The current 'time'. We need all input we can get to generate
 * high-quality spatially and temporally varying pseudo-random numbers.
 */
uniform float time;

/**
 * The factor to mix between the previous framebuffer image and the 
 * image generated during this compute shader invocation.
 *
 * The formula for that is:
 *   finalColor = blendFactor * oldColor + (1 - blendFactor) * newColor
 *
 * See the Java source code for more explanation.
 */
uniform float blendFactor;
/**
 * Whether we want to importance sample the BRDF instead of
 * using uniform hemisphere samples.
 */
uniform bool importanceSampled;

uniform float phongExponent;

uniform float specularFactor;

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)
#define LARGE_FLOAT 1E+10
#define NUM_BOXES 11
#define EPSILON 0.0001
#define LIGHT_INTENSITY 4.0
#define SKY_COLOR vec3(0.89, 0.96, 1.00)

/*
 * Forward-declare external functions from random.glsl.
 * 
 * See random.glsl for more explanation of these functions.
 */
vec3 random3(vec3 f);
vec4 randomHemispherePoint(vec3 n, vec2 rand);
vec4 randomCosineWeightedHemispherePoint(vec3 n, vec2 rand);
vec4 randomPhongWeightedHemispherePoint(vec3 r, float a, vec2 rand);

/**
 * Describes an axis-aligned box by its minimum and maximum corner 
 * oordinates.
 */
struct box {
  vec3 min, max, col;
};

/**
 * Our scene description is very simple. We just use a static array
 * of boxes, each defined by its minimum and maximum corner coordinates.
 */
const box boxes[NUM_BOXES] = {
  {vec3(-5.0, -0.1, -5.0), vec3( 5.0, 0.0,  5.0), vec3(0.50, 0.45, 0.33)},// <- floor
  {vec3(-5.1,  0.0, -5.0), vec3(-5.0, 5.0,  5.0), vec3(0.4, 0.4, 0.4)},   // <- left wall
  {vec3( 5.0,  0.0, -5.0), vec3( 5.1, 5.0,  5.0), vec3(0.4, 0.4, 0.4)},   // <- right wall
  {vec3(-5.0,  0.0, -5.1), vec3( 5.0, 5.0, -5.0), vec3(0.43, 0.52, 0.27)},// <- back wall
  {vec3(-5.0,  0.0,  5.0), vec3( 5.0, 5.0,  5.1), vec3(0.5, 0.2, 0.09)},  // <- front wall
  {vec3(-1.0,  1.0, -1.0), vec3( 1.0, 1.1,  1.0), vec3(0.3, 0.23, 0.15)}, // <- table top
  {vec3(-1.0,  0.0, -1.0), vec3(-0.8, 1.0, -0.8), vec3(0.4, 0.3, 0.15)},  // <- table foot
  {vec3(-1.0,  0.0,  0.8), vec3(-0.8, 1.0,  1.0), vec3(0.4, 0.3, 0.15)},  // <- table foot
  {vec3( 0.8,  0.0, -1.0), vec3( 1.0, 1.0, -0.8), vec3(0.4, 0.3, 0.15)},  // <- table foot
  {vec3( 0.8,  0.0,  0.8), vec3( 1.0, 1.0,  1.0), vec3(0.4, 0.3, 0.15)},  // <- table foot
  {vec3( 3.0,  0.0, -4.9), vec3( 3.3, 2.0, -4.6), vec3(0.6, 0.6, 0.6)}    // <- some "pillar"
};

/**
 * Describes the first intersection of a ray with a box.
 */
struct hitinfo {
  /**
   * The normal at the point of intersection.
   */
  vec3 normal;
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
 * We hold the framebuffer pixel coordinate of the current work item to
 * not drag it as parameter all over the place. In addition to "time"
 * this parameter provides spatial variance for generated pseudo-random
 * numbers.
 */
ivec2 px;

/**
 * Compute whether the given ray `origin + t * dir` intersects the given
 * box 'b' and return the values of the parameter 't' at which the ray
 * enters and exists the box, called (tNear, tFar). If there is no
 * intersection then tNear > tFar or tFar < 0.
 *
 * @param origin the ray's origin position
 * @param dir the ray's direction vector
 * @param b the box to test
 * @param normal will hold the normal at the intersection point
 * @returns (tNear, tFar)
 */
vec2 intersectBox(vec3 origin, vec3 dir, const box b, out vec3 normal) {
  vec3 tMin = (b.min - origin) / dir;
  vec3 tMax = (b.max - origin) / dir;
  vec3 t1 = min(tMin, tMax);
  vec3 t2 = max(tMin, tMax);
  float tNear = max(max(t1.x, t1.y), t1.z);
  float tFar = min(min(t2.x, t2.y), t2.z);
  normal = vec3(equal(t1, vec3(tNear))) * sign(-dir);
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
  vec3 normal;
  for (int i = 0; i < NUM_BOXES; i++) {
    vec2 lambda = intersectBox(origin, dir, boxes[i], normal);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.near = lambda.x;
      info.i = i;
      info.normal = normal;
      smallest = lambda.x;
      found = true;
    }
  }
  return found;
}

/**
 * This is one of the key components of our Monte Carlo integration.
 * A way of obtaining spatially and temporally varying pseudo-random
 * numbers.
 *
 * Note that the first two components of the returned vector are usually
 * used to derive spatial values such as the two spherical angles when
 * generating a sample direction vector. The last component is used when
 * needing a random value that does not correlate with generated angles/
 * directions derived from the first two components. An example of this
 * is seen when selecting which of the two importance sampling
 * strategies to be used in the trace() function below for sampling the
 * BRDF components.
 * 
 * @param s this is the "bounce index" to get different pseudo-random
 *          numbers each bounce
 * @returns a vector containing three floating-point pseudo-random
 *          numbers, each in the range [0, 1)
 */
vec3 randvec3(int s) {
  return random3(vec3(px + ivec2(s), time));
}

/**
 * Evaluate the specular part of the BRDF.
 *
 * @param b the box to evaluate (used to get its diffuse color)
 * @param i the incoming light direction
 *          (by convention this points away from the surface)
 * @param o the outgoing light direction
 * @param n the surface normal
 * @returns the attenuation factor
 */
vec3 brdfSpecular(box b, vec3 i, vec3 o, vec3 n) {
  float a = phongExponent;
  vec3 r = reflect(-i, n);
  return vec3(pow(max(0.0, dot(r, o)), a) * (a + 2.0) * ONE_OVER_2PI);
}
/**
 * Evaluate the diffuse part of the BRDF.
 *
 * @param albedo the diffuse color
 * @param i the incoming light direction
 *          (by convention this points away from the surface)
 * @param o the outgoing light direction
 * @param n the surface normal
 * @returns the attenuation factor
 */
vec3 brdfDiffuse(box b, vec3 i, vec3 o, vec3 n) {
  return b.col * ONE_OVER_PI;
}
/**
 * Compute the BRDF of the box's surface given the incoming and outgoing
 * light directions as well as the surface normal.
 *
 * @param b the box to evaluate (used to get its diffuse color)
 * @param i the incoming light direction
 *          (by convention this points away from the surface)
 * @param o the outgoing light direction
 * @param n the surface normal
 * @returns the attenuation factor
 */
vec3 brdf(box b, vec3 i, vec3 o, vec3 n) {
  return brdfSpecular(b, i, o, n) * specularFactor
         +
         brdfDiffuse(b, i, o, n) * (1.0 - specularFactor);
}

/**
 * The general algorithm of this function did not change a lot compared
 * to the last tutorial part. The only thing that changed is how we
 * generate new incoming light direction vectors. We use importance
 * sampling here, which favors direction vectors for which the BRDF of
 * the sampled surface has a large value. This will be used to
 * significantly increase the rate of convergence of the image and thus
 * reduce variance.
 *
 * @param origin the initial origin of the ray
 * @param dir the initial direction vector of the ray
 * @returns the computed color
 */
vec3 trace(vec3 origin, vec3 dir) {
  /*
   * Since we are tracing a light ray "back" from the eye/camera into
   * the scene, everytime we hit a box surface, we need to remember the
   * attenuation the light would take because of the albedo (fraction of
   * light not absorbed) of the surface it reflected off of. At the
   * beginning, this value will be 1.0 since when we start, the ray has
   * not yet touched any surface.
   */
  vec3 att = vec3(1.0);
  /*
   * We are following a ray a fixed number of bounces through the scene.
   * Since every time the ray intersects a box, the steps that need to
   * be done at every such intersection are the same, we use a simple
   * for loop.
   * The first iteration where `bounce == 0` will process our primary
   * ray starting directly from the eye/camera through the framebuffer
   * pixel into the scene. Let's go...
   */
  for (int bounce = 0; bounce < 3; bounce++) {
    /*
     * The ray now travels through the scene of boxes and eventually
     * either hits a box or escapes through the open ceiling. So, test
     * which case it is going to be.
     */
    hitinfo hinfo;
    if (!intersectBoxes(origin, dir, hinfo)) {
      /*
       * The ray did not hit any box in the scene, so it escaped through
       * the open ceiling to the outside world, which is assumed to
       * consist of nothing but light coming in from everywhere.
       * Light source does not scatter light equally in all directions
       * but its radiance is attenuated by the normal at which the ray
       * hits the light source (which shall be a simple plane in our
       * case).
       */
      float d = dir.y; // == dot(dir, vec3(0.0, 1.0, 0.0))
      return LIGHT_INTENSITY * SKY_COLOR * att * d;
    }
    /*
     * When we are here, the ray we are currently processing hit a box.
     * Obtain the box from the index in the hitinfo.
     */
    box b = boxes[hinfo.i];
    /*
     * Next, we need the actual point of intersection. So evaluate the
     * ray equation `point = origin + t * dir` with 't' being the value
     * returned by intersectBoxes() in the hitinfo.
     */
    vec3 point = origin + hinfo.near * dir;
    /*
     * Now, we want to be able to generate a new ray which starts from
     * the point of intersection and travels away from the surface into
     * a random direction.
     * For that, we first need the surface's normal at the point of
     * intersection.
     */
    vec3 normal = hinfo.normal;
    /*
     * Next, we reset the ray's origin to the point of intersection
     * offset by a small epsilon along the surface normal to avoid
     * intersecting that box again because of precision issues with
     * floating-point arithmetic.
     * Notice that we just overwrite the input parameter's value since
     * we do not need the original value anymore, which for the first
     * bounce was our eye/camera location. 
     */
    origin = point + normal * EPSILON;
    /*
     * We have the new origin of the ray and now we just need its new
     * direction. This time we will have two possible ways of generating
     * a direction vector. One is via a uniformly distributed hemisphere
     * direction, like in the last tutorial. Another way is by making
     * use of our knowledge of the BRDF we are using here, and
     * generating vectors predominantly in those directions which our
     * BRDF would return the highest value (i.e. the lowest attenuation)
     * of the light.
     * Since we know that we are dealing with a diffuse and specular
     * reflection component, we know that the BRDF will both have a
     * strong "lobe" around the direction of perfect reflection and a
     * broader lobe around the normal. To account for both possible
     * components, we sample both individually with a probability equal
     * to their respective factor in the BRDF. First, declare our vector
     * holding the generated sample direction and its pdf(x) value.
     */
    vec4 s;
    /*
     * Generate some random values we use later to generate the random
     * vector.
     */
    vec3 rand = randvec3(bounce);
    /*
     * Check whether we want to use importance sampling.
     */
    if (importanceSampled) {
      /*
       * We will use importance sampling for the two parts, diffuse and
       * specular, of the BRDF separately. We do this via randomly
       * choosing which component of the BRDF to sample based on the
       * factor of this component in the whole BRDF.
       * To decide which part of the BRDF to evaluate, we divide a
       * random variable [0, 1) into the interval [0, specularFactor)
       * and [specularFactor, 1), where specularFactor is the amount of 
       * specularity our surface material has. It is important that this
       * factor is both used to weigh the BRDF components as well as to 
       * decide which of the BRDF components to sample and evaluate
       * separately when using importance sampling.
       *
       * Note that we are using the last of the three components in our
       * random vector for this in order to avoid any patterns due to
       * correlation between the sampling decision and the generated
       * sample vectors, which always use the first two components in
       * the random vector.
       *
       * When we might come to a point that we need even more
       * uncorrelated randomness we can just generate a new random
       * vector by calling randvec3() with a different input.
       */
      if (rand.z < specularFactor) {
        /*
         * We want to sample and evaluate the specular part of the BRDF.
         * So, generate a Phong-weighted vector based on the direction
         * of perfect reflection. Because Phong has a strong lobe around
         * that reflection vector we will generate most sample vectors
         * around that reflection vector.
         */
        vec3 r = reflect(dir, normal);
        s = randomPhongWeightedHemispherePoint(r, phongExponent, rand.xy);
        /*
         * Evaluate the specular part of the BRDF.
         */
        att *= brdfSpecular(b, s.xyz, -dir, normal);
      } else {
        /*
         * When we are here, we should evaluate the diffuse part of the
         * BRDF. For that, we will use a cosine-weighted sample
         * distribution. This is because even though a
         * lambertian/diffuse surface reflects light in all directions
         * that light is ultimately attenuated by the cosine fall-off
         * term of the rendering equation. So favoring  the directions
         * for which this cosine term is biggest is the best we can do.
         */
        s = randomCosineWeightedHemispherePoint(normal, rand.xy);
        /*
         * Evaluate the diffuse part of the BRDF.
         */
        att *= brdfDiffuse(b, s.xyz, -dir, normal);
      }
    } else {
      /*
       * No importance sampling should be used. Just use our default
       * uniform hemisphere sampling. This will give a significantly
       * worse convergence rate with much more variance in the
       * estimate/image.
       */
      s = randomHemispherePoint(normal, rand.xy);
      /*
       * Evaluate the whole BRDF (all parts of it).
       */
      att *= brdf(b, s.xyz, -dir, normal);
    }
    /*
     * Use the newly generated direction vector as the next one to
     * follow.
     */
    dir = s.xyz;
    /*
     * Attenuate by the cosine term (explained in Tutorial 2).
     */
    att *= max(0.0, dot(dir, normal));
    /*
     * Attenuate by the sample's probability density value.
     * (also explained in Tutorial 2).
     */
    if (s.w > 0.0)
      att /= s.w;
  }
  /*
   * When having followed a ray for the maximum number of bounces and
   * that ray still did not escape through the ceiling into the light,
   * that ray does therefore not transport any light back to the
   * eye/camera and its contribution to this Monte Carlo iteration will
   * be 0.
   */
  return vec3(0.0);
}

layout (local_size_x = 16, local_size_y = 16) in;

/**
 * Entry point of this GLSL compute shader.
 */
void main(void) {
  /*
   * Obtain the 2D index of the current compute shader work item via the
   * built-in gl_GlobalInvocationID variable and store it in a 'px'
   * variable because we need it later.
   */
  px = ivec2(gl_GlobalInvocationID.xy);
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
   * Trace the list of boxes with the initial ray `eye + t * dir` and
   * follow it along in the scene. The result is a computed color which
   * we will combine with the current/previous framebuffer content
   * below.
   */
  vec3 color = trace(eye, normalize(dir));
  vec3 oldColor = vec3(0.0);
  /*
   * For any iteration but the very first, we load the current value of
   * the pixel from the framebuffer.
   */
  if (blendFactor > 0.0)
    oldColor = imageLoad(framebufferImage, px).rgb;
  /*
   * The final/new color to write to the framebuffer pixel is a linear
   * interpolation of the newly computed value in trace() and the
   * old/current value based on the blendFactor.
   */
  vec3 finalColor = mix(color, oldColor, blendFactor);
  /*
   * Store the final color in the framebuffer's pixel of the current
   * work item.
   */
  imageStore(framebufferImage, px, vec4(finalColor, 1.0));
}
