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
 * Whether we want to multiple importance sample the light source
 * instead of using only uniform hemisphere samples.
 */
uniform bool multipleImportanceSampled;

uniform float phongExponent;

uniform float specularFactor;

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)
#define LARGE_FLOAT 1E+10
#define NUM_BOXES 11
#define NUM_SPHERES 1
#define EPSILON 0.0001
#define LIGHT_INTENSITY 2000.0 // <- because the light source is very small!
#define PROBABILITY_OF_LIGHT_SAMPLE 0.5

/*
 * Forward-declare external functions from random.glsl.
 * 
 * See random.glsl for more explanation of these functions.
 */
vec3 random3(vec3 f);
vec4 randomHemispherePoint(vec3 n, vec2 rand);
float hemisphereProbability(vec3 n, vec3 v);
vec4 randomDiskPoint(vec3 n, float d, float r, vec2 rand);
float diskProbability(vec3 n, float d, float r, vec3 v);

/**
 * Describes an axis-aligned box by its minimum and maximum corner 
 * oordinates.
 */
struct box {
  vec3 min, max, col;
};

/**
 * Describes a sphere with center position and radius.
 */
struct sphere {
  vec3 c;
  float r;
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

const sphere spheres[NUM_SPHERES] = {
  {vec3(0.0, 3.0, 0.0), 0.1}
};

/**
 * Describes the first intersection of a ray with a box or a sphere.
 */
struct hitinfo {
  /**
   * The normal at the point of intersection.
   */
  vec3 normal;
  /*
   * The value of the parameter 't' in the ray equation
   * `p = origin + dir * t` at which p is a point on one of the boxes
   * or spheres intersected by the ray.
   */
  float near;
  /*
   * The index of the box into the 'boxes' array or sphere into the
   * 'spheres' array.
   */
  int i;
  /*
   * We need to distinguish whether the thing we hit is a box or sphere.
   */
  bool isSphere;
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
 * Compute whether the given ray `origin + t * dir` intersects the given
 * sphere 's' and return the values of the parameter 't' at which the
 * ray enters and exists the sphere, called (tNear, tFar). If there is
 * no intersection then tNear == -1 and tFar == -1.
 *
 * @param origin the ray's origin position
 * @param dir the ray's direction vector
 * @param s the sphere to test
 * @returns (tNear, tFar)
 */
vec2 intersectSphere(vec3 origin, vec3 dir, const sphere s) {
  vec3 L = s.c - origin;
  float tca = dot(L, dir);
  float d2 = dot(L, L) - tca * tca;
  if (d2 > s.r * s.r)
    return vec2(-1.0);
  float thc = sqrt(s.r * s.r - d2);
  float t0 = tca - thc;
  float t1 = tca + thc;
  if (t0 < t1 && t1 >= 0.0)
    return vec2(t0, t1);
  return vec2(-1.0);
}

/**
 * Compute the closest intersection of the given ray `origin + t * dir`
 * with all boxes and spheres and return whether there was any
 * intersection and if so, store the value of 't' at the nearest
 * intersection as well as the index of the intersected box or sphere
 * into the out-parameter 'info'.
 */
bool intersectObjects(vec3 origin, vec3 dir, out hitinfo info) {
  float smallest = LARGE_FLOAT;
  bool found = false;
  vec3 normal;
  /* Test the boxes */
  for (int i = 0; i < NUM_BOXES; i++) {
    vec2 lambda = intersectBox(origin, dir, boxes[i], normal);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.near = lambda.x;
      info.i = i;
      info.isSphere = false;
      info.normal = normal;
      smallest = lambda.x;
      found = true;
    }
  }
  /* Also test the sphere(s) */
  for (int i = 0; i < NUM_SPHERES; i++) {
    vec2 lambda = intersectSphere(origin, dir, spheres[i]);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.near = lambda.x;
      info.i = i;
      info.isSphere = true;
      smallest = lambda.x;
      found = true;
    }
  }
  return found;
}

/**
 * Compute the normal of a sphere at the given point.
 *
 * @param hit the point on the sphere
 * @param s the sphere
 * @returns the normal vector
 */
vec3 normalForSphere(vec3 hit, const sphere s) {
  return normalize(hit - s.c);
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
vec3 brdfSpecular(vec3 i, vec3 o, vec3 n) {
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
vec3 brdfDiffuse(vec3 albedo, vec3 i, vec3 o, vec3 n) {
  return albedo * ONE_OVER_PI;
}
/**
 * Compute the BRDF of the box's surface given the incoming and outgoing
 * light directions as well as the surface normal.
 *
 * @param albedo the diffuse color
 * @param i the incoming light direction
 *          (by convention this points away from the surface)
 * @param o the outgoing light direction
 * @param n the surface normal
 * @returns the attenuation factor
 */
vec3 brdf(vec3 albedo, vec3 i, vec3 o, vec3 n) {
  return brdfSpecular(i, o, n) * specularFactor
         +
         brdfDiffuse(albedo, i, o, n) * (1.0 - specularFactor);
}

/**
 * The general algorithm of this function did not change a lot compared
 * to the last tutorial part. The only thing that changed is how we
 * generate new incoming light direction vectors.
 * We use "Multiple Importance" sampling now to stochastically either  
 * sample the light source or use simple uniform hemisphere sampling.
 * This will be used to significantly increase the rate of convergence
 * of the image and thus reduce variance.
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
     * The ray now travels through the scene of boxes and spheres and
     * eventually either hits a box/sphere or escapes through the open
     * ceiling. So, test which case it is going to be.
     */
    hitinfo hinfo;
    if (!intersectObjects(origin, dir, hinfo)) {
      /*
       * The ray did not hit any box or sphere in the scene, so it
       * escaped through the open ceiling into the outside world, which
       * is completely dark now in this tutorial part.
       */
       return vec3(0.0);
    }
    /*
     * When we are here, the ray we are currently processing hit an
     * object (box or sphere).
     * Next, we need the actual point of intersection. So evaluate the
     * ray equation `point = origin + t * dir` with 't' being the value
     * returned by intersectObjects() in the hitinfo.
     */
    vec3 point = origin + hinfo.near * dir;
    vec3 normal, albedo;
    if (hinfo.isSphere) {
      /*
       * We hit a light source! So just return its cosine-weighted
       * attenuated radiance.
       */
      const sphere s = spheres[hinfo.i];
      return att * LIGHT_INTENSITY * dot(normalForSphere(point, s), -dir);
    } else {
      /*
       * We hit a box. Proceed as in the last tutorial part.
       */
      const box b = boxes[hinfo.i];
      normal = hinfo.normal;
      albedo = b.col;
    }
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
     * direction. Another new way is by making use of our knowledge of
     * the light sources in the scene and generate sample directions
     * towards them in the hope that those are not occuded by other
     * scene geometry.
     */
    vec4 s;
    /*
     * Generate some random values we use later to generate the random
     * vector.
     */
    vec3 rand = randvec3(bounce);
    /*
     * Do we want to use MI sampling or just our standard hemisphere
     * sampling?
     */
    if (multipleImportanceSampled) {
      /*
       * Obtain a light source in our scene and compute the direction
       * towards it from our current 'origin'.
       */
      sphere li = spheres[0];
      vec3 d = li.c - origin, n = normalize(d);
      /*
       * We are going to use the "One-sample model" proposed by Veach.
       * This means, we use one of two sampling strategies. Either
       * sampling the light source or sampling the hemisphere.
       * The first one is good when we can expect to hit the light
       * and get fast convergence/low variance quickly. This approach
       * however is not ideal when we do not directly hit the light and
       * should instead sample the BRDF.
       * On the other hand, sampling the BRDF can not be optimal either,
       * for example when having a strong specular lobe which does not
       * point towards any light.
       * That's why we will use randomly either the one or the other 
       * strategy by letting a random variable decide which one it is
       * going to be.
       */
      if (rand.z < PROBABILITY_OF_LIGHT_SAMPLE) {
        /*
         * We want to sample the light source. From our point of view
         * the spherical light source is a projected circle/disk and we
         * need a way to uniformly sample a disk. So call a function
         * which does exactly that.
         */
        s = randomDiskPoint(n, length(d), li.r, rand.xy);
        /*
         * We will be using the "balancec heuristic" weighting strategy
         * of multiple importance sampling, which requires us to
         * evaluate the probability distribution function of the
         * hemisphere sampling function with our disk sample direction.
         */
        float p = hemisphereProbability(normal, s.xyz);
        /*
         * Now we need to compute the balanced weight of both
         * probability density values together with the probability of
         * this sample being chosen.
         */
        s.w = (s.w + p) * PROBABILITY_OF_LIGHT_SAMPLE;
      } else {
        /*
         * We want to sample uniformly over the hemisphere instead.
         */
        s = randomHemispherePoint(normal, rand.xy);
        /*
         * Same as above. Also obtain pdf(x) for the other distribution.
         */
        float p = diskProbability(n, length(d), li.r, s.xyz);
        /*
         * And weight it.
         */
        s.w = (s.w + p) * (1.0 - PROBABILITY_OF_LIGHT_SAMPLE);
      }
    } else {
      /*
       * Not using multiple importance sampling but instead our good old
       * trusty and correct random hemisphere sampling.
       */
      s = randomHemispherePoint(normal, rand.xy);
    }
    /*
     * Now it's the same as in the previous tutorial parts. Evaluate the
     * BRDF and attenuate by it.
     */
    att *= brdf(albedo, s.xyz, -dir, normal);
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
   * that ray still did not hit a light source, that ray does therefore
   * not transport any light back to the eye/camera and its contribution
   * to this Monte Carlo iteration will be 0.
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
   * Trace the list of boxes and spheres with the initial ray
   * `eye + t * dir` and follow it along in the scene. The result is a
   * computed color which we will combine with the current/previous
   * framebuffer content below.
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
