#version 330 core
#if GL_NV_gpu_shader5
#extension GL_NV_gpu_shader5 : enable
#elif GL_AMD_gpu_shader_int16
#extension GL_AMD_gpu_shader_int16 : enable
#endif

#define NO_AXIS uint8_t(3u)
#define NO_NODE uint16_t(-1)
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

in vec2 texcoord;
out vec4 color_out;

uniform vec3 cam[5];

uniform usamplerBuffer nodes;
uniform usamplerBuffer nodegeoms;
uniform usamplerBuffer leafnodes;
uniform usamplerBuffer voxels;
uniform samplerBuffer materials;

struct hitinfo {
  vec3 normal;
  float t;
  uint i;
  uint descends;
  uint ropes;
  uint16_t nodeIdx;
};
bool intersectVoxel(vec3 origin, vec3 invdir, vec3 bmin, vec3 bmax, out float t, out vec3 normal) {
  vec3 tMin = (bmin - origin) * invdir, tMax = (bmax - origin) * invdir;
  vec3 t1 = min(tMin, tMax), t2 = max(tMin, tMax);
  t = max(max(t1.x, t1.y), t1.z);
  normal = vec3(equal(t1, vec3(t))) * sign(-invdir);
  return t >= 0.0 && t <= min(min(t2.x, t2.y), t2.z);
}
float intersectBox(vec3 origin, vec3 invdir, uvec3 bmin, uvec3 bmax) {
  bvec3 lt = lessThan(invdir, vec3(0.0));
  vec3 m1 = (vec3(bmin) - origin) * invdir, m2 = ((vec3(bmax) + vec3(1.0)) - origin) * invdir;
  vec3 tmin = mix(m1, m2, lt);
  return max(max(tmin.x, tmin.y), tmin.z);
}
bool intersectVoxels(vec3 origin, vec3 invdir, uint firstVoxel, uint numVoxels, inout hitinfo hinfo) {
  bool hit = false;
  for (uint i = 0u; i < numVoxels; i++) {
    vec3 vx = vec3(texelFetch(voxels, int(i + firstVoxel)));
    float tn;
    vec3 normal;
    if (intersectVoxel(origin, invdir, vx, vx + vec3(1.0), tn, normal) && tn <= hinfo.t) {
      hinfo.t = tn;
      hinfo.i = i + firstVoxel;
      hinfo.normal = normal;
      hit = true;
    }
  }
  return hit;
}
float exitSide(vec3 origin, vec3 invdir, uvec3 boxMin, uvec3 boxMax, out int exitSide) {
  bvec3 lt = lessThanEqual(invdir, vec3(0.0));
  vec3 tmax = (mix(vec3(boxMax) + vec3(1.0), vec3(boxMin), lt) - origin) * invdir;
  ivec3 signs = ivec3(lt);
  vec2 vals;
  vals = tmax.y <= tmax.x ? vec2(tmax.y, SIDE_Y_NEG + signs.y) : vec2(tmax.x, signs.x);
  vals = tmax.z <= vals.x ? vec2(tmax.z, SIDE_Z_NEG + signs.z) : vals;
  exitSide = int(vals.y);
  return vals.x;
}

uint8_t axis(uvec4 n) {
  return uint8_t(n.x >> AXIS_SHIFT & AXIS_MASK);
}
uint8_t splitPos(uvec4 n) {
  return uint8_t(n.x >> SHORT_BITS & SPLITPOS_MASK);
}
uint16_t nodeIndex(uvec4 n) {
  return uint16_t(n.x & SHORT_MASK);
}
uvec3 unpack8(uint v) {
  return uvec3(v & BYTE_MASK, v >> 8u & BYTE_MASK, v >> 16u & BYTE_MASK);
}
uint16_t rope(uvec4 n, int r) {
  return uint16_t(n[1 + (r >> 1)] >> (r & 1) * SHORT_BITS & SHORT_MASK);
}

bool intersectScene(uint16_t nodeIdx, vec3 origin, vec3 dir, vec3 invdir, out hitinfo shinfo) {
  uvec4 n = texelFetch(nodes, int(nodeIdx));
  uvec4 ng = texelFetch(nodegeoms, int(nodeIdx));
  float tEntry = max(0.0, intersectBox(origin, invdir, unpack8(ng.x), unpack8(ng.y)));
  shinfo.descends = 0u;
  shinfo.ropes = 0u;
  shinfo.t = 1.0/0.0;
  uvec3 nmin = unpack8(ng.x), nmax = unpack8(ng.y);
  while (true) {
    vec3 pEntry = dir * tEntry + origin;
    while (axis(n) != NO_AXIS) {
      uint8_t sp = splitPos(n);
      if (sp <= pEntry[axis(n)]) {
        nodeIdx = nodeIndex(n);
        nmin[axis(n)] = sp;
      } else {
        nodeIdx = uint16_t(nodeIdx + 1u);
        nmax[axis(n)] = uint8_t(sp - 1u);
      }
      n = texelFetch(nodes, int(nodeIdx));
      if (shinfo.descends++ > MAX_DESCEND)
        return false;
    }
    uint16_t leafIndex = nodeIndex(n);
    if (leafIndex != NO_NODE) {
      uvec4 ln = texelFetch(leafnodes, int(leafIndex));
      if (intersectVoxels(origin, invdir, ln.x, ln.y, shinfo)) {
        shinfo.nodeIdx = nodeIdx;
        return true;
      }
    }
    int exit;
    tEntry = exitSide(origin, invdir, nmin, nmax, exit);
    nodeIdx = rope(n, exit);
    if (nodeIdx == NO_NODE)
      return false;
    n = texelFetch(nodes, int(nodeIdx));
    ng = texelFetch(nodegeoms, int(nodeIdx));
    nmin = unpack8(ng.x);
    nmax = unpack8(ng.y);
    if (shinfo.ropes++ > MAX_ROPES)
      return false;
  }
  return false;
}

vec3 trace(vec3 origin, vec3 dir) {
  hitinfo hinfo, hinfo2;
  vec3 col = vec3(0.3, 0.42, 0.62);
  if (intersectScene(uint16_t(0u), origin, dir, 1.0/dir, hinfo)) {
    uvec4 v = texelFetch(voxels, int(hinfo.i));
    col = texelFetch(materials, int(v.w)).rgb;
    // Cast shadow ray
    origin += dir * hinfo.t + hinfo.normal * 1E-3;
    dir = vec3(2.0, 1.0, 1.0);
    if (intersectScene(hinfo.nodeIdx, origin, dir, 1.0/dir, hinfo2))
      col *= 0.3;
  }
  //col *= vec3(hinfo.ropes, hinfo.descends, 1.0) * vec3(0.3, 0.05, 1.0);
  return col;
}

void main(void) {
  vec3 dir = mix(mix(cam[1], cam[2], texcoord.y), mix(cam[3], cam[4], texcoord.y), texcoord.x);
  color_out = vec4(trace(cam[0], dir), 1.0);
}
