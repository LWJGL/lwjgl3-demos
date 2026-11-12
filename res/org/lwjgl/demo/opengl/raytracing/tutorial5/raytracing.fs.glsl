/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

/**
 * The texture coordinate in [(0, 0), (1, 1)] of this fragment within the
 * framebuffer, coming from the full-screen triangle vertex shader.
 */
in vec2 texcoord;

/**
 * The three render targets we write to (see Tutorial5#createTraceFBO()).
 * Since this is now a fragment shader and not a compute shader anymore,
 * we do not write to images but to the color attachments of a
 * framebuffer object.
 * The first one receives the path-traced color and the other two the
 * world-space normal and position of the primary ray hit, which the
 * edge-avoiding A-Trous filter needs for its edge-stopping functions.
 */
out vec4 color_out;
out vec4 normal_out;
out vec4 position_out;

/**
 * Describes the view frustum of the camera via its world-space corner
 * edge vectors which we perform bilinear interpolation on to get the
 * world-space direction vector of a fragment's framebuffer pixel.
 * See function main().
 */
uniform vec3 eye, ray00, ray01, ray10, ray11;

/**
 * The current 'time'. We need all input we can get to generate
 * high-quality spatially and temporally varying pseudo-random numbers.
 */
uniform float time;

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
#define LIGHT_INTENSITY 60.0
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
vec4 randomPhongWeightedHemispherePoint(vec3 r, float a, vec2 rand);
float phongProbability(vec3 r, float a, vec3 v);

/**
 * Describes an axis-aligned box by its minimum and maximum corner 
 * oordinates.
 */
struct box {
  vec3 min, max, col;
};

struct sphere {
  vec3 c;
  float r;
};

/**
 * Our scene description is very simple. We just use a static array
 * of boxes, each defined by its minimum and maximum corner coordinates.
 */
const box boxes[NUM_BOXES] = box[NUM_BOXES](
  box(vec3(-5.0, -0.1, -5.0), vec3( 5.0, 0.0,  5.0), vec3(0.50, 0.45, 0.33)),// <- floor
  box(vec3(-5.1,  0.0, -5.0), vec3(-5.0, 5.0,  5.0), vec3(0.4, 0.4, 0.4)),   // <- left wall
  box(vec3( 5.0,  0.0, -5.0), vec3( 5.1, 5.0,  5.0), vec3(0.4, 0.4, 0.4)),   // <- right wall
  box(vec3(-5.0,  0.0, -5.1), vec3( 5.0, 5.0, -5.0), vec3(0.43, 0.52, 0.27)),// <- back wall
  box(vec3(-5.0,  0.0,  5.0), vec3( 5.0, 5.0,  5.1), vec3(0.5, 0.2, 0.09)),  // <- front wall
  box(vec3(-1.0,  1.0, -1.0), vec3( 1.0, 1.1,  1.0), vec3(0.3, 0.23, 0.15)), // <- table top
  box(vec3(-1.0,  0.0, -1.0), vec3(-0.8, 1.0, -0.8), vec3(0.4, 0.3, 0.15)),  // <- table foot
  box(vec3(-1.0,  0.0,  0.8), vec3(-0.8, 1.0,  1.0), vec3(0.4, 0.3, 0.15)),  // <- table foot
  box(vec3( 0.8,  0.0, -1.0), vec3( 1.0, 1.0, -0.8), vec3(0.4, 0.3, 0.15)),  // <- table foot
  box(vec3( 0.8,  0.0,  0.8), vec3( 1.0, 1.0,  1.0), vec3(0.4, 0.3, 0.15)),  // <- table foot
  box(vec3( 3.0,  0.0, -4.9), vec3( 3.3, 2.0, -4.6), vec3(0.6, 0.6, 0.6))    // <- some "pillar"
);

const sphere spheres[NUM_SPHERES] = sphere[NUM_SPHERES](
  sphere(vec3(0.0, 10.0, 0.0), 1.8)
);

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
  /*
   * We need to distinguish whether the thing we hit is a box or sphere.
   */
  bool isSphere;
};

/**
 * We hold the framebuffer pixel coordinate of the current fragment to
 * not drag it as parameter all over the place. In addition to "time"
 * this parameter provide spatial variance for generated pseudo-random
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
 * It is important that no two pixels get the same random numbers, also
 * not for different bounces. Otherwise, the noise of those two pixels
 * will be correlated, and the filter cannot remove correlated noise by
 * averaging neighboring pixels as well as it can remove independent
 * noise. That is why we do not add the bounce index to the pixel
 * coordinate (the second bounce of a pixel would then use the random
 * numbers of the first bounce of its diagonal neighbor) but to the
 * time.
 * 
 * @param s this is the "bounce index" to get different pseudo-random
 *          numbers each bounce
 * @returns a vector containing three floating-point pseudo-random
 *          numbers, each in the range [0, 1)
 */
vec3 randvec3(int s) {
  return random3(vec3(px, time + float(s) * 1.6180339));
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
 * Write the given position 'p' and normal 'n' to the render targets
 * for the current fragment.
 *
 * @param p the position
 * @param n the normal
 */
void write(vec3 p, vec3 n) {
  normal_out = vec4(n, 0.0);
  position_out = vec4(p, 1.0);
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
       if (bounce == 0) {
         /* Write zero to the position and normal output buffer */
         write(vec3(0.0), vec3(0.0));
       }
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
      sphere s = spheres[hinfo.i];
      vec3 sn = normalForSphere(point, s);
      if (bounce == 0) {
        /*
         * For the filter we are outputting the position and normal
         * in the corresponding render targets.
         */
        write(point, sn);
      }
      return att * LIGHT_INTENSITY * dot(sn, -dir);
    } else {
      /*
       * We hit a box. Proceed as in the last tutorial part.
       */
      box b = boxes[hinfo.i];
      /*
       * When the ray hits a box exactly at an edge or a corner, the
       * normal computed by intersectBox() is the sum of the normals of
       * the adjacent faces and therefore not of unit length. This is
       * very rare, but it will happen, and then the BRDF evaluation
       * below overflows and gives us a NaN sample. Such a NaN would
       * never go away again when accumulating frames, and the filter
       * would spread it to all pixels in its neighborhood.
       */
      normal = normalize(hinfo.normal);
      albedo = b.col;
      if (bounce == 0) {
        /*
         * For the filter we are outputting the position and normal
         * in the corresponding render targets.
         */
        write(point, normal);
      }
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
       * Also compute the direction of perfect reflection, around which
       * the specular part of our BRDF has its lobe.
       */
      vec3 r = reflect(dir, normal);
      /*
       * We are going to use the "One-sample model" proposed by Veach.
       * This means, we use one of three sampling strategies. Either
       * sampling the light source, sampling the specular lobe of the
       * BRDF or sampling the hemisphere.
       * The first one is good when we can expect to hit the light
       * and get fast convergence/low variance quickly. This approach
       * however is not ideal when we do not directly hit the light and
       * should instead sample the BRDF.
       * On the other hand, sampling the BRDF can not be optimal either,
       * for example when having a strong specular lobe which does not
       * point towards any light.
       * That's why we will use randomly one of the strategies by
       * letting a random variable decide which one it is going to be.
       *
       * Half of the time we sample the light. The other half is split
       * like in Tutorial 3: We sample the specular lobe with a
       * probability equal to the specular factor of the BRDF and the
       * hemisphere otherwise.
       *
       * Sampling the specular lobe is essential for the filter to work
       * with specular surfaces. With a high Phong exponent, the BRDF is
       * very big in a very small cone of directions and close to zero
       * everywhere else. Hemisphere sampling only very rarely generates
       * a direction inside of that cone. But when it does, that sample
       * has to make up for all the others which missed the cone and is
       * extremely bright. A few extremely bright pixels between many
       * pixels without any reflection is not something a filter, which
       * only averages a pixel with a few hundred pixels around it, can
       * turn into a smooth reflection.
       */
      float pl = PROBABILITY_OF_LIGHT_SAMPLE;
      float ps = (1.0 - pl) * specularFactor;
      float ph = (1.0 - pl) * (1.0 - specularFactor);
      if (rand.z < pl) {
        /*
         * We want to sample the light source. From our point of view
         * the spherical light source is a projected circle/disk and we
         * need a way to uniformly sample a disk. So call a function
         * which does exactly that.
         */
        s = randomDiskPoint(n, length(d), li.r, rand.xy);
      } else if (rand.z < pl + ps) {
        /*
         * We want to sample the specular lobe of the BRDF. So, generate
         * a Phong-weighted vector around the direction of perfect
         * reflection.
         */
        s = randomPhongWeightedHemispherePoint(r, phongExponent, rand.xy);
      } else {
        /*
         * We want to sample uniformly over the hemisphere instead.
         */
        s = randomHemispherePoint(normal, rand.xy);
      }
      /*
       * We will be using the "balance heuristic" weighting strategy
       * of multiple importance sampling. For that, it does not matter
       * which of the strategies actually generated our sample
       * direction. Any of them could have. So, the probability density
       * of the direction is the sum of the probability density values
       * of all strategies for that direction, each one weighted with
       * the probability of that strategy being chosen.
       */
      s.w = pl * diskProbability(n, length(d), li.r, s.xyz)
          + ps * phongProbability(r, phongExponent, s.xyz)
          + ph * hemisphereProbability(normal, s.xyz);
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

/**
 * Entry point of this GLSL fragment shader.
 */
void main(void) {
  /*
   * Obtain the 2D index of the pixel of the current fragment via the
   * built-in gl_FragCoord variable and store it in a 'px' variable
   * because we need it later.
   */
  px = ivec2(gl_FragCoord.xy);
  /*
   * Now we take our rayNN uniforms declared above to determine the
   * world-space direction from the eye position through the current
   * fragment's pixel center in the framebuffer. In the compute shader
   * of the previous parts we had to compute the position of that pixel
   * center in the interval [(0, 0), (1, 1)] ourselves. Now, the
   * rasterizer does it for us by interpolating the 'texcoord' output
   * of the vertex shader over the full-screen triangle.
   *
   * Use bilinear interpolation based on the X and Y fraction
   * (within 0..1) with our rayNN vectors defining the world-space
   * vectors along the corner edges of the camera's view frustum. The
   * result is the world-space direction of the ray originating from the
   * camera/eye center through the fragment's framebuffer pixel center.
   */
  vec2 p = texcoord;
  vec3 dir = mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x);
  /*
   * Trace the list of boxes with the initial ray `eye + t * dir` and
   * follow it along in the scene. The result is a computed color which
   * will be combined with the current/previous framebuffer content.
   *
   * A compute shader can read from and write to the same image, and so
   * could mix the new color with the old color itself. A fragment
   * shader cannot read from the render target it is currently rendering
   * to. But we do not have to, because mixing an incoming color with
   * the color already stored in the framebuffer is exactly what OpenGL
   * blending does. So, we simply output the new color and let the
   * blending stage, which gets configured in Tutorial5#trace(), compute:
   *   finalColor = blendFactor * oldColor + (1 - blendFactor) * newColor
   */
  color_out = vec4(trace(eye, normalize(dir)), 1.0);
}
