/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform usamplerBuffer nodes;
uniform usamplerBuffer leafnodes;
uniform usamplerBuffer voxels;

#define MAX_DESCEND 200u
#define MAX_ROPES 100u
#define SIDE_Y_NEG 2u
#define SIDE_Z_NEG 4u
#define AXIS_SHIFT 30u
#define SHORT_BITS 16u
#define AXIS_MASK 3u
#define SPLITPOS_MASK 0x3FFFu
#define SHORT_MASK 0xFFFFu
#define BYTE_MASK 0xFFu
#define NO_AXIS AXIS_MASK
#define NO_NODE SHORT_MASK
#define BYTE_SHIFT 8u
#define SHORT_SHIFT 16u

uvec3 unpack8(uint v) {
  return uvec3(v & BYTE_MASK, v >> BYTE_SHIFT & BYTE_MASK, v >> SHORT_SHIFT & BYTE_MASK);
}

uint axis(uvec3 n) {return n.z >> AXIS_SHIFT & AXIS_MASK;}
uint splitPos(uvec3 n) {return n.z >> SHORT_BITS & SPLITPOS_MASK;}
uint nodeIndex(uint n) {return n & SHORT_MASK;}
uint rope(uvec4 n, uint r) {return n[1u + (r >> 1u)] >> (r & 1u) * SHORT_BITS & SHORT_MASK;}
uint firstVoxel(uvec4 ln) {return ln.x & SHORT_MASK;}
uint numVoxels(uvec4 ln) {return ln.x >> SHORT_BITS & SHORT_MASK;}
uvec3 boxMin(uvec3 ng) {return unpack8(ng.x);}
uvec3 boxMax(uvec3 ng) {return unpack8(ng.y);}
uint leftChild(uint nodeIdx) {return nodeIdx + 1u;}
uint rightChild(uvec3 n) {return nodeIndex(n.z);}

bool intersectVoxel(vec3 origin, vec3 invdir, vec3 bmin, vec3 bmax, out float t) {
  float t1 = (bmin.x - origin.x)*invdir.x, t2 = (bmax.x - origin.x)*invdir.x;
  float t3 = (bmin.y - origin.y)*invdir.y, t4 = (bmax.y - origin.y)*invdir.y;
  float t5 = (bmin.z - origin.z)*invdir.z, t6 = (bmax.z - origin.z)*invdir.z;
  float t12m = min(t1, t2), t34m = min(t3, t4), t56m = min(t5, t6);
  t = max(max(t12m, t34m), t56m);
  float tm = min(min(max(t1, t2), max(t3, t4)), max(t5, t6));
  return tm > 0.0 && t <= tm;
}
float intersectBox(vec3 origin, vec3 invdir, uvec3 bmin, uvec3 bmax) {
  vec3 lt = vec3(lessThan(invdir, vec3(0.0)));
  vec3 m1 = (vec3(bmin) - origin) * invdir, m2 = ((vec3(bmax) + vec3(1.0)) - origin) * invdir;
  vec3 tmin = mix(m1, m2, lt);
  return max(max(tmin.x, tmin.y), tmin.z);
}

struct hitinfo {
  float t;
  uint i;
  uint descends;
  uint ropes;
  uint nodeIdx;
};
bool intersectVoxels(vec3 origin, vec3 invdir, uint firstVoxel, uint numVoxels, inout hitinfo hinfo) {
  bool hit = false;
  for (uint i = 0u; i < numVoxels; i++) {
    uvec2 vpe = texelFetch(voxels, int(i + firstVoxel)).xy;
    vec3 vp = vec3(unpack8(vpe.x)), ve = vec3(unpack8(vpe.y));
    float tn;
    if (intersectVoxel(origin, invdir, vp, vp + ve + vec3(1.0), tn) && tn <= hinfo.t) {
      hinfo.t = tn;
      hinfo.i = i + firstVoxel;
      hit = true;
    }
  }
  return hit;
}
float exitSide(vec3 origin, vec3 invdir, uvec3 boxMin, uvec3 boxMax, out uint exitSide) {
  vec3 lt = vec3(lessThanEqual(invdir, vec3(0.0)));
  vec3 tmax = (mix(vec3(boxMax) + vec3(1.0), vec3(boxMin), lt) - origin) * invdir;
  uvec3 signs = uvec3(lt);
  vec2 vals = tmax.y <= tmax.x ? vec2(tmax.y, SIDE_Y_NEG + signs.y) : vec2(tmax.x, signs.x);
  vals = tmax.z <= vals.x ? vec2(tmax.z, SIDE_Z_NEG + signs.z) : vals;
  exitSide = uint(vals.y);
  return vals.x;
}
bool intersectScene(uint nodeIdx, vec3 origin, vec3 dir, vec3 invdir, out hitinfo shinfo) {
  uvec3 ngn = texelFetch(nodes, int(nodeIdx)).xyz;
  uvec3 nmin = boxMin(ngn), nmax = boxMax(ngn);
  float tEntry = max(0.0, intersectBox(origin, invdir, nmin, nmax));
  shinfo.descends = 0u;
  shinfo.ropes = 0u;
  shinfo.t = 1E30;
  while (true) {
    vec3 pEntry = dir * tEntry + origin;
    while (axis(ngn) != NO_AXIS) {
      uint sp = splitPos(ngn);
      if (sp <= pEntry[axis(ngn)]) {
        nodeIdx = rightChild(ngn);
        nmin[axis(ngn)] = sp;
      } else {
        nodeIdx = leftChild(nodeIdx);
        nmax[axis(ngn)] = sp - 1u;
      }
      ngn = texelFetch(nodes, int(nodeIdx)).xyz;
      if (shinfo.descends++ > MAX_DESCEND)
        return false;
    }
    uint leafIndex = nodeIndex(ngn.z);
    uvec4 ln = texelFetch(leafnodes, int(leafIndex));
    if (intersectVoxels(origin, invdir, firstVoxel(ln), numVoxels(ln), shinfo)) {
      shinfo.nodeIdx = nodeIdx;
      return true;
    }
    uint exit;
    tEntry = exitSide(origin, invdir, nmin, nmax, exit);
    nodeIdx = rope(ln, exit);
    if (nodeIdx == NO_NODE)
      return false;
    ngn = texelFetch(nodes, int(nodeIdx)).xyz;
    nmin = boxMin(ngn);
    nmax = boxMax(ngn);
    if (shinfo.ropes++ > MAX_ROPES)
      return false;
  }
  return false;
}
