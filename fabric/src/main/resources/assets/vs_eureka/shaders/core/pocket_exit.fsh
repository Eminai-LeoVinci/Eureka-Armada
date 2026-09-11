#version 330

// The pocket's EXIT along each pixel's ray (see ArmadaPocketOccluder, shaderpack path). The boundary quads are
// wound to face INTO the pocket, and the pipeline culls nothing, so both sides of every face arrive here: only
// the ones facing the camera are the faces a ray leaves the pocket through, and LESS then keeps the nearest.

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (!gl_FrontFacing) {
        discard;
    }
    fragColor = vec4(0.0);
}
