#version 450

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;
layout(location = 3) in float inShape;

layout(location = 0) out vec2 outUv;
layout(location = 1) out vec4 outColor;
layout(location = 2) flat out float outShape;

void main() {
    // The shared geometry uses OpenGL's +Y-up NDC. Vulkan's positive viewport
    // maps -Y to the top, so flip once here instead of rebuilding every batch.
    gl_Position = vec4(inPosition.x, -inPosition.y, 0.0, 1.0);
    outUv = inUv;
    outColor = inColor;
    outShape = inShape;
}
