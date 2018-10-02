/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

#define SIDE_X_POS 0
#define SIDE_X_NEG 1
#define SIDE_Y_POS 2
#define SIDE_Y_NEG 3
#define SIDE_Z_POS 4
#define SIDE_Z_NEG 5
#define MAX_DESCEND 60.0
#define MAX_ROPES 30.0
#define EPSILON 0.0001
#define NO_NEIGHBOR -1
#define NO_CHILD -1
#define LARGE_FLOAT 1.E9

const vec4 background = vec4(0.1, 0.3, 0.5, 1.0);

layout(binding = 0, rgba8) writeonly uniform image2D framebufferImage;

uniform vec3 eye;
uniform vec3 ray00;
uniform vec3 ray01;
uniform vec3 ray10;
uniform vec3 ray11;
uniform bool debug;

/*
 * Total size: 80 bytes
 */
struct node {
  vec3 min;
  vec3 max;
  int splitAxis; // 0 = x, 1 = y, 2 = z
  float split;
  int ropes[6]; // -1 = no neighbor
  int left;  // -1 = no left
  int right; // -1 = no right
  int firstTri;
  int numTris;
  // int padding; <- std430 padding!
};
layout(std430, binding=2) readonly buffer Nodes {
  node[] nodes;
};

/*
 * Total size: 12 bytes
 */
struct triangle {
  vec3 v0;
  vec3 v1;
  vec3 v2;
};
layout(std430, binding=1) readonly buffer Triangles {
  triangle[] triangles;
};

float intersectTriangle(vec3 origin, vec3 ray, vec3 v0, vec3 v1, vec3 v2) {
  vec3 edge1 = v1 - v0;
  vec3 edge2 = v2 - v0;
  vec3 pvec = cross(ray, edge2);
  float det = dot(edge1, pvec);
  if (det <= 0.0)
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
  vec2 bounds;
  float t;
};

bool intersectTriangles(vec3 origin, vec3 dir, const node o, inout hitinfo info) {
  bool found = false;
  for (int i = o.firstTri; i < o.firstTri + o.numTris; i++) {
    const triangle tri = triangles[i];
    float t = intersectTriangle(origin, dir, tri.v0, tri.v1, tri.v2);
    if (t < info.t && t >= info.bounds.x - EPSILON) {
      info.t = t;
      found = true;
    }
  }
  return found;
}

/**
 * http://people.csail.mit.edu/amy/papers/box-jgt.pdf
 */
vec2 intersectCube(vec3 origin, vec3 dir, vec3 boxMin, vec3 boxMax) {
  vec3 invDir = vec3(1.0)/dir;
  float tNear, tFar, tymin, tymax, tzmin, tzmax;
  if (dir.x >= 0.0) {
    tNear = (boxMin.x - origin.x) * invDir.x;
    tFar = (boxMax.x - origin.x) * invDir.x;
  } else {
    tNear = (boxMax.x - origin.x) * invDir.x;
    tFar = (boxMin.x - origin.x) * invDir.x;
  }
  if (dir.y >= 0.0) {
    tymin = (boxMin.y - origin.y) * invDir.y;
    tymax = (boxMax.y - origin.y) * invDir.y;
  } else {
    tymin = (boxMax.y - origin.y) * invDir.y;
    tymax = (boxMin.y - origin.y) * invDir.y;
  }
  if (tNear > tymax || tymin > tFar)
    return vec2(1.0, -1.0);
  if (dir.z >= 0.0) {
    tzmin = (boxMin.z - origin.z) * invDir.z;
    tzmax = (boxMax.z - origin.z) * invDir.z;
  } else {
    tzmin = (boxMax.z - origin.z) * invDir.z;
    tzmax = (boxMin.z - origin.z) * invDir.z;
  }
  if (tNear > tzmax || tzmin > tFar)
    return vec2(1.0, -1.0);
  if (tymin > tNear || isnan(tNear))
    tNear = tymin;
  if (tymax < tFar || isnan(tFar))
    tFar = tymax;
  if (tzmin > tNear)
    tNear = tzmin;
  if (tzmax < tFar)
    tFar = tzmax;
  return vec2(tNear, tFar);
}

float exitCube(vec3 origin, vec3 dir, vec3 boxMin, vec3 boxMax) {
  return intersectCube(origin, dir, boxMin, boxMax).y;
}

/**
 * Reference: http://xboxforums.create.msdn.com/forums/t/98616.aspx
 */
int exitRope(node n, vec3 origin, vec3 dir, float lambdaY) {
  vec3 pos = origin + dir * lambdaY;
  vec3 distMin = abs(pos - n.min);
  vec3 distMax = abs(pos - n.max);
  int face = SIDE_X_NEG;
  float minDist = distMin.x;
  if (distMax.x < minDist && dir.x > 0.0) {
    face = SIDE_X_POS;
    minDist = distMax.x;
  }
  if (distMin.y < minDist && dir.y < 0.0) {
    face = SIDE_Y_NEG;
    minDist = distMin.y;
  }
  if (distMax.y < minDist && dir.y > 0.0) {
    face = SIDE_Y_POS;
    minDist = distMax.y;
  }
  if (distMin.z < minDist && dir.z < 0.0) {
    face = SIDE_Z_NEG;
    minDist = distMin.z;
  }
  if (distMax.z < minDist && dir.z > 0.0) {
    face = SIDE_Z_POS;
    minDist = distMin.z;
  }
  return n.ropes[face];
}

vec4 depth(node n, vec3 origin, vec3 dir) {
  hitinfo info;
  info.t = LARGE_FLOAT;
  info.bounds = intersectCube(origin, dir, n.min, n.max);
  vec2 statistics = vec2(0.0);
  while (info.bounds.x < info.bounds.y) {
    vec3 pEntry = origin + dir * info.bounds.x;
    while (n.left != NO_CHILD) {
      int nearIndex;
      if (n.split >= pEntry[n.splitAxis]) {
        nearIndex = n.left;
      } else {
        nearIndex = n.right;
      }
      n = nodes[nearIndex];
      if (statistics.x++ > MAX_DESCEND) {
        // Abort! Too many descends into children!
        // Might be a bug in the implementation.
        return vec4(1.0, 0.0, 0.0, 1.0);
      }
    }
    info.bounds.x -= EPSILON;
    if (intersectTriangles(origin, dir, n, info)) {
      vec3 hit = origin + dir * info.t;
      info.bounds.y = info.t;
      if (all(lessThanEqual(n.min, hit)) && all(lessThanEqual(hit, n.max)))
        break;
    }
    float exit = exitCube(origin, dir, n.min, n.max);
    int ropeId = exitRope(n, origin, dir, exit);
    info.bounds.x = exit;
    if (ropeId == NO_NEIGHBOR) {
      break;
    } else {
      n = nodes[ropeId];
    }
    if (statistics.y++ > MAX_ROPES) {
      // Abort! Followed too many ropes!
      // Might be a bug in the implementation.
      return vec4(0.0, 1.0, 1.0, 1.0);
    }
  }
  if (info.t == LARGE_FLOAT)
    return background;
  if (debug)
    return vec4(statistics.xyxy * 0.02);
  return vec4(info.t * 0.1);
}

layout (local_size_x = 16, local_size_y = 8) in;

void main(void) {
  ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (pix.x >= size.x || pix.y >= size.y) {
    return;
  }
  vec2 p = (vec2(pix) + vec2(0.5, 0.5)) / vec2(size.x, size.y);
  vec4 color = vec4(0.0, 0.0, 0.0, 1.0);
  vec3 dir = mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x);
  node rootNode = nodes[0];
  color = depth(rootNode, eye, dir);
  imageStore(framebufferImage, pix, color);
}
