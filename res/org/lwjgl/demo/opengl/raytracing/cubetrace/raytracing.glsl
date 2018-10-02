/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

#define NO_NODE -1
#define ERROR_COLOR vec3(1.0, 0.0, 1.0)
#define MAX_FOLLOWS 750

layout(binding = 0, rgba8) writeonly uniform image2D framebufferImage;
uniform vec3 eye, ray00, ray01, ray10, ray11;
uniform ivec2 off, cbwidth;

struct node {
  vec3 min; uint left;
  vec3 max; uint right;
  uint parent, firstVoxel, numVoxels;
};
layout(std430, binding = 0) readonly buffer Nodes {
  node[] nodes;
};

struct voxel {
  uvec3 p; uint c;
};
layout(std430, binding = 1) readonly buffer Voxels {
  voxel[] voxels;
};

vec3 decodeColor(uint col) {
  return vec3(col & 0x3FFu, (col >> 10u) & 0x3FF, (col >> 20u) & 0x3FF) / 1024.0;
}

bool intersectBox(const in vec3 origin, const in vec3 invdir,
                  const in vec3 boxMin, const in vec3 boxMax, inout float t) {
  bvec3 lt = lessThan(invdir, vec3(0.0));
  vec3 m1 = (boxMin - origin) * invdir, m2 = (boxMax - origin) * invdir;
  vec3 tmin = mix(m1, m2, lt), tmax = mix(m2, m1, lt);
  float mint = max(max(tmin.x, tmin.y), tmin.z);
  float maxt = min(min(tmax.x, tmax.y), tmax.z);
  bool cond = mint < t && maxt >= 0.0 && mint < maxt;
  t = mix(t, mint, cond);
  return cond;
}

bool intersectVoxels(const in vec3 origin, const in vec3 invdir, const uint firstVoxel,
                     const uint numVoxels, inout float t, out voxel hitvoxel) {
  bool hit = false;
  for (uint i = 0; i < numVoxels; i++) {
    const voxel v = voxels[i + firstVoxel];
    if (intersectBox(origin, invdir, vec3(v.p), vec3(v.p) + vec3(1.0), t)) {
      hit = true;
      hitvoxel = v;
    }
  }
  return hit;
}

vec3 normalForVoxel(const in vec3 hit, const in vec3 dir, const in uvec3 v) {
  float dx = dir.x > 0.0 ? abs(hit.x - float(v.x)) : abs(hit.x - float(v.x+1));
  float dy = dir.y > 0.0 ? abs(hit.y - float(v.y)) : abs(hit.y - float(v.y+1));
  float dz = dir.z > 0.0 ? abs(hit.z - float(v.z)) : abs(hit.z - float(v.z+1));
  if (dx < dy && dx < dz)
    return dir.x < 0.0 ? vec3(1.0, 0.0, 0.0) : vec3(-1.0, 0.0, 0.0);
  else if (dy < dz)
    return dir.y < 0.0 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, -1.0, 0.0);
  else
    return dir.z < 0.0 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 0.0, -1.0);
}

uint closestChild(const in vec3 origin, const in node n, inout uint leftRightStack) {
  if (n.left == NO_NODE)
    return NO_NODE;
  const node left = nodes[n.left], right = nodes[n.right];
  const vec3 midLeft = (left.min + left.max) * vec3(0.5);
  const vec3 midRight = (right.min + right.max) * vec3(0.5);
  const vec3 dl = origin - midLeft, dr = origin - midRight;
  uint res;
  if (dot(dl, dl) < dot(dr, dr)) {
    leftRightStack <<= 1;
    res = n.left;
  } else {
    leftRightStack = leftRightStack << 1 | 1;
    res = n.right;
  }
  return res;
}

bool processNextFarChild(inout uint nearFarStack, inout uint leftRightStack,
                         const in uint pidx, inout uint nextIdx) {
  node parent = nodes[pidx];
  while ((nearFarStack & 1u) == 1u) {
    nearFarStack >>= 1;
    leftRightStack >>= 1;
    if (parent.parent == NO_NODE)
      return false;
    parent = nodes[parent.parent];
  }
  nextIdx = (leftRightStack & 1u) == 0u ? parent.right : parent.left;
  nearFarStack |= 1;
  return true;
}

vec3 trace(const in vec3 origin, const in vec3 dir, const in vec3 invdir) {
  float nt = 1.0/0.0, bt = 1.0/0.0;
  vec3 normal = vec3(0.0), col = vec3(1.0);
  uint nextIdx = 0u, iterations = 0u, leftRightStack = 0u, nearFarStack = 0u;
  while (true) {
    if (iterations++ > MAX_FOLLOWS)
      return ERROR_COLOR;
    const node next = nodes[nextIdx];
    if (!intersectBox(origin, invdir, next.min, next.max, bt)) {
      if (!processNextFarChild(nearFarStack, leftRightStack, next.parent, nextIdx))
        break;
    } else {
      if (next.numVoxels > 0) {
        voxel hitvoxel;
        if (intersectVoxels(origin, invdir, next.firstVoxel, next.numVoxels, nt, hitvoxel)) {
          normal = normalForVoxel(origin + nt * dir, dir, hitvoxel.p);
          col = decodeColor(hitvoxel.c);
        }
        if (!processNextFarChild(nearFarStack, leftRightStack, next.parent, nextIdx))
          break;
      } else {
        nextIdx = closestChild(origin, next, leftRightStack);
        nearFarStack <<= 1;
      }
      bt = nt;
    }
  }
  return col * max(0.2, dot(normal, vec3(0.4, 0.82, 0.4)));
}

layout (local_size_x = 8, local_size_y = 8) in;

void main(void) {
  ivec2 pix = ivec2(gl_GlobalInvocationID.xy) * cbwidth + off;
  ivec2 size = imageSize(framebufferImage);
  if (any(greaterThanEqual(pix, size)))
    return;
  vec2 p = (vec2(pix) + vec2(0.5)) / vec2(size);
  vec3 dir = normalize(mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x));
  vec3 color = trace(eye, dir, 1.0 / dir);
  imageStore(framebufferImage, pix, vec4(color, 1.0));
}
