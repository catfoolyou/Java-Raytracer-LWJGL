package net.raytracer;

import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import java.nio.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.GLUtil.setupDebugMessageCallback;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.MemoryStack.*;

public class RenderClass {
    private long window;
    private int width = 800;
    private int height = 800;

    private static final String QUAD_PROGRAM_VS_SOURCE = """
        #version 430 core
        out vec2 texcoord;
        void main(void) {
          vec2 vertex = vec2((gl_VertexID & 1) << 2, (gl_VertexID & 2) << 1) - vec2(1.0);
          gl_Position = vec4(vertex, 0.0, 1.0);
          texcoord = vertex * 0.5 + vec2(0.5, 0.5);
        }
        """;
    private static final String QUAD_PROGRAM_FS_SOURCE = """
        #version 430 core
        uniform sampler2D tex;
        in vec2 texcoord;
        layout(location = 0) out vec4 color;
        void main(void) {
          color = texture(tex, texcoord);
        }
        """;
    private static final String COMPUTE_SHADER_SOURCE = """
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
        """;

    public void run() {
        System.out.println("LWGJL version: " + Version.getVersion() + " (native)");

        init();
        loop();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);

        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if ( !glfwInit() )
            throw new IllegalStateException("Unable to initialize GLFW");

        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(this.width, this.height, "LWJGL raytracer test", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if ( key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE )
                glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop
        });

        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);

            glfwGetWindowSize(window, pWidth, pHeight);

            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            glfwSetWindowPos(
                    window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
            );
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);

        glfwShowWindow(window);
    }

    private void loop() {

        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        setupDebugMessageCallback();

        glBindVertexArray(glGenVertexArrays());

        int framebuffer = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, framebuffer);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, this.width, this.height);
        glBindImageTexture(0, framebuffer, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);

        int quadProgram = glCreateProgram();
        int quadProgramVs = glCreateShader(GL_VERTEX_SHADER);
        int quadProgramFs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(quadProgramVs, QUAD_PROGRAM_VS_SOURCE);
        glShaderSource(quadProgramFs, QUAD_PROGRAM_FS_SOURCE);
        glCompileShader(quadProgramVs);
        glCompileShader(quadProgramFs);
        glAttachShader(quadProgram, quadProgramVs);
        glAttachShader(quadProgram, quadProgramFs);
        glLinkProgram(quadProgram);

        int computeProgram = glCreateProgram();
        int computeProgramShader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(computeProgramShader, COMPUTE_SHADER_SOURCE);
        glCompileShader(computeProgramShader);
        glAttachShader(computeProgram, computeProgramShader);
        glLinkProgram(computeProgram);
        int numGroupsX = (int) Math.ceil((double)this.width / 8);
        int numGroupsY = (int) Math.ceil((double)this.height / 8);

        glfwShowWindow(window);
        while (!glfwWindowShouldClose(window) ) {
            glUseProgram(computeProgram);
            glDispatchCompute(numGroupsX, numGroupsY, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            glUseProgram(quadProgram);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            //glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glfwSwapBuffers(window);
            //glfwPollEvents();
        }
    }

    public static void main(String[] args){
        new RenderClass().run();
    }
}
