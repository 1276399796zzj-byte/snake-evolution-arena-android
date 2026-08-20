#version 450

layout(location = 0) in vec2 inUv;
layout(location = 1) in vec4 inColor;
layout(location = 2) flat in float inShape;

layout(location = 0) out vec4 outColor;

void main() {
    float coverage = 1.0;
    if (inShape > 1.5) {
        float radius = length(inUv);
        coverage = smoothstep(1.0, 0.93, radius) * smoothstep(0.76, 0.84, radius);
    } else if (inShape > 0.5) {
        coverage = smoothstep(1.0, 0.88, length(inUv));
    }
    if (coverage <= 0.001) {
        discard;
    }
    outColor = vec4(inColor.rgb, inColor.a * coverage);
}

