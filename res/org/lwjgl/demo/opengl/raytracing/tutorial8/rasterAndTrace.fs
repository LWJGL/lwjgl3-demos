/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

/**
 * Sampler for a texture holding the previous frame's color.
 */
uniform sampler2D prevColorTex;
/**
 * Sampler for a texture holding the previous frame's view depth.
 */
uniform sampler2D prevViewDepthTex;
/**
 * The current 'time'. We need all input we can get to generate
 * high-quality spatially and temporally varying pseudo-random numbers.
 */
uniform float time;

in vec4 worldPosition;
in vec4 worldNormal;
in vec3 dir;

/* Current frame's clip position. */
in vec4 clipPosition;
/* Previous frame's clip position. */
in vec4 prevClipPosition;
/* Current frame's view position. */
in vec4 viewPosition;
/* Previous frame's view position. */
in vec4 prevViewPosition;

out vec4 color_out;
out float viewDepth_out;
out vec3 normal_out;

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)
#define LARGE_FLOAT 1E+10
#define EPSILON 1E-4
#define LIGHT_INTENSITY 2.0
#define SKY_COLOR vec3(0.92, 0.97, 1.00)
#define PHONG_EXPONENT 8000.0
#define BOX_ATTENUATION 0.5
#define BOUNCES 2
#define MAX_NUM_BOXES 64
#define MAX_SAMPLE_FRAME 20.0

/*
 * Forward-declare external functions from random.glsl.
 * 
 * See random.glsl for more explanation of these functions.
 */
float random(vec3 f);
vec4 randomCosineWeightedHemispherePoint(vec3 n, vec2 rand);
vec4 randomPhongWeightedHemispherePoint(vec3 r, float a, vec2 rand);

/**
 * Describes an axis-aligned box by its minimum and maximum corner coordinates.
 */
struct box {
  vec3 min;
  vec3 max;
};

/** UBO with all boxes of the scene. */
layout (std140) uniform Boxes {
  box[MAX_NUM_BOXES] boxes;
};
/** The number of boxes in the scene. */
uniform int numBoxes;

/** Structure holding all necessary information of a ray-scene intersection. */
struct hitinfo {
  /** The near 't' */
  float near;
  /** The normal at the point of intersection */
  vec3 normal;
  /** The index (into 'boxes') of the box */
  int bi;
};

/**
 * We hold the framebuffer pixel coordinate of the current work item to not drag
 * it as parameter all over the place. In addition to "time" this parameter
 * provides spatial variance for generated pseudo-random numbers.
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
 * Compute whether the given ray `origin + t * dir` intersects any box in the
 * scene and return the value of the parameter 't' at which the ray enters the
 * nearest box as well as return the index of the box.
 *
 * @param origin the ray's origin position
 * @param dir    the ray's direction vector
 * @param info   output parameter receiving information about the nearest
 *               intersection
 * @returns true iff there was an intersection; false otherwise
 */
bool intersectBoxes(vec3 origin, vec3 dir, out hitinfo info) {
  float smallest = LARGE_FLOAT;
  bool found = false;
  vec3 normal;
  for (int i = 0; i < numBoxes; i++) {
    box b = boxes[i];
    vec2 lambda = intersectBox(origin, dir, b, normal);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.near = lambda.x;
      info.bi = i;
      info.normal = normal;
      smallest = lambda.x;
      found = true;
    }
  }
  return found;
}

/**
 * This is one of the key components of our Monte Carlo integration.
 * A way of obtaining spatially and temporally varying pseudo-random numbers.
 *
 * Note that the first two components of the returned vector are usually used to
 * derive spatial values such as the two spherical angles when generating a
 * sample direction vector. The last component is used when needing a random
 * value that does not correlate with generated angles/directions derived from
 * the first two components. An example of this is seen when selecting which of
 * the two BRDF parts to sample in the trace() function below.
 * 
 * @param s the "bounce index" to get different pseudo-random numbers each
 *          bounce
 * @returns a vector containing three floating-point pseudo-random numbers, each
 *          in the range [0, 1)
 */
vec3 randvec3(int s) {
  return vec3(
    random(vec3(px + ivec2(s), time)),
    random(vec3(px + ivec2(s), time * 1.3)),
    random(vec3(px + ivec2(s), time * 0.43)));
}

/**
 * Evaluate the specular part of the BRDF.
 *
 * @param i the incoming light direction
 *          (by convention this points away from the surface)
 * @param o the outgoing light direction
 * @param n the surface normal
 * @returns the attenuation factor
 */
float brdfSpecular(vec3 i, vec3 o, vec3 n) {
  float a = PHONG_EXPONENT;
  vec3 r = reflect(-i, n);
  return pow(max(0.0, dot(r, o)), a) * (a + 2.0) * ONE_OVER_2PI;
}
/**
 * Evaluate the diffuse part of the BRDF.
 *
 * @param i the incoming light direction
 *          (by convention this points away from the surface)
 * @param o the outgoing light direction
 * @param n the surface normal
 * @returns the attenuation factor
 */
float brdfDiffuse(vec3 i, vec3 o, vec3 n) {
  return BOX_ATTENUATION * ONE_OVER_PI;
}

/**
 * Schlick approximation of the Fresnel term.
 *
 * @param h the half-vector between incoming light direction and outgoing light
 *          direction (by convention all of those point away from the surface)
 * @param n the surface normal
 * @returns the Fresnel factor
 */
float fresnel(vec3 h, vec3 n) {
  return pow(1.0 - max(0.0, dot(n, h)), 5.0);
}

/**
 * Trace the scene starting with a ray at the given origin with the given
 * direction. Since the primary ray was computed via rasterization, origin will
 * already be the intersection of the primary ray with the scene and we
 * therefore need the normal at that point, which we also get from
 * rasterization.
 * 
 * @param origin the intersection point of the primary ray and the scene
 * @param dir    the world-space direction of the rasterized primary ray
 * @param normal the world-space normal at the intersection point
 * @return the traced color
 */
vec3 trace(vec3 origin, vec3 dir, vec3 normal) {
  /*
   * Since we are tracing a light ray "back" from the eye/camera into the scene,
   * everytime we hit a box surface, we need to remember the attenuation the
   * light would take because of the albedo (fraction of light not absorbed) of
   * the surface it reflected off of. At the beginning, this value will be 1.0
   * since when we start, the ray has not yet touched any surface (the
   * intersection with the primary ray and the scene will be processed in the
   * loop below).
   */
  float att = 1.0;
  /*
   * We are following a ray a fixed number of bounces through the scene.
   * Because every time the ray intersects a box, the steps that need to be done
   * at every such intersection are the same, we use a simple for-loop.
   * The first iteration where `bounce == 0` will process the first ray starting
   * directly after the primary ray hit a surface.
   */
  for (int bounce = 0; bounce < BOUNCES; bounce++) {
    /*
     * Generate some random values we use later to generate a random vector.
     */
    vec3 rand = randvec3(bounce);
    /*
     * We have the origin of the ray and now we just need its new direction.
     * Based on the BRDF of the surfaces in our scene, we will have two possible
     * ways of generating a direction vector.
     * One is via a cosine-weighted direction for the diffuse part of the BRDF.
     * Another is via a Phong-weighted direction for the specular part of the
     * BRDF.
     * Which term to sample is derived based on the Fresnel factor and the value
     * of a pseudo-random variable. This will give a probability of sampling a
     * part of the BRDF proportional to the Fresnel factor.
     */
    vec4 s;
    if (rand.z < fresnel(-dir, normal)) {
      /*
       * We should sample the specular BRDF term using a Phong-weighted sample
       * distribution. So generate a sample direction together with its
       * probability density (= pdf(s.xyz)).
       */
      s = randomPhongWeightedHemispherePoint(reflect(dir, normal), PHONG_EXPONENT, rand.xy);
      /**
       * Sample the BRDF and multiply the returned attenuation.
       */
      att *= brdfSpecular(s.xyz, -dir, normal);
    } else {
      /*
       * We should sample the diffuse BRDF term using a cosine-weighted sample
       * distribution. So generate a sample direction together with its
       * probability density (= pdf(s.xyz)).
       */
      s = randomCosineWeightedHemispherePoint(normal, rand.xy);
      /**
       * Sample the BRDF and multiply the returned attenuation.
       */
      att *= brdfDiffuse(s.xyz, -dir, normal) * max(0.0, dot(s.xyz, normal));
    }
    /*
     * Set the new ray direction from the result of generating the sample
     * direction we sampled the BRDF with earlier.
     */
    dir = s.xyz;
    /*
     * Attenuate by the sample's probability density value.
     */
    if (s.w > 0.0)
      att /= s.w;
    /*
     * The ray now travels through the scene of boxes and eventually either
     * hits a box or escapes through the open ceiling. So, test which case it is
     * going to be.
     */
    hitinfo hinfo;
    if (!intersectBoxes(origin, dir, hinfo)) {
      /*
       * The ray did not hit any box in the scene, so it escaped through the
       * open ceiling into the outside world, from which light enters the scene.
       */
      return LIGHT_INTENSITY * SKY_COLOR * att;
    }
    box b = boxes[hinfo.bi];
    /*
     * When we are here, the ray we are currently processing hit a box.
     * Next, we need the actual point of intersection. So evaluate the ray
     * equation `point = origin + t * dir` with `t` being the value returned by
     * intersectObjects() in the hitinfo.
     */
    vec3 point = origin + hinfo.near * dir;
    /*
     * Also calculate the normal based on the point on the box.
     */
    normal = hinfo.normal;
    /*
     * Next, we reset the ray's origin to the point of intersection offset by a
     * small epsilon along the surface normal to avoid intersecting that box
     * again because of precision issues with floating-point arithmetic.
     * Notice that we just overwrite the input parameter's value since we do not
     * need the original value anymore, which for the first bounce was the
     * intersection of the primary ray with the scene. 
     */
    origin = point + normal * EPSILON;
  }
  /*
   * When we reach here, no ray hit the ceiling/light. This means, no
   * light contribution from this ray.
   */
  return vec3(0.0);
}

void main(void) {
  /**
   * Initialize the 'px' variable which will be used as a spatial-varying
   * seed for the pseudo-random number generator.
   */
  px = ivec2(gl_FragCoord.xy);
  /**
   * Compute texture lookup coordinates from the current frame's clip position.
   */
  vec2 texCoord = clipPosition.xy * 0.5 + vec2(0.5);
  /**
   * Compute the previous frame's texture lookup coordinates.
   * We will use this to fetch into the previous frame textures to obtain
   * the filtered sample of the current pixel in the previous frame.
   * For simplicity, we perform a simple linear/box filter.
   */
  vec2 prevTexCoord = (prevClipPosition.xy / prevClipPosition.w) * 0.5 + vec2(0.5);
  /**
   * Now, lookup the view depth at the previous frame's position.
   */
  float prevViewDepth = texture(prevViewDepthTex, prevTexCoord).r;
  /**
   * The next factor will determine the number of frames this particular sample 
   * has been reused. If it is the first frame, then the value is 1.0.
   * This is used below to compute the factor when mixing between the
   * previous frame output and this frame output. In the literature the latter
   * term is usually denoted with a Greek 'alpha'.
   */
  float sampleFrame = 0.0;
  /**
   * Initialize the color of the previous pixel. This remains 0.0 if we cannot 
   * (yet) reproject a previous sample.
   */
  vec3 oldColor = vec3(0.0);
  /**
   * Check whether the previous frame's sample texture coordinates are valid
   * and the depth difference indicates no disocclusion. In this case, we can
   * reuse the previous sample.
   */
  if (all(greaterThanEqual(prevTexCoord, vec2(0.0))) &&
      all(lessThan(prevTexCoord, vec2(1.0))) &&
      abs(prevViewDepth - prevViewPosition.z) < 1E-1) {
    /**
     * Read the sample color from the previous frame.
     */
    vec4 oc = texture(prevColorTex, prevTexCoord);
    oldColor = oc.rgb;
    sampleFrame = oc.a;
  }
  /**
   * Since the primary ray has already been shot by rasterizing the scene
   * we already have the world position and the normal at the hit point.
   * We just compute a (safe) ray origin to go from there.
   */
  vec3 origin = worldPosition.xyz + worldNormal.xyz * 1E-4;
  /**
   * Trace the scene.
   */
  vec3 color = trace(origin, normalize(dir), worldNormal.xyz);
  /**
   * Compute the weighting factor between previous and this frame.
   */
  float alpha = sampleFrame/(sampleFrame+1.0);
  /**
   * Mix both frames.
   */
  color_out = vec4(mix(color, oldColor, alpha), min(sampleFrame + 1.0, MAX_SAMPLE_FRAME));
  /**
   * And also output the view depth of this frame.
   */
  viewDepth_out = viewPosition.z;
  normal_out = worldNormal.xyz;
}
