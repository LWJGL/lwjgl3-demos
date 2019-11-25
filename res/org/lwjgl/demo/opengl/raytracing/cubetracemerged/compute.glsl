/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 430 core
#if GL_NV_gpu_shader5
#extension GL_NV_gpu_shader5 : enable
#elif GL_AMD_gpu_shader_int16
#extension GL_AMD_gpu_shader_int16 : enable
#endif

#define NO_AXIS uint(-1u)
#define NO_NODE uint(-1u)
#define SPLIT_POS(v) (v & 0x3FFFFFFFu)
#define SPLIT_AXIS(v) (v >> 30u & 3u)
#define MAX_DESCEND 200u
#define MAX_ROPES 100u
#define SKY_COLOR vec3(0.96, 0.98, 1.0)

#define SIDE_Y_NEG 2u
#define SIDE_Z_NEG 4u

layout(binding = 0, rgba8) writeonly uniform image2D framebufferImage;
uniform vec3 cam[5];
uniform ivec2 off = ivec2(0,0), cbwidth = ivec2(1,1);
uniform int scale = 1;
uniform uint startNodeIdx = 0u;

/*
 * 8 ints.
 */
struct node {
  uvec2 rightOrLeaf_SplitAxisSplitPos;
  uint ropes[6];
};
layout(std430, binding = 0) readonly restrict buffer Nodes {
  node[] nodes;
};

/*
 * 2 ints.
 */
struct nodegeom {
  u8vec3 min, max;
};
layout(std430, binding = 1) readonly restrict buffer NodeGeoms {
  nodegeom[] nodegeoms;
};

/*
 * 2 ints.
 */
struct leafnode {
  uvec2 voxels;
};
layout(std430, binding = 2) readonly restrict buffer LeafNodes {
  leafnode[] leafnodes;
};

struct voxel {
  u8vec4 positionAndPalette;
  u8vec4 extent;
};

/*
 * 1 int.
 */
layout(std430, binding = 3) readonly restrict buffer Voxels {
  voxel[] voxels;
};

const bool intersectVoxel(const in vec3 origin, const in vec3 invdir, const in vec3 bmin, const in vec3 bmax,
                          inout float t) {
  const bvec3 lt = lessThan(invdir, vec3(0.0));
  const vec3 m1 = (bmin - origin) * invdir, m2 = (bmax - origin) * invdir;
  const vec3 tmin = mix(m1, m2, lt), tmax = mix(m2, m1, lt);
  const float mint = max(max(tmin.x, tmin.y), tmin.z);
  const float maxt = min(min(tmax.x, tmax.y), tmax.z);
  if (mint < t && mint >= 0.0 && mint < maxt) {
    t = mint;
    return true;
  } else {
    return false;
  }
}

const bool intersectVoxels(const in vec3 origin, const in vec3 invdir, const uint firstVoxel,
                           const uint numVoxels, inout float t, out uint vindex) {
  bool hit = false;
  for (uint i = 0; i < numVoxels; i++) {
    const voxel v = voxels[i + firstVoxel];
    const vec3 vp = vec3(v.positionAndPalette.xyz)*scale;
    const vec3 ve = vec3(v.extent.xyz)*scale;
    if (intersectVoxel(origin, invdir, vp, vp + vec3(scale) + ve, t)) {
      hit = true;
      vindex = i + firstVoxel;
    }
  }
  return hit;
}

const float exitSide(const in vec3 origin, const in vec3 invdir,
                     const in uvec3 boxMin, const in uvec3 boxMax,
                     out uint exitSide) {
  const bvec3 lt = lessThan(invdir, vec3(0.0));
  const vec3 tmax = (mix(vec3(boxMax)+vec3(1.0), vec3(boxMin), lt)*scale - origin) * invdir;
  const ivec3 signs = ivec3(lt);
  vec2 vals;
  vals = mix(vec2(tmax.y, SIDE_Y_NEG + signs.y), vec2(tmax.x, signs.x), tmax.y > tmax.x);
  vals = mix(vec2(tmax.z, SIDE_Z_NEG + signs.z), vals, tmax.z > vals.x);
  exitSide = uint(vals.y);
  return vals.x;
}

const vec2 intersectBox(const in vec3 origin, const in vec3 invdir, const in u8vec3 bmin, const in u8vec3 bmax) {
  const bvec3 lt = lessThan(invdir, vec3(0.0));
  const vec3 m1 = (vec3(bmin)*scale - origin) * invdir, m2 = ((vec3(bmax) + vec3(1.0))*scale - origin) * invdir;
  const vec3 tmin = mix(m1, m2, lt), tmax = mix(m2, m1, lt);
  return vec2(max(max(tmin.x, tmin.y), tmin.z), min(min(tmax.x, tmax.y), tmax.z));
}

struct scenehitinfo {
  float t;
  uint vindex;
  uint descends, ropes;
};
const bool intersectScene(in uint nodeIdx, const in vec3 origin, const in vec3 dir,
                          const in vec3 invdir, out scenehitinfo shinfo) {
  node n = nodes[nodeIdx];
  vec3 o = origin;
  nodegeom ng = nodegeoms[nodeIdx];
  vec2 lambda = intersectBox(o, invdir, ng.min, ng.max);
  if (lambda.y < 0.0 || lambda.x > lambda.y)
    return false;
  lambda.x = max(lambda.x, 0.0);
  shinfo.descends = 0u;
  shinfo.ropes = 0u;
  shinfo.t = 1.0/0.0;
  uvec3 nmin = ng.min, nmax = ng.max;
  while (true) {
    vec3 pEntry = dir * lambda.x + o;
    while (n.rightOrLeaf_SplitAxisSplitPos.y != NO_AXIS) {
      if (float(SPLIT_POS(n.rightOrLeaf_SplitAxisSplitPos.y))*scale <= pEntry[SPLIT_AXIS(n.rightOrLeaf_SplitAxisSplitPos.y)]) {
        nodeIdx = n.rightOrLeaf_SplitAxisSplitPos.x;
        nmin[SPLIT_AXIS(n.rightOrLeaf_SplitAxisSplitPos.y)] = SPLIT_POS(n.rightOrLeaf_SplitAxisSplitPos.y);
      } else {
        nodeIdx = nodeIdx + 1u;
        nmax[SPLIT_AXIS(n.rightOrLeaf_SplitAxisSplitPos.y)] = SPLIT_POS(n.rightOrLeaf_SplitAxisSplitPos.y) - 1u;
      }
      n = nodes[nodeIdx];
      if (shinfo.descends++ > MAX_DESCEND)
        return false;
    }
    if (n.rightOrLeaf_SplitAxisSplitPos.x != NO_NODE) {
      leafnode ln = leafnodes[n.rightOrLeaf_SplitAxisSplitPos.x];
      if (intersectVoxels(o, invdir, ln.voxels.x, ln.voxels.y, shinfo.t, shinfo.vindex))
        return true;
    }
    uint exit;
    lambda.x = exitSide(o, invdir, nmin, nmax, exit);
    nodeIdx = n.ropes[exit];
    if (nodeIdx == NO_NODE || shinfo.ropes++ > MAX_ROPES)
      break;
    n = nodes[nodeIdx];
    ng = nodegeoms[nodeIdx];
    nmin = ng.min;
    nmax = ng.max;
    o = origin;
  }
  return false;
}

layout (local_size_x = 8, local_size_y = 8) in;
void main(void) {
  const ivec2 pix = ivec2(gl_GlobalInvocationID.xy) * cbwidth + off;
  const ivec2 size = imageSize(framebufferImage);
  if (any(greaterThanEqual(pix, size)))
    return;
  const vec2 p = (vec2(pix) + vec2(0.5)) / vec2(size);
  const vec3 dir = normalize(mix(mix(cam[1], cam[2], p.y), mix(cam[3], cam[4], p.y), p.x));
  vec3 acc = vec3(0.0);
  vec3 att = vec3(1.0);
  scenehitinfo shinfo;
  if (intersectScene(startNodeIdx, cam[0], dir, 1.0/dir, shinfo)) {
    acc += 3.2 * vec3(float(shinfo.ropes) * 1.4E-2, float(shinfo.descends) * 4E-3, 0.0);
  } else {
    acc += SKY_COLOR;
  }
  imageStore(framebufferImage, pix, vec4(acc, 1.0));
}
