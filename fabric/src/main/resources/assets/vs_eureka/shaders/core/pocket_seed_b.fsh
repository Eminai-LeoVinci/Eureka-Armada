#version 330

// Shaderpack path, step two (see ArmadaPocketOccluder): put the depth back for the world's own translucent draw,
// except that every pixel a visible pocket covers is pushed to the pocket's ENTRY, so that draw can only land
// water in front of the pocket -- never inside it. With the camera inside a pocket the entry is 0 and nothing
// lands at all. A pocket hidden behind an opaque wall (its entry beyond the scene) is not a pocket here.

uniform sampler2D SavedDepth;
uniform sampler2D FrontDepth;
uniform sampler2D ExitDepth;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float saved = texelFetch(SavedDepth, px, 0).r;
    float front = texelFetch(FrontDepth, px, 0).r;
    float exit = texelFetch(ExitDepth, px, 0).r;
    bool pocket = exit < 1.0 && front < saved;
    float depth = pocket ? front : saved;
    if (depth >= 1.0) {
        discard; // the clear already holds 1.0
    }
    gl_FragDepth = depth;
    fragColor = vec4(0.0);
}
