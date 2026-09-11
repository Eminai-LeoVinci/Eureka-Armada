#version 330

// Full-screen pass for the submarine pocket restore (see ArmadaPocketOccluder). The quad is already in clip
// space; nothing to transform.

in vec3 Position;

void main() {
    gl_Position = vec4(Position, 1.0);
}
