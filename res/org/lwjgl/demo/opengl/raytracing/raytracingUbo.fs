/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform sampler2D framebuffer;

uniform CameraSettings {
  vec3 eye;
  vec3 ray00;
  vec3 ray01;
  vec3 ray10;
  vec3 ray11;
};

uniform float blendFactor;
uniform float time;
uniform float width;
uniform float height;
uniform int bounceCount;

in vec2 texcoord;

out vec4 color;

struct box {
  vec3 min;
  vec3 max;
  int mat;
};

#define MAX_SCENE_BOUNDS 100.0
#define NUM_BOXES 7

const box boxes[] = box[7](
  /* The ground */
  box(vec3(-5.0, -0.1, -5.0), vec3(5.0, 0.0, 5.0), 0),
  /* Box in the middle */
  box(vec3(-0.5, 0.0, -0.5), vec3(0.5, 1.0, 0.5), 1),
  /* Left wall */
  box(vec3(-5.1, 0.0, -5.0), vec3(-5.0, 5.0, 5.0), 2),
  /* Right wall */
  box(vec3(5.0, 0.0, -5.0), vec3(5.1, 5.0, 5.0), 3),
  /* Back wall */
  box(vec3(-5.0, 0.0, -5.1), vec3(5.0, 5.0, -5.0), 0),
  /* Front wall */
  box(vec3(-5.0, 0.0, 5.0), vec3(5.0, 5.0, 5.1), 0),
  /* Top wall */
  box(vec3(-5.0, 5.0, -5.0), vec3(5.0, 5.1, 5.0), 4)
);

#define EPSILON 0.00001
#define LIGHT_RADIUS 0.9

#define LIGHT_BASE_INTENSITY 20.0
const vec3 lightCenterPosition = vec3(1.5, 2.9, 3);
const vec4 lightColor = vec4(1);

vec3 random3(vec3 f);
vec3 randomDiskPoint(vec3 rand, vec3 n);
vec3 randomHemispherePoint(vec3 rand, vec3 n);
vec3 randomCosineWeightedHemispherePoint(vec3 rand, vec3 dir);

struct hitinfo {
  float near;
  vec3 normal;
  float far;
  int bi;
};

/*
 * We need random values every now and then.
 * So, they will be precomputed for each ray we trace and
 * can be used by any function.
 */
vec3 rand;
vec3 cameraUp;

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

vec4 colorOfBox(const box b) {
  vec4 col;
  if (b.mat == 0) {
    col = vec4(1.0, 1.0, 1.0, 1.0);
  } else if (b.mat == 1) {
    col = vec4(1.0, 0.2, 0.2, 1.0);
  } else if (b.mat == 2) {
    col = vec4(0.2, 0.2, 1.0, 1.0);
  } else if (b.mat == 3) {
    col = vec4(0.2, 1.0, 0.2, 1.0);
  } else {
    col = vec4(0.5, 0.5, 0.5, 1.0);
  }
  return col;
}

bool intersectBoxes(vec3 origin, vec3 dir, out hitinfo info) {
  float smallest = MAX_SCENE_BOUNDS;
  bool found = false;
  vec3 normal;
  for (int i = 0; i < NUM_BOXES; i++) {
    vec2 lambda = intersectBox(origin, dir, boxes[i], normal);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.near = lambda.x;
      info.far = lambda.y;
      info.normal = normal;
      info.bi = i;
      smallest = lambda.x;
      found = true;
    }
  }
  return found;
}

vec4 trace(vec3 origin, vec3 dir) {
  hitinfo i;
  vec4 accumulated = vec4(0.0);
  vec4 attenuation = vec4(1.0);
  for (int bounce = 0; bounce < bounceCount; bounce++) {
    if (intersectBoxes(origin, dir, i)) {
      box b = boxes[i.bi];
      vec3 hitPoint = origin + i.near * dir;
      vec3 normal = i.normal;
      vec3 lightNormal = normalize(hitPoint - lightCenterPosition);
      vec3 lightPosition = lightCenterPosition + randomDiskPoint(rand, lightNormal) * LIGHT_RADIUS;
      vec3 shadowRayDir = lightPosition - hitPoint;
      vec3 shadowRayStart = hitPoint + normal * EPSILON;
      hitinfo shadowRayInfo;
      bool lightObstructed = intersectBoxes(shadowRayStart, shadowRayDir, shadowRayInfo);
      attenuation *= colorOfBox(b);
      if (shadowRayInfo.near >= 1.0) {
        float cosineFallOff = max(0.0, dot(normal, normalize(shadowRayDir)));
        float oneOverR2 = 1.0 / dot(shadowRayDir, shadowRayDir);
        accumulated += attenuation * vec4(lightColor * LIGHT_BASE_INTENSITY * cosineFallOff * oneOverR2);
      }
      origin = shadowRayStart;
      //dir = randomHemispherePoint(rand, normal);
      dir = randomCosineWeightedHemispherePoint(rand, normal);
      attenuation *= dot(normal, dir);
    } else {
      break;
    }
  }
  return accumulated;
}

void main(void) {
  vec2 pos = texcoord;
  vec4 newColor = vec4(0.0, 0.0, 0.0, 1.0);
  cameraUp = normalize(ray01 - ray00);
  for (int s = 0; s < 1; s++) {
    rand = random3(vec3(pos, time + float(s)));
    vec2 jitter = rand.xy / vec2(width, height);
    vec2 p = pos + jitter;
    vec3 dir = mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x);
    newColor += trace(eye, dir);
  }
  newColor /= 1;
  vec4 oldColor = vec4(0.0);
  oldColor = texture(framebuffer, pos);
  color = mix(newColor, oldColor, blendFactor);
}
