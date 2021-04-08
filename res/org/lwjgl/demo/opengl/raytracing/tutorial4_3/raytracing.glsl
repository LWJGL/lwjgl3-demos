/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

#if GL_NV_gpu_shader5
#extension GL_NV_gpu_shader5 : enable
#elif GL_EXT_shader_8bit_storage
#extension GL_EXT_shader_8bit_storage : enable
#endif

layout(binding = 0, rgba32f) uniform image2D framebufferImage;
uniform vec3 eye, ray00, ray01, ray10, ray11;
uniform float time;
uniform int frameIndex;
uniform float blendFactor;
uniform bool useBlueNoise;
uniform float phongExponent;
uniform float specularFactor;

layout(std430, binding = 0) readonly buffer SobolBuffer {
  uint8_t[] sobols;
};
layout(std430, binding = 1) readonly buffer ScrambleBuffer {
  uint8_t[] scrambles;
};
layout(std430, binding = 2) readonly buffer RankingBuffer {
  uint8_t[] rankings;
};

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)
#define LARGE_FLOAT 1E+10
#define NUM_BOXES 11
#define NUM_RECTANGLES 1
#define EPSILON 1E-4
#define BOUNCES 3
#define LIGHT_INTENSITY 80.0
#define PROBABILITY_OF_LIGHT_SAMPLE 0.6

uvec3 pcg3d(uvec3 v);
vec3 random3(vec3 f);
vec4 randomHemisphereDirection(vec3 n, vec2 rand);
vec4 randomCosineWeightedHemisphereDirection(vec3 n, vec2 rand);
float randomCosineWeightedHemisphereDirectionPDF(vec3 n, vec3 v);
vec4 randomPhongWeightedHemisphereDirection(vec3 r, float a, vec2 rand);
float randomPhongWeightedHemisphereDirectionPDF(vec3 r, float a, vec3 v);
vec4 randomRectangleAreaDirection(vec3 p, vec3 c, vec3 x, vec3 y, vec2 rand);
float randomRectangleAreaDirectionPDF(vec3 p, vec3 c, vec3 x, vec3 y, vec3 v);
bool inrect(vec3 p, vec3 c, vec3 x, vec3 y);

struct box {
  vec3 min, max, col;
};
struct rectangle {
  vec3 c;
  vec3 x;
  vec3 y;
};
const box boxes[NUM_BOXES] = {
  {vec3(-5.0, -0.1, -5.0), vec3( 5.0, 0.0,  5.0), vec3(0.90, 0.85, 0.78)},// <- floor
  {vec3(-5.1,  0.0, -5.0), vec3(-5.0, 5.0,  5.0), vec3(0.96, 0.96, 0.96)},   // <- left wall
  {vec3( 5.0,  0.0, -5.0), vec3( 5.1, 5.0,  5.0), vec3(0.96, 0.96, 0.96)},   // <- right wall
  {vec3(-5.0,  0.0, -5.1), vec3( 5.0, 5.0, -5.0), vec3(0.63, 0.82, 0.37)},// <- back wall
  {vec3(-5.0,  0.0,  5.0), vec3( 5.0, 5.0,  5.1), vec3(0.96, 0.6, 0.29)},  // <- front wall
  {vec3(-1.0,  1.0, -1.0), vec3( 1.0, 1.1,  1.0), vec3(0.3, 0.23, 0.15)*1.5}, // <- table top
  {vec3(-1.0,  0.0, -1.0), vec3(-0.8, 1.0, -0.8), vec3(0.9, 0.6, 0.25)},  // <- table foot
  {vec3(-1.0,  0.0,  0.8), vec3(-0.8, 1.0,  1.0), vec3(0.9, 0.6, 0.25)},  // <- table foot
  {vec3( 0.8,  0.0, -1.0), vec3( 1.0, 1.0, -0.8), vec3(0.9, 0.6, 0.25)},  // <- table foot
  {vec3( 0.8,  0.0,  0.8), vec3( 1.0, 1.0,  1.0), vec3(0.9, 0.6, 0.25)},  // <- table foot
  {vec3( 3.0,  0.0, -4.9), vec3( 3.3, 2.0, -4.6), vec3(0.9, 0.8, 0.8)}    // <- some "pillar"
};
const rectangle rectangles[NUM_RECTANGLES] = {
  {vec3(-3.0, 2.0, 3.0), vec3(6.0, 0.0, 0.0), vec3(0.0, -0.2, 0.05)}
};

struct hitinfo {
  vec3 normal;
  float near;
  int i;
  bool isRectangle;
};

ivec2 px;

bool intersectRectangle(vec3 origin, vec3 dir, rectangle r, out hitinfo hinfo) {
  vec3 n = cross(r.x, r.y);
  float den = dot(n, dir), t = dot(r.c - origin, n) / den;
  hinfo.near = t;
  return den < 0.0 && t > 0.0 && inrect(origin + t * dir, r.c, r.x, r.y);
}

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
      info.isRectangle = false;
      info.normal = normal;
      smallest = lambda.x;
      found = true;
    }
  }
  /* Also test the rectangles */
  for (int i = 0; i < NUM_RECTANGLES; i++) {
    hitinfo hinfo;
    if (intersectRectangle(origin, dir, rectangles[i], hinfo) && hinfo.near < smallest) {
      info.near = hinfo.near;
      info.i = i;
      info.isRectangle = true;
      smallest = hinfo.near;
      found = true;
    }
  }
  return found;
}
vec3 normalForRectangle(vec3 hit, const rectangle r) {
  return cross(r.x, r.y);
}

float sampleBlueNoise(uint sampleDimension) {
  uvec2 off = pcg3d(uvec3(px.y>>7u, px.x>>7u, frameIndex >> 8u)).xy;
  uvec2 pxo = (px + ivec2(off)) & 127u;
  uint sampleIndex = frameIndex & 255u;
  sampleDimension = sampleDimension & 255u;
  uint pxv = (pxo.x + (pxo.y<<7u))<<3u;
  uint rankedSampleIndex = sampleIndex ^ rankings[sampleDimension + pxv];
  uint value = sobols[sampleDimension + (rankedSampleIndex << 8u)];
  value ^= scrambles[(sampleDimension & 7u) + pxv];
  return (0.5 + float(value)) / 256.0;
}

vec3 randBlueNoise(uint s) {
  return vec3(
    sampleBlueNoise(3*s),
    sampleBlueNoise(3*s+1),
    sampleBlueNoise(3*s+2)
  );
}
vec3 randWhiteNoise(int s) {
  return random3(vec3(px + ivec2(s), time));
}
vec3 randvec3(int s) {
  return useBlueNoise ? randBlueNoise(s) : randWhiteNoise(s);
}

vec3 brdfSpecular(vec3 i, vec3 o, vec3 n) {
  float a = phongExponent;
  vec3 r = reflect(-i, n);
  return vec3(pow(max(0.0, dot(r, o)), a) * (a + 2.0) * ONE_OVER_2PI);
}
vec3 brdfDiffuse(vec3 albedo, vec3 i, vec3 o, vec3 n) {
  return albedo * ONE_OVER_PI;
}
vec3 brdf(vec3 albedo, vec3 i, vec3 o, vec3 n) {
  return brdfSpecular(i, o, n) * specularFactor
         +
         brdfDiffuse(albedo, i, o, n) * (1.0 - specularFactor);
}

vec3 trace(vec3 origin, vec3 dir) {
  vec3 att = vec3(1.0);
  for (int bounce = 0; bounce < BOUNCES; bounce++) {
    hitinfo hinfo;
    if (!intersectObjects(origin, dir, hinfo))
       return vec3(0.0);
    vec3 point = origin + hinfo.near * dir;
    vec3 normal, albedo;
    if (hinfo.isRectangle) {
      const rectangle r = rectangles[hinfo.i];
      return att * LIGHT_INTENSITY;
    } else {
      const box b = boxes[hinfo.i];
      normal = hinfo.normal;
      albedo = b.col;
    }
    origin = point + normal * EPSILON;
    vec3 rand = randvec3(bounce);
    rectangle li = rectangles[0];
    vec4 s;
    float wl = PROBABILITY_OF_LIGHT_SAMPLE;
    vec3 ps = vec3(wl, specularFactor, 1.0 - specularFactor);
    vec3 p = ps / (ps.x + ps.y + ps.z);
    vec2 cdf = vec2(p.x, p.x + p.y);
    vec3 w, r = reflect(dir, normal);
    if (rand.z < cdf.x) {
      s = randomRectangleAreaDirection(origin, li.c, li.x, li.y, rand.xy);
      w = vec3(s.w,
               randomPhongWeightedHemisphereDirectionPDF(r, phongExponent, s.xyz),
               randomCosineWeightedHemisphereDirectionPDF(normal, s.xyz));
    } else if (rand.z < cdf.y) {
      s = randomPhongWeightedHemisphereDirection(r, phongExponent, rand.xy);
      w = vec3(randomCosineWeightedHemisphereDirectionPDF(normal, s.xyz),
               randomRectangleAreaDirectionPDF(origin, li.c, li.x, li.y, s.xyz),
               s.w);
    } else {
      s = randomCosineWeightedHemisphereDirection(normal, rand.xy);
      w = vec3(randomRectangleAreaDirectionPDF(origin, li.c, li.x, li.y, s.xyz),
               randomPhongWeightedHemisphereDirectionPDF(r, phongExponent, s.xyz),
               s.w);
    }
    s.w = dot(w, p);
    att *= brdf(albedo, s.xyz, -dir, normal);
    dir = s.xyz;
    att *= max(0.0, dot(dir, normal));
    if (s.w > 0.0)
      att /= s.w;
  }
  return vec3(0.0);
}

layout (local_size_x = 16, local_size_y = 16) in;

void main(void) {
  px = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (any(greaterThanEqual(px, size)))
    return;
  vec2 p = (vec2(px) + vec2(0.5)) / vec2(size);
  vec3 dir = mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x);
  vec3 color = trace(eye, normalize(dir));
  vec3 oldColor = vec3(0.0);
  if (blendFactor > 0.0)
    oldColor = imageLoad(framebufferImage, px).rgb;
  vec3 finalColor = mix(color, oldColor, blendFactor);
  imageStore(framebufferImage, px, vec4(finalColor, 1.0));
}
