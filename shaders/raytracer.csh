#version 430 core

layout(binding = 0, rgba8) uniform image2D framebufferImage;

layout(location = 0) uniform vec3 cam[5] = {
  vec3(0.0, 2.0, 5.0), // <- position
  vec3(-1.0, -1.0, -1.0), vec3(-1.0, 1.0, -1.0), // <- left corner directions
  vec3(1.0, -1.0, -1.0), vec3(1.0, 1.0, -1.0) // <- right corner directions
};

struct box {
  vec3 min, max;
};

#define NUM_BOXES 9
const box boxes[NUM_BOXES] = {
  {vec3(-5.0, -0.1, -5.0), vec3(5.0, 0.0, 5.0)},  // <- bottom
  {vec3(-5.1, 0.0, -5.0), vec3(-5.0, 5.0, 5.0)},  // <- left
  {vec3(5.0, 0.0, -5.0), vec3(5.1, 5.0, 5.0)},    // <- right
  {vec3(-5.0, 0.0, -5.1), vec3(5.0, 5.0, -5.0)},  // <- back
  {vec3(-1.0, 1.0, -1.0), vec3(1.0, 1.1, 1.0)},   // <- table top
  {vec3(-1.0, 0.0, -1.0), vec3(-0.8, 1.0, -0.8)}, // <- table foot
  {vec3(-1.0, 0.0,  0.8), vec3(-0.8, 1.0, 1.0)},  // <- table foot
  {vec3(0.8, 0.0, -1.0), vec3(1.0, 1.0, -0.8)},   // <- table foot
  {vec3(0.8, 0.0,  0.8), vec3(1.0, 1.0, 1.0)}     // <- table foot
};

struct hitinfo {
  float near;
  int i;
};

vec2 intersectBox(vec3 origin, vec3 invdir, box b) {
  vec3 tMin = (b.min - origin) * invdir;
  vec3 tMax = (b.max - origin) * invdir;
  vec3 t1 = min(tMin, tMax);
  vec3 t2 = max(tMin, tMax);
  float tNear = max(max(t1.x, t1.y), t1.z);
  float tFar = min(min(t2.x, t2.y), t2.z);
  return vec2(tNear, tFar);
}
bool intersectBoxes(vec3 origin, vec3 invdir, out hitinfo info) {
  float smallest = 1.0/0.0;
  bool found = false;
  for (int i = 0; i < NUM_BOXES; i++) {
    vec2 lambda = intersectBox(origin, invdir, boxes[i]);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.near = lambda.x;
      info.i = i;
      smallest = lambda.x;
      found = true;
    }
  }
  return found;
}

vec3 trace(vec3 origin, vec3 dir) {
  hitinfo hinfo;
  if (!intersectBoxes(origin, 1.0/dir, hinfo))
    return vec3(0.0); // <- nothing hit, return black
  box b = boxes[hinfo.i];
  return vec3(float(hinfo.i+1) / NUM_BOXES);
}
layout(local_size_x = 8, local_size_y = 8) in;
void main(void) {
  ivec2 px = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (any(greaterThanEqual(px, size)))
    return;
  vec2 p = (vec2(px) + vec2(0.5)) / vec2(size);
  vec3 dir = mix(mix(cam[1], cam[2], p.y), mix(cam[3], cam[4], p.y), p.x);
  imageStore(framebufferImage, px, vec4(trace(cam[0], normalize(dir)), 1.0));
}
