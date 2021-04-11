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
vec3 randomHemispherePoint(vec3 n, vec2 rand);

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
  {vec3(-5.0, -0.1, -5.0), vec3( 5.0, 0.0,  5.0), vec3(0.50, 0.45, 0.33)}, // <- floor
  {vec3(-5.1,  0.0, -5.0), vec3(-5.0, 5.0,  5.0), vec3(0.4, 0.4, 0.4)}, // <- left wall
  {vec3( 5.0,  0.0, -5.0), vec3( 5.1, 5.0,  5.0), vec3(0.4, 0.4, 0.4)}, // <- right wall
  {vec3(-5.0,  0.0, -5.1), vec3( 5.0, 5.0, -5.0), vec3(0.43, 0.52, 0.27)}, // <- back wall
  {vec3(-5.0,  0.0,  5.0), vec3( 5.0, 5.0,  5.1), vec3(0.5, 0.2, 0.09)}, // <- front wall
  {vec3(-1.0,  1.0, -1.0), vec3( 1.0, 1.1,  1.0), vec3(0.3, 0.23, 0.15)}, // <- table top
  {vec3(-1.0,  0.0, -1.0), vec3(-0.8, 1.0, -0.8), vec3(0.4, 0.3, 0.15)}, // <- table foot
  {vec3(-1.0,  0.0,  0.8), vec3(-0.8, 1.0,  1.0), vec3(0.4, 0.3, 0.15)}, // <- table foot
  {vec3( 0.8,  0.0, -1.0), vec3( 1.0, 1.0, -0.8), vec3(0.4, 0.3, 0.15)}, // <- table foot
  {vec3( 0.8,  0.0,  0.8), vec3( 1.0, 1.0,  1.0), vec3(0.4, 0.3, 0.15)}, // <- table foot
  {vec3( 3.0,  0.0, -4.9), vec3( 3.3, 2.0, -4.6), vec3(0.6, 0.6, 0.6)}  // <- some "pillar"
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
 * @param s this is the "bounce index" to get different pseudo-random
 *          numbers for each bounce
 * @returns a vector containing two floating-point pseudo-random
 *          numbers, each in the range [0, 1)
 */
vec2 randvec2(int s) {
  return random3(vec3(px + ivec2(s), time)).xy;
}

/**
 * Compared to the previous tutorial, this function changed
 * significantly. Now we do not just shoot the primary ray from the
 * eye/camera into the scene and return whether that ray intersected a
 * box.
 * 
 * This time, when a ray hit a box surface, we evaluate a simple
 * Lambertian BRDF, attenuate any light potentially propagating along
 * this ray path and compute a next ray direction within the hemisphere
 * of the box surface. This ray is then followed again until it hits
 * anything. In the case the ray escapes the scene through the open
 * ceiling/roof it is assumed that it hit a light source.
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
   * for loop. The first iteration where `bounce == 0` will process our
   * primary ray starting directly from the eye/camera through the
   * framebuffer pixel into the scene. Let's go...
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
       * consist of nothing but light coming in from everywhere. Return
       * the attenuated sky color.
       */
      return LIGHT_INTENSITY * SKY_COLOR * att;
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
     * a random direction. For that, we first need the surface's normal
     * at the point of intersection.
     */
    vec3 normal = hinfo.normal;
    /*
     * Next, we reset the ray's origin to the point of intersection
     * offset by a small epsilon along the surface normal to avoid
     * intersecting that box again because of precision issues with
     * floating-point arithmetic. Notice that we just overwrite the
     * input parameter's value since we do not need the original value
     * anymore, which for the first bounce was our eye/camera location.
     */
    origin = point + normal * EPSILON;
    /*
     * We have the new origin of the ray and now we just need its new
     * direction. Since we have the normal of the hemisphere within
     * which we want to generate the new direction, we can call a
     * function which generates such vector based on the normal and some
     * pseudo-random numbers we need to generate as well.
     */
    dir = randomHemispherePoint(normal, randvec2(bounce));
    /*
     * The next thing we have to take care of is evaluating the surface
     * BRDF with the outgoing light ray direction (i.e. our previous
     * 'dir') and the incoming light direction (i.e. our newly computed
     * 'dir'). Since we are dealing with lambertian surfaces which
     * reflect light equally in all directions, this BRDF function is
     * actually a constant equal to c/PI, where 'c' is the
     * albedo/reflectance factor of the surface determining how much of
     * the incoming light is reflected by the surface. We will take care
     * of the 'c' factor further below. Here we just multiply our
     * attenuation factor by 1/PI. In this tutorial the BRDF is a
     * constant, however in later tutorials this will be an actual
     * function of the light direction vectors.
     *
     * The reason for this factor being 1/PI is to have the integrand
     * cos(theta) * 1/PI integrate to 1 over the surface hemisphere.
     * The cos(theta) comes from the rendering equation and is the
     * "cosine fall-off" term (we adjust 'att' with this factor below)
     * to account for the reduced irradiance of a surface when light
     * hits it at shallow angles.
     * That means if we evaluate this integral for a given incident
     * light direction, computing the radiances for all outgoing
     * directions, that integral must not be greater than 1. This is to
     * ensure energy conservation and means that a surface cannot
     * reflect more light than it receives.
     *
     * Just give it a try for yourself and compute the value of the 
     * hemisphere surface integral
     * `{0->2pi}{0->pi/2}(cos(t)/PI*sin(t))dt*dp`
     * e.g. by using
     * http://www.wolframalpha.com/widgets/view.jsp?id=6a68b5e97b4f834f58e777a4dceaed0
     * Make sure to set "max theta" to `pi/2` for integrating over the 
     * hemisphere instead of the whole sphere.
     */
    att *= ONE_OVER_PI;
    /*
     * Now this part is important: Any light that might come along the
     * newly generated ray has to be attenuated based on the angle
     * between the generated direction vector and the surface normal.
     * This is known as the "cosine fall-off" factor and is 100%
     * physically correct and has actually nothing to do with how the
     * surface actually reflects light (diffuse/lambertian, specular,
     * etc.). The cosine term is also one part of the rendering
     * equation.
     * We use the dot product because for any two unit vectors a and b,
     * the cosine of the angle between them is exactly dot(a, b).
     */
    att *= dot(dir, normal);
    /*
     * We also model the color of a box. And the color is nothing but
     * the fraction of incoming light which gets reflected off the
     * surface and hence not absorbed by the surface. This is often
     * called the "albedo" of the surface. To express this in our
     * calculations, we further attenuate by the box's color.
     */
    att *= b.col;
    /*
     * The last factor we need to take care of is an important part of
     * the Monte Carlo integration. What we are doing is sampling the
     * hemisphere of a surface with random vectors and evaluating an
     * integrand (in this case a lambertian BRDF). Since we are
     * integrating over a hemisphere, the actual result of the whole 
     * integral must be <= 2PI, since that is the solid angle of a
     * hemisphere.
     * Recall that the Monte Carlo estimator looks like this:
     *
     *  F[N] = 1/N * Sum{1, N}(f(x) / p(x))
     * 
     * where we have f(x) to be our BRDF, in this case `cos(theta)/PI`.
     * But there is another factor `p(x)` which is the probability
     * density function of our generated sample vectors, giving the 
     * probability that `x` will be generated over any differential
     * solid angle over the hemisphere. So it is a "probability over a
     * differential solid angle of the hemisphere."
     * For uniform sampling this is a constant and equal to `1/(2*PI)`.
     * If you integrate `cos(theta)/PI*2*PI = cos(theta)*2` over the
     * hemisphere surface you'll get `2*PI` as result, which is the 
     * solid angle of a hemisphere (with radius 1) and is exactly what
     * we need our integral, and in turn our Monte Carlo estimator, to
     * evaluate to. 
     *
     * In later tutorial parts when we do not use uniform hemisphere
     * samples anymore the probability density function is not going to
     * be a constant but an actual function depending on various input
     * vectors.
     */
    att /= ONE_OVER_2PI;
  }
  /*
   * When followed a ray for the maximum number of bounces and that ray
   * still did not escape through the ceiling into the light, that ray
   * does therefore not transport any light back to the eye/camera and
   * its contribution to this Monte Carlo iteration will be 0.
   */
  return vec3(0.0);
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
