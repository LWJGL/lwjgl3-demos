/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

layout(binding = 0, rgba32f) uniform image2D framebufferImage;
layout(binding = 1, rgba32f) uniform readonly image2D worldPositionImage;
layout(binding = 2, rgba16f) uniform readonly image2D worldNormalImage;

uniform float blendFactor;
uniform float time;
uniform float lightRadius;

const vec4 background = vec4(0.1, 0.3, 0.5, 1.0);

struct triangle {
  vec3 v0;
  vec3 v1;
  vec3 v2;
};

struct object {
  vec3 min;
  vec3 max;
  int first;
  int count;
};

layout(std430, binding=2) readonly buffer Objects {
  object[] objects;
};

layout(std430, binding=1) readonly buffer Triangles {
  triangle[] triangles;
};

#define MAX_SCENE_BOUNDS 100.0
#define EPSILON 0.0001
#define LIGHT_BASE_INTENSITY 5.0

const vec3 lightCenterPosition = vec3(1.5, 1.5, 1.5);
const vec4 lightColor = vec4(1);

float random(vec2 f, float time);
vec3 randomDiskPoint(vec3 rand, vec3 n);

/*
 * We need random values every now and then.
 * So, they will be precomputed for each ray we trace and
 * can be used by any function.
 */
vec3 rand;
vec3 cameraUp;

vec2 intersectObject(vec3 origin, vec3 dir, const object o) {
  vec3 tMin = (o.min - origin) / dir;
  vec3 tMax = (o.max - origin) / dir;
  vec3 t1 = min(tMin, tMax);
  vec3 t2 = max(tMin, tMax);
  float tNear = max(max(t1.x, t1.y), t1.z);
  float tFar = min(min(t2.x, t2.y), t2.z);
  return vec2(tNear, tFar);
}

struct objecthitinfo {
  float near;
  float far;
  int index;
};

float intersectTriangle(vec3 origin, vec3 ray, vec3 v0, vec3 v1, vec3 v2) {
  vec3 edge1 = v1 - v0;
  vec3 edge2 = v2 - v0;
  vec3 pvec = cross(ray, edge2);
  float det = dot(edge1, pvec);
  if (det <= 0)
    return -1.0;
  vec3 tvec = origin - v0;
  float u = dot(tvec, pvec);
  if (u < 0.0 || u > det)
    return -1.0;
  vec3 qvec = cross(tvec, edge1);
  float v = dot(ray, qvec);
  if (v < 0.0 || u + v > det)
    return -1.0;
  float invDet = 1.0 / det;
  float t = dot(edge2, qvec) * invDet;
  return t;
}

struct hitinfo {
  float t;
};

bool intersectTriangles(vec3 origin, vec3 dir, const object o, inout hitinfo info) {
  bool found = false;
  for (int i = o.first; i < o.first + o.count; i++) {
    const triangle tri = triangles[i];
    float t = intersectTriangle(origin, dir, tri.v0, tri.v1, tri.v2);
    if (t >= 0.0 && t < info.t) {
      info.t = t;
      found = true;
    }
  }
  return found;
}

bool intersect(vec3 origin, vec3 dir, out hitinfo info) {
  info.t = MAX_SCENE_BOUNDS;
  bool hit = false;
  int numObjects = objects.length();
  for (int i = 0; i < numObjects; i++) {
    const object o = objects[i];
    vec2 lambda = intersectObject(origin, dir, o);
    if (lambda.y >= 0.0 && lambda.x <= lambda.y && lambda.x < info.t) {
      if (intersectTriangles(origin, dir, o, info)) {
        hit = true;
      }
    }
  }
  return hit;
}

vec4 trace(vec3 hitPoint, vec3 normal) {
  hitinfo i;
  vec4 accumulated = vec4(0.0);
  vec4 attenuation = vec4(1.0);
  bool intersected = false;
  vec3 lightNormal = normalize(hitPoint - lightCenterPosition);
  vec3 lightPosition = lightCenterPosition + randomDiskPoint(rand, lightNormal) * lightRadius;
  vec3 shadowRayDir = lightPosition - hitPoint;
  vec3 shadowRayStart = hitPoint + normal * EPSILON;
  hitinfo shadowRayInfo;
  bool lightObstructed = intersect(shadowRayStart, shadowRayDir, shadowRayInfo);
  if (!lightObstructed || shadowRayInfo.t >= 1.0) {
    float cosineFallOff = max(0.0, dot(normal, normalize(shadowRayDir)));
    float oneOverR2 = 1.0 / dot(shadowRayDir, shadowRayDir);
    accumulated += attenuation * vec4(lightColor * LIGHT_BASE_INTENSITY * cosineFallOff * oneOverR2);
  }
  return accumulated;
}

layout (local_size_x = 16, local_size_y = 8) in;

void main(void) {
  ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (pix.x >= size.x || pix.y >= size.y) {
    return;
  }

  vec4 worldPosition = imageLoad(worldPositionImage, pix);
  if (worldPosition.a == 0.0) {
    // alpha channel encodes whether the worldPositionImage actually
    // contains rastered geometry at this texel.
    // If not, we just write the background color and abort.
    imageStore(framebufferImage, pix, background);
    return;
  }
  vec3 worldNormal = imageLoad(worldNormalImage, pix).xyz;

  vec2 pos = (vec2(pix) + vec2(0.5, 0.5)) / vec2(size.x, size.y);
  cameraUp = vec3(0.0, 1.0, 0.0);

  float rand1 = random(pix, time);
  float rand2 = random(pix + vec2(641.51224, 423.178), time);
  float rand3 = random(pix - vec2(147.16414, 363.941), time);
  /* Set global 'rand' variable */
  rand = vec3(rand1, rand2, rand3);

  vec4 color = vec4(0.0, 0.0, 0.0, 1.0);
  color += trace(worldPosition.xyz, worldNormal);

  vec4 oldColor = vec4(0.0);
  if (blendFactor > 0.0) {
    oldColor = imageLoad(framebufferImage, pix);
  }
  vec4 finalColor = mix(color, oldColor, blendFactor);
  imageStore(framebufferImage, pix, finalColor);
}
