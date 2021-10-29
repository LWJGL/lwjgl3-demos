/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core
#extension GL_KHR_shader_subgroup_shuffle : require

layout(location=0) uniform sampler2D baseImage;
layout(binding=0, rgba16f) uniform writeonly restrict image2D mips[3];

/*
 * The assumption here is that each subgroup item maps to its corresponding local workgroup item
 * according to gl_LocalInvocationID.x % gl_SubgroupSize == gl_SubgroupInvocationID.
 * Our workgroups are 256 = 16 * 16 items in size.
 * 
 * We will use z-order / morton-curve to layout the 256 threads in a workgroup
 * across a 16x16 grid. That means, we still use (width/16, height/16, 1) workgroups
 * to process the baseImage. We just redistribute the work items on a different
 * 2D pattern.
 */
layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

/*
 * Morton code unpack to generate (x, y) pair from a z-order curve coordinate within [0..255].
 */
int unpack(int x) {
  x &= 0x55;
  x = (x ^ (x >> 1)) & 0x33;
  x = (x ^ (x >> 2)) & 0x0f;
  return x;
}

void main(void) {
  ivec2 ts = textureSize(baseImage, 0);

  // the actual size of our work items is only half the baseImage size, because for the first mip level
  // each work item already uses linear filtering with a sampler to gather a 2x2 texel average
  ivec2 s  = ts / ivec2(2);

  // Compute the (x, y) coordinates of the current work item within its workgroup using z-order curve
  ivec2 l = ivec2(unpack(int(gl_LocalInvocationID.x)),
                  unpack(int(gl_LocalInvocationID.x >> 1u)));

  // Compute the global (x, y) coordinate of this work item
  ivec2 i = ivec2(gl_WorkGroupID.xy) * ivec2(16) + l;

  // compute mip 1 using linear filtering
  if (i.x >= s.x || i.y >= s.y)
    return;
  // Compute a texture coordinate right at the corner between four texels
  vec2 tc = (vec2(i * 2) + vec2(1.0)) / vec2(ts);
  vec4 t = textureLod(baseImage, tc, 0.0);
  imageStore(mips[0], i, t);

  // compute mip 2 using subgroup quad sharing
  /*
   * The trick here is to assume a 1:1 correspondence between subgroup invocation ids
   * and workgroup invocation ids (modulus the subgroup size).
   * This way, together with our assumed Z-order swizzled layout, we know that
   * for the subgroup [0, 1, 2, 3] forming a single 2x2 quad, e.g. the horizontal swap
   * will come out correctly as [1, 0, 3, 2], etc.
   */
  vec4 h = subgroupShuffleXor(t, 1);
  vec4 v = subgroupShuffleXor(t, 2);
  vec4 d = subgroupShuffleXor(t, 3);
  t = (t + h + v + d) * vec4(0.25);
  if ((gl_SubgroupInvocationID & 3) == 0)
    imageStore(mips[1], i/ivec2(2), t);

  // compute mip 3 using subgroup xor shuffles
  /*
   * The trick here is to exchange information between subgroup items with a stride
   * of 4 items. In order to do this, we have subgroupShuffleXor().
   */
  h = subgroupShuffleXor(t, 4);
  v = subgroupShuffleXor(t, 8);
  d = subgroupShuffleXor(t, 12);
  t = (t + h + v + d) * vec4(0.25);
  if ((gl_SubgroupInvocationID & 15) == 0)
    imageStore(mips[2], i/ivec2(4), t);
}
