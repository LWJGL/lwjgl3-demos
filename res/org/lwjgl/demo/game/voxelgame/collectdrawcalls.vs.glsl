/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
#extension GL_ARB_shader_storage_buffer_object : enable
#extension GL_ARB_shader_atomic_counters : enable
#pragma {{DEFINES}}

/**
 * Atomic counter used to compute indices for appending to the 'allVisibleOutputBuffer'.
 */
layout(binding = 0) uniform atomic_uint allVisibleCounter;

#if TEMPORAL_COHERENCE
/**
 * Atomic counter used to compute indices for appending to the 'newlyDisoccludedOutputBuffer'.
 */
layout(binding = 1) uniform atomic_uint newlyDisoccludedCounter;
#endif

/**
 * Structure of information stored in the 'inputBuffer'.
 */
struct ChunkInfo {
  uint faceOffset;
  uint faceCount;
};

/**
 * https://www.khronos.org/registry/OpenGL-Refpages/gl4/html/glDrawElementsIndirect.xhtml#description
 */
struct DrawElementsIndirectCommand {
  uint count;
  uint instanceCount;
  uint firstIndex;
  uint baseVertex;
  uint baseInstance;
};

/**
 * Contains the visibility flag for each visible chunk whose bounding box generated fragments.
 */
layout(std430, binding = 0) readonly restrict buffer visiblesBuffer {
  uint visible[];
};

/**
 * Contains each chunk's face offset and count.
 */
layout(std430, binding = 1) readonly restrict buffer inputBuffer {
  ChunkInfo chunks[];
};

/**
 * We will append MDI structs for visible chunks to this buffer. 
 */
layout(std430, binding = 2) writeonly restrict buffer allVisibleOutputBuffer {
  DrawElementsIndirectCommand allVisibleCommand[];
};

#if TEMPORAL_COHERENCE
/**
 * Contains the visibility flag for each chunk that was visible in the previous frame.
 */
layout(std430, binding = 3) readonly restrict buffer prevVisiblesBuffer {
  uint prevVisible[];
};

/**
 * We will append MDI structs for newly disoccluded chunks to this buffer. 
 */
layout(std430, binding = 4) writeonly restrict buffer newlyDisoccludedOutputBuffer {
  DrawElementsIndirectCommand newlyDisoccludedCommand[];
};
#endif

// X = chunk X coordinate
// Y = chunk Z coordinate
// Z = chunk minY | maxY << 16
// W = index | (1 if chunk MUST be drawn, 0 otherwise) << 31
layout(location=0) in ivec4 chunk;

void main(void) {
  /* Extract chunk index */
  int idx = chunk.w & 0xFFFFFF;

  /* Skip this chunk if it isn't visible in this frame */
  if (!bool(visible[idx]))
    return;

  /* Build the MDI struct for this chunk */
  ChunkInfo c = chunks[gl_VertexID];
  DrawElementsIndirectCommand cmd;
  cmd.count = c.faceCount * INDICES_PER_FACE;
  cmd.instanceCount = 1u;
  cmd.firstIndex = c.faceOffset * INDICES_PER_FACE;
  cmd.baseVertex = c.faceOffset * VERTICES_PER_FACE;
  cmd.baseInstance = uint(idx);

  /* Append that to the list of MDI structs for _all_ visible/non-occluded chunks */
  allVisibleCommand[atomicCounterIncrement(allVisibleCounter)] = cmd;

#if TEMPORAL_COHERENCE
  /* Skip appending MDI struct to newly disoccluded chunks if chunk was visible in previous frame */
  if (bool(prevVisible[idx]))
    return;

  /* Append chunk's MDI struct to list of newly disoccluded chunks */
  newlyDisoccludedCommand[atomicCounterIncrement(newlyDisoccludedCounter)] = cmd;
#endif
}
