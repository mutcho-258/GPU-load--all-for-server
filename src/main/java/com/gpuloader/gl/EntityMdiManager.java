package com.gpuloader.gl;

import com.gpuloader.Config;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenGL 4.3+ Multi-Draw Indirect (MDI) によるエンティティ描画加速 & GPU駆動アニメーションマネージャー。
 * 動的なボーン移動（Blazeのロッド回転など）と、複数レイヤーを持つ特殊モブのバニラフォールバックをサポートします。
 */
public class EntityMdiManager {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static boolean initialized = false;
    public static volatile Matrix4f cachedProjMatrix = null;
    private static int mdiProgram = -1;
    private static int vao = -1;
    private static int sharedVbo = -1;
    private static int sharedEbo = -1;
    private static int indirectBuffer = -1;
    private static int ssbo = -1;

    // Uniform locations
    private static int locProjMatrix = -1;
    private static int locParentIds = -1;
    private static int locLocalTranslations = -1;

    // ジオメトリ抽出用のマスタデータ
    private static final List<SkinnedVertex> ALL_VERTICES = new ArrayList<>();
    private static final List<Integer> ALL_INDICES = new ArrayList<>();
    private static final Map<String, EntityMeshInfo> MESHES = new HashMap<>();

    public static class BatchKey {
        public final net.minecraft.resources.ResourceLocation texture;
        public final String registryName;

        public BatchKey(net.minecraft.resources.ResourceLocation texture, String registryName) {
            this.texture = texture;
            this.registryName = registryName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BatchKey batchKey = (BatchKey) o;
            return texture.equals(batchKey.texture) && registryName.equals(batchKey.registryName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(texture, registryName);
        }
    }

    // テクスチャとモブ種別ごとのレンダリングバッチ
    private static final Map<BatchKey, List<QueuedInstance>> BATCHES = new HashMap<>();

    public static class SkinnedVertex {
        public final float x, y, z;
        public final float u, v;
        public final float nx, ny, nz;
        public final int boneId;

        public SkinnedVertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int boneId) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.boneId = boneId;
        }
    }

    public static class EntityMeshInfo {
        public final int firstIndex;
        public final int indexCount;
        public final int vertexOffset;
        public final List<net.minecraft.client.model.geom.ModelPart> allParts;
        public final Map<net.minecraft.client.model.geom.ModelPart, Integer> partToBoneId;
        public final List<net.minecraft.client.model.geom.ModelPart> roots;
        public final int[] parentIds;
        public final float[] localTranslations;
        public final float[] staticX;
        public final float[] staticY;
        public final float[] staticZ;

        public EntityMeshInfo(int firstIndex, int indexCount, int vertexOffset, 
                              List<net.minecraft.client.model.geom.ModelPart> allParts, 
                              Map<net.minecraft.client.model.geom.ModelPart, Integer> partToBoneId, 
                              List<net.minecraft.client.model.geom.ModelPart> roots,
                              int[] parentIds, float[] localTranslations,
                              float[] staticX, float[] staticY, float[] staticZ) {
            this.firstIndex = firstIndex;
            this.indexCount = indexCount;
            this.vertexOffset = vertexOffset;
            this.allParts = allParts;
            this.partToBoneId = partToBoneId;
            this.roots = roots;
            this.parentIds = parentIds;
            this.localTranslations = localTranslations;
            this.staticX = staticX;
            this.staticY = staticY;
            this.staticZ = staticZ;
        }
    }

    private static class QueuedInstance {
        final Matrix4f modelView;
        final float[] boneRotations;    // 16ボーン分 (xRot, yRot, zRot, scale)
        final float[] boneTranslations; // 16ボーン分 (dx, dy, dz, 0.0)
        final float r, g, b, a;
        final float overlayR, overlayG, overlayB, overlayA;
        final float blockLight, skyLight;

        QueuedInstance(Matrix4f modelView, float[] boneRotations, float[] boneTranslations,
                       float r, float g, float b, float a,
                       float overlayR, float overlayG, float overlayB, float overlayA,
                       float blockLight, float skyLight) {
            this.modelView = new Matrix4f(modelView);
            this.boneRotations = boneRotations;
            this.boneTranslations = boneTranslations;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            this.overlayR = overlayR;
            this.overlayG = overlayG;
            this.overlayB = overlayB;
            this.overlayA = overlayA;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * BufferSource.endBatch() がCPUバッチ（防具・衣装・半透明ゲルなど）をフラッシュする直前に呼ばれ、
     * MDIベースモデルを先行描画してデプスバッファを構築します。
     * これにより、スライムの半透明ゲルがベース核を隠してしまう深度の前後関係バグを解消します。
     */
    public static void flushDirect() {
        if (!initialized || BATCHES.isEmpty() || cachedProjMatrix == null) return;
        PoseStack identity = new PoseStack();
        flush(identity, cachedProjMatrix);
    }

    public static void init() {
        if (initialized) return;

        if (!GLShaderManager.isSupported()) {
            LOGGER.warn("[GPU MDI] OpenGL 4.3 not supported. MDI disabled.");
            return;
        }

        try {
            initShaders();
            initialized = true;
            LOGGER.info("[GPU MDI] EntityMdiManager dynamically initialized.");
        } catch (Exception e) {
            LOGGER.error("[GPU MDI] Failed to initialize MDI shaders", e);
            cleanup();
        }
    }

    private static void initShaders() {
        String vertSource = 
            "#version 430 core\n" +
            "layout(location = 0) in vec3 inPosition;\n" +
            "layout(location = 1) in vec2 inTexCoord;\n" +
            "layout(location = 2) in vec3 inNormal;\n" +
            "layout(location = 3) in float inBoneId;\n" +
            "\n" +
            "layout(location = 0) out vec2 fragTexCoord;\n" +
            "layout(location = 1) out vec3 fragNormal;\n" +
            "layout(location = 2) out vec4 fragColor;\n" +
            "layout(location = 3) out vec4 fragOverlay;\n" +
            "layout(location = 4) out vec2 fragLight;\n" +
            "\n" +
            "struct BoneData {\n" +
            "    vec4 rotation;    // xyz: xRot, yRot, zRot, w: scale\n" +
            "    vec4 translation; // xyz: dx, dy, dz, w: 0.0\n" +
            "};\n" +
            "\n" +
            "struct EntityInstance {\n" +
            "    mat4 modelViewMatrix;\n" +
            "    BoneData bones[16];\n" +
            "    vec4 color;\n" +
            "    vec4 overlayColor;\n" +
            "    vec4 lightCoords;\n" +
            "};\n" +
            "\n" +
            "layout(std430, binding = 0) readonly buffer InstanceBuffer {\n" +
            "    EntityInstance instances[];\n" +
            "};\n" +
            "\n" +
            "uniform mat4 projMatrix;\n" +
            "uniform int parentIds[16];\n" +
            "uniform vec3 localTranslations[16];\n" +
            "\n" +
            "mat4 getLocalMatrix(int id, EntityInstance inst) {\n" +
            "    vec4 rot = inst.bones[id].rotation;\n" +
            "    vec4 trans = inst.bones[id].translation;\n" +
            "    \n" +
            "    float rx = rot.x;\n" +
            "    float ry = rot.y;\n" +
            "    float rz = rot.z;\n" +
            "    float scale = rot.w;\n" +
            "    \n" +
            "    // 静的配置＋動的オフセットを加算\n" +
            "    vec3 t = localTranslations[id] + trans.xyz;\n" +
            "    \n" +
            "    mat4 m = mat4(1.0);\n" +
            "    m[3][0] = t.x;\n" +
            "    m[3][1] = t.y;\n" +
            "    m[3][2] = t.z;\n" +
            "    \n" +
            "    if (rz != 0.0) {\n" +
            "        float cz = cos(rz);\n" +
            "        float sz = sin(rz);\n" +
            "        mat4 r = mat4(1.0);\n" +
            "        r[0][0] = cz; r[0][1] = sz;\n" +
            "        r[1][0] = -sz; r[1][1] = cz;\n" +
            "        m = m * r;\n" +
            "    }\n" +
            "    if (ry != 0.0) {\n" +
            "        float cy = cos(ry);\n" +
            "        float sy = sin(ry);\n" +
            "        mat4 r = mat4(1.0);\n" +
            "        r[0][0] = cy; r[0][2] = -sy;\n" +
            "        r[2][0] = sy; r[2][2] = cy;\n" +
            "        m = m * r;\n" +
            "    }\n" +
            "    if (rx != 0.0) {\n" +
            "        float cx = cos(rx);\n" +
            "        float sx = sin(rx);\n" +
            "        mat4 r = mat4(1.0);\n" +
            "        r[1][1] = cx; r[1][2] = sx;\n" +
            "        r[2][1] = -sx; r[2][2] = cx;\n" +
            "        m = m * r;\n" +
            "    }\n" +
            "    if (scale != 1.0) {\n" +
            "        mat4 s = mat4(1.0);\n" +
            "        s[0][0] = scale;\n" +
            "        s[1][1] = scale;\n" +
            "        s[2][2] = scale;\n" +
            "        m = m * s;\n" +
            "    }\n" +
            "    return m;\n" +
            "}\n" +
            "\n" +
            "mat4 getBoneMatrix(int id, EntityInstance inst) {\n" +
            "    mat4 m = mat4(1.0);\n" +
            "    int current = id;\n" +
            "    for (int i = 0; i < 6; i++) {\n" +
            "        if (current < 0 || current >= 16) break;\n" +
            "        m = getLocalMatrix(current, inst) * m;\n" +
            "        current = parentIds[current];\n" +
            "    }\n" +
            "    return m;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    EntityInstance inst = instances[gl_InstanceID];\n" +
            "    int boneId = int(inBoneId);\n" +
            "    mat4 boneMat = getBoneMatrix(boneId, inst);\n" +
            "    \n" +
            "    vec4 localPos = boneMat * vec4(inPosition, 1.0);\n" +
            "    vec4 viewPos = inst.modelViewMatrix * localPos;\n" +
            "    gl_Position = projMatrix * viewPos;\n" +
            "    \n" +
            "    fragTexCoord = inTexCoord;\n" +
            "    \n" +
            "    mat3 normalMat = mat3(inst.modelViewMatrix) * mat3(boneMat);\n" +
            "    fragNormal = normalMat * inNormal;\n" +
            "    \n" +
            "    fragColor = inst.color;\n" +
            "    fragOverlay = inst.overlayColor;\n" +
            "    fragLight = inst.lightCoords.xy;\n" +
            "}\n";

        String fragSource =
            "#version 430 core\n" +
            "layout(location = 0) in vec2 fragTexCoord;\n" +
            "layout(location = 1) in vec3 fragNormal;\n" +
            "layout(location = 2) in vec4 fragColor;\n" +
            "layout(location = 3) in vec4 fragOverlay;\n" +
            "layout(location = 4) in vec2 fragLight;\n" +
            "\n" +
            "layout(location = 0) out vec4 outColor;\n" +
            "\n" +
            "uniform sampler2D textureSampler;\n" +
            "\n" +
            "void main() {\n" +
            "    vec3 normal = normalize(fragNormal);\n" +
            "    vec3 lightDir = normalize(vec3(0.3, 1.0, 0.5));\n" +
            "    float diff = max(dot(normal, lightDir), 0.0) * 0.4;\n" +
            "    \n" +
            "    float blockFactor = fragLight.x / 15.0;\n" +
            "    float skyFactor = fragLight.y / 15.0;\n" +
            "    float ambient = max(0.15, max(blockFactor * 0.9, skyFactor * 0.6));\n" +
            "    \n" +
            "    vec4 texColor = texture(textureSampler, fragTexCoord);\n" +
            "    vec4 finalColor = texColor * fragColor * (ambient + diff);\n" +
            "    \n" +
            "    if (fragOverlay.a > 0.0) {\n" +
            "        finalColor.rgb = mix(finalColor.rgb, fragOverlay.rgb, fragOverlay.a);\n" +
            "    }\n" +
            "    \n" +
            "    outColor = vec4(finalColor.rgb, texColor.a * fragColor.a);\n" +
            "}\n";

        int vertShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertShader, vertSource);
        GL20.glCompileShader(vertShader);
        if (GL20.glGetShaderi(vertShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Vertex shader compilation failed: " + GL20.glGetShaderInfoLog(vertShader));
        }

        int fragShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fragShader, fragSource);
        GL20.glCompileShader(fragShader);
        if (GL20.glGetShaderi(fragShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Fragment shader compilation failed: " + GL20.glGetShaderInfoLog(fragShader));
        }

        mdiProgram = GL20.glCreateProgram();
        GL20.glAttachShader(mdiProgram, vertShader);
        GL20.glAttachShader(mdiProgram, fragShader);
        GL20.glLinkProgram(mdiProgram);
        if (GL20.glGetProgrami(mdiProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Shader program linking failed: " + GL20.glGetProgramInfoLog(mdiProgram));
        }

        GL20.glDeleteShader(vertShader);
        GL20.glDeleteShader(fragShader);

        locProjMatrix = GL20.glGetUniformLocation(mdiProgram, "projMatrix");
        locParentIds = GL20.glGetUniformLocation(mdiProgram, "parentIds");
        locLocalTranslations = GL20.glGetUniformLocation(mdiProgram, "localTranslations");

        GL20.glUseProgram(mdiProgram);
        int locTexSampler = GL20.glGetUniformLocation(mdiProgram, "textureSampler");
        if (locTexSampler != -1) {
            GL20.glUniform1i(locTexSampler, 0);
        }
        GL20.glUseProgram(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, net.minecraft.client.model.geom.ModelPart> getChildren(net.minecraft.client.model.geom.ModelPart part) {
        try {
            java.lang.reflect.Field childrenField = net.minecraft.client.model.geom.ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            return (Map<String, net.minecraft.client.model.geom.ModelPart>) childrenField.get(part);
        } catch (Exception e) {
            LOGGER.error("Failed to get ModelPart children via reflection", e);
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * モブの ModelPart を走査し、モデル全体のメッシュジオメトリおよびボーン構造を抽出します。
     */
    private static EntityMeshInfo getOrExtractMesh(String registryName, LivingEntity entity) {
        EntityMeshInfo info = MESHES.get(registryName);
        if (info != null) return info;

        try {
            net.minecraft.client.renderer.entity.EntityRenderer<? super LivingEntity> renderer = 
                net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (!(renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer)) {
                return null;
            }

            net.minecraft.client.model.EntityModel<?> model = 
                ((net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?>) renderer).getModel();
            
            List<net.minecraft.client.model.geom.ModelPart> allParts = new ArrayList<>();
            Map<net.minecraft.client.model.geom.ModelPart, String> partNames = new HashMap<>();
            extractModelParts(model, allParts, partNames);

            if (allParts.isEmpty()) {
                return null;
            }

            // すべてのパーツに完全にユニークなボーンIDを割り当てる (親子関係での衝突を防ぎ無限ループを回避)
            Map<net.minecraft.client.model.geom.ModelPart, Integer> partToBoneId = new HashMap<>();
            int boneCount = 0;
            for (net.minecraft.client.model.geom.ModelPart part : allParts) {
                if (boneCount < 16) {
                    partToBoneId.put(part, boneCount++);
                }
            }

            // ルート判定
            java.util.Set<net.minecraft.client.model.geom.ModelPart> allChildren = new java.util.HashSet<>();
            for (net.minecraft.client.model.geom.ModelPart part : allParts) {
                allChildren.addAll(getChildren(part).values());
            }
            List<net.minecraft.client.model.geom.ModelPart> roots = new ArrayList<>();
            for (net.minecraft.client.model.geom.ModelPart part : allParts) {
                if (!allChildren.contains(part)) {
                    roots.add(part);
                }
            }

            // ボーン階層データおよび初期配置平行移動値の構築
            int[] parentIds = new int[16];
            java.util.Arrays.fill(parentIds, -1);
            float[] localTranslations = new float[16 * 3];
            float[] staticX = new float[16];
            float[] staticY = new float[16];
            float[] staticZ = new float[16];

            for (net.minecraft.client.model.geom.ModelPart part : allParts) {
                Integer id = partToBoneId.get(part);
                if (id != null && id < 16) {
                    staticX[id] = part.x;
                    staticY[id] = part.y;
                    staticZ[id] = part.z;
                    localTranslations[id * 3] = part.x / 16.0f;
                    localTranslations[id * 3 + 1] = part.y / 16.0f;
                    localTranslations[id * 3 + 2] = part.z / 16.0f;

                    for (net.minecraft.client.model.geom.ModelPart child : getChildren(part).values()) {
                        Integer childId = partToBoneId.get(child);
                        if (childId != null && childId < 16) {
                            parentIds[childId] = id;
                        }
                    }
                }
            }

            // 頂点の抽出と三角形インデックスの構築
            int vertexOffset = ALL_VERTICES.size();
            int firstIndex = ALL_INDICES.size();

            int localVertexCounter = 0;
            for (net.minecraft.client.model.geom.ModelPart part : allParts) {
                Integer boneId = partToBoneId.get(part);
                if (boneId == null) continue;
                List<SkinnedVertex> partVertices = extractPartVertices(part, boneId);
                
                // Quad（4頂点）からTriangle（6インデックス）へ変換
                for (int i = 0; i < partVertices.size(); i += 4) {
                    if (i + 3 >= partVertices.size()) break;
                    
                    ALL_VERTICES.add(partVertices.get(i));
                    ALL_VERTICES.add(partVertices.get(i + 1));
                    ALL_VERTICES.add(partVertices.get(i + 2));
                    ALL_VERTICES.add(partVertices.get(i + 3));

                    int base = localVertexCounter + i;
                    ALL_INDICES.add(base + 0);
                    ALL_INDICES.add(base + 1);
                    ALL_INDICES.add(base + 2);
                    ALL_INDICES.add(base + 0);
                    ALL_INDICES.add(base + 2);
                    ALL_INDICES.add(base + 3);
                }
                localVertexCounter += partVertices.size();
            }

            int indexCount = ALL_INDICES.size() - firstIndex;

            info = new EntityMeshInfo(firstIndex, indexCount, vertexOffset, allParts, partToBoneId, roots, parentIds, localTranslations, staticX, staticY, staticZ);
            MESHES.put(registryName, info);

            rebuildBuffers();

            LOGGER.info("[GPU MDI] Extracted static skeleton and mesh for: " + registryName + " (" + indexCount + " indices)");
            return info;
        } catch (Exception e) {
            LOGGER.error("Failed to extract model mesh for " + registryName, e);
            return null;
        }
    }

    private static void extractModelParts(Object model, List<net.minecraft.client.model.geom.ModelPart> outParts, Map<net.minecraft.client.model.geom.ModelPart, String> partNames) {
        Class<?> clazz = model.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (net.minecraft.client.model.geom.ModelPart.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        net.minecraft.client.model.geom.ModelPart part = (net.minecraft.client.model.geom.ModelPart) field.get(model);
                        if (part != null && !outParts.contains(part)) {
                            outParts.add(part);
                            partNames.put(part, field.getName());
                            addChildrenParts(part, outParts, partNames, field.getName());
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static void addChildrenParts(net.minecraft.client.model.geom.ModelPart part, List<net.minecraft.client.model.geom.ModelPart> outParts, Map<net.minecraft.client.model.geom.ModelPart, String> partNames, String parentName) {
        for (Map.Entry<String, net.minecraft.client.model.geom.ModelPart> entry : getChildren(part).entrySet()) {
            net.minecraft.client.model.geom.ModelPart child = entry.getValue();
            if (child != null && !outParts.contains(child)) {
                outParts.add(child);
                partNames.put(child, parentName + "_" + entry.getKey());
                addChildrenParts(child, outParts, partNames, parentName + "_" + entry.getKey());
            }
        }
    }

    private static List<SkinnedVertex> extractPartVertices(net.minecraft.client.model.geom.ModelPart part, int boneId) {
        List<SkinnedVertex> out = new ArrayList<>();
        try {
            java.lang.reflect.Field cubesField = net.minecraft.client.model.geom.ModelPart.class.getDeclaredField("cubes");
            cubesField.setAccessible(true);
            List<?> cubes = (List<?>) cubesField.get(part);

            for (Object cube : cubes) {
                java.lang.reflect.Field polygonsField = cube.getClass().getDeclaredField("polygons");
                polygonsField.setAccessible(true);
                Object[] polygons = (Object[]) polygonsField.get(cube);

                for (Object polygon : polygons) {
                    java.lang.reflect.Field verticesField = polygon.getClass().getDeclaredField("vertices");
                    verticesField.setAccessible(true);
                    Object[] vertices = (Object[]) verticesField.get(polygon);

                    java.lang.reflect.Field normalField = polygon.getClass().getDeclaredField("normal");
                    normalField.setAccessible(true);
                    org.joml.Vector3f normal = (org.joml.Vector3f) normalField.get(polygon);

                    for (Object vertex : vertices) {
                        java.lang.reflect.Field posField = vertex.getClass().getDeclaredField("pos");
                        posField.setAccessible(true);
                        org.joml.Vector3f pos = (org.joml.Vector3f) posField.get(vertex);

                        java.lang.reflect.Field uField = vertex.getClass().getDeclaredField("u");
                        uField.setAccessible(true);
                        float u = uField.getFloat(vertex);

                        java.lang.reflect.Field vField = vertex.getClass().getDeclaredField("v");
                        vField.setAccessible(true);
                        float v = vField.getFloat(vertex);

                        out.add(new SkinnedVertex(pos.x() / 16.0f, pos.y() / 16.0f, pos.z() / 16.0f, u, v, normal.x(), normal.y(), normal.z(), boneId));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to extract vertices from ModelPart via reflection", e);
        }
        return out;
    }

    private static void rebuildBuffers() {
        if (vao == -1) {
            vao = GL30.glGenVertexArrays();
            sharedVbo = GL15.glGenBuffers();
            sharedEbo = GL15.glGenBuffers();
            indirectBuffer = GL15.glGenBuffers();
            ssbo = GL15.glGenBuffers();
        }

        GL30.glBindVertexArray(vao);

        // VBOの構築
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, sharedVbo);
        FloatBuffer vBuf = BufferUtils.createFloatBuffer(ALL_VERTICES.size() * 9);
        for (SkinnedVertex v : ALL_VERTICES) {
            vBuf.put(v.x); vBuf.put(v.y); vBuf.put(v.z);
            vBuf.put(v.u); vBuf.put(v.v);
            vBuf.put(v.nx); vBuf.put(v.ny); vBuf.put(v.nz);
            vBuf.put((float) v.boneId);
        }
        vBuf.flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vBuf, GL15.GL_STATIC_DRAW);

        // EBOの構築
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, sharedEbo);
        IntBuffer iBuf = BufferUtils.createIntBuffer(ALL_INDICES.size());
        for (int index : ALL_INDICES) {
            iBuf.put(index);
        }
        iBuf.flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, iBuf, GL15.GL_STATIC_DRAW);

        // Position: attribute 0
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 9 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        // UV: attribute 1
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 9 * Float.BYTES, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        // Normal: attribute 2
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, 9 * Float.BYTES, 5 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);

        // BoneID: attribute 3
        GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, 9 * Float.BYTES, 8 * Float.BYTES);
        GL20.glEnableVertexAttribArray(3);

        GL30.glBindVertexArray(0);
    }

    @SuppressWarnings("unchecked")
    public static boolean queueEntity(LivingEntity entity, PoseStack poseStack, int packedLight, net.minecraft.resources.ResourceLocation texture,
                                      net.minecraft.client.renderer.MultiBufferSource bufferSource, float partialTick) {
        if (!initialized || !Config.entityMDI) return false;

        String registryName = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        
        EntityMeshInfo mesh = getOrExtractMesh(registryName, entity);
        if (mesh == null) return false;

        // アニメーション計算用パラメータ (setupAnim呼び出し用)
        float limbSwing = 0.0f;
        float limbSwingAmount = 0.0f;
        if (entity.isAlive()) {
            limbSwing = entity.walkAnimation.position();
            limbSwingAmount = entity.walkAnimation.speed();
            if (entity.isBaby()) {
                limbSwing *= 3.0f;
            }
            if (limbSwingAmount > 1.0f) {
                limbSwingAmount = 1.0f;
            }
        }

        float bodyYaw = net.minecraft.util.Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float headYawAbs = net.minecraft.util.Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
        float headYawDegrees = headYawAbs - bodyYaw;
        float headPitchDegrees = net.minecraft.util.Mth.rotLerp(partialTick, entity.xRotO, entity.getXRot());

        // CPU上で一度 setupAnim を走らせ、ModelPartの各回転角（xRot, yRot, zRot, x, y, z）と visible を計算
        try {
            net.minecraft.client.renderer.entity.EntityRenderer<? super LivingEntity> renderer = 
                net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer) {
                net.minecraft.client.model.EntityModel<LivingEntity> model = 
                    (net.minecraft.client.model.EntityModel<LivingEntity>) ((net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?>) renderer).getModel();
                float ageInTicks = getBob((net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?>) renderer, entity, partialTick);
                model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, headYawDegrees, headPitchDegrees);
            }
        } catch (Exception e) {
            // ignore setupAnim call failures
        }

        // 各ボーンの現在の回転角 (xRot, yRot, zRot) および 動的平行移動値 (x, y, z - staticX/Y/Z) を収集
        float[] boneRotations = new float[16 * 4];
        float[] boneTranslations = new float[16 * 4];
        for (int i = 0; i < 16; i++) {
            boneRotations[i * 4] = 0.0f;
            boneRotations[i * 4 + 1] = 0.0f;
            boneRotations[i * 4 + 2] = 0.0f;
            boneRotations[i * 4 + 3] = 1.0f; // デフォルトスケール 1.0

            boneTranslations[i * 4] = 0.0f;
            boneTranslations[i * 4 + 1] = 0.0f;
            boneTranslations[i * 4 + 2] = 0.0f;
            boneTranslations[i * 4 + 3] = 0.0f;
        }

        for (net.minecraft.client.model.geom.ModelPart part : mesh.allParts) {
            Integer id = mesh.partToBoneId.get(part);
            if (id != null && id < 16) {
                boneRotations[id * 4] = part.xRot;
                boneRotations[id * 4 + 1] = part.yRot;
                boneRotations[id * 4 + 2] = part.zRot;
                boneRotations[id * 4 + 3] = part.visible ? part.xScale : 0.0f;

                // 初期配置位置（Pivot）からの動的差分を算出（Blazeのロッド回転追従用）
                boneTranslations[id * 4] = (part.x - mesh.staticX[id]) / 16.0f;
                boneTranslations[id * 4 + 1] = (part.y - mesh.staticY[id]) / 16.0f;
                boneTranslations[id * 4 + 2] = (part.z - mesh.staticZ[id]) / 16.0f;
                boneTranslations[id * 4 + 3] = 0.0f;
            }
        }

        // デフォルト色
        float r = 1.0f, g = 1.0f, b = 1.0f;

        // ダメージフラッシュ（赤）
        float overlayR = 1.0f, overlayG = 0.0f, overlayB = 0.0f, overlayA = 0.0f;
        if (entity.hurtTime > 0 || entity.deathTime > 0) {
            overlayA = 0.5f;
        }

        // 光量アンパック (0..15 の範囲に戻す)
        float blockLight = (float) ((packedLight & 0xFFFF) / 16.0f);
        float skyLight = (float) (((packedLight >> 16) & 0xFFFF) / 16.0f);

        // バニラに合わせて Y 軸および X 軸を反転し、胴体のY回転を適用してから基準位置 (0.0, -1.501, 0.0) へ平行移動
        Matrix4f modelView = new Matrix4f(poseStack.last().pose());
        
        // 1. 胴体のY軸回転 (setupRotations) - 軸反転の前に適用することで回転方向の逆転を防ぐ
        float bodyRotRad = (180.0f - bodyYaw) * ((float)Math.PI / 180.0f);
        modelView.rotateY(bodyRotRad);
        
        // 2. X,Yフリップ
        modelView.scale(-1.0f, -1.0f, 1.0f);

        // レンダラー固有のスケール（スライムの大きさなど）をリフレクション経由で適用
        PoseStack tempStack = new PoseStack();
        tempStack.last().pose().set(modelView);
        try {
            net.minecraft.client.renderer.entity.EntityRenderer<? super LivingEntity> renderer = 
                net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer) {
                invokeRendererScale((net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?>) renderer, entity, tempStack, partialTick);
            }
        } catch (Exception e) {
            if (entity instanceof net.minecraft.world.entity.AgeableMob && ((net.minecraft.world.entity.AgeableMob) entity).isBaby()) {
                tempStack.scale(0.5f, 0.5f, 0.5f);
            }
        }
        modelView.set(tempStack.last().pose());
        
        modelView.translate(0.0f, -1.501f, 0.0f);

        BatchKey key = new BatchKey(texture, registryName);
        List<QueuedInstance> list = BATCHES.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(new QueuedInstance(modelView, boneRotations, boneTranslations, r, g, b, 1.0f, overlayR, overlayG, overlayB, overlayA, blockLight, skyLight));

        // CPU側のRenderLayers（防具、衣装、羊毛、エンダーマンの光る目など）をバニラのコードでレンダリング
        try {
            net.minecraft.client.renderer.entity.EntityRenderer<? super LivingEntity> renderer = 
                net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer) {
                net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> livingRenderer = 
                    (net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?>) renderer;
                
                List<?> layers = getLayersList(livingRenderer);
                if (layers != null && !layers.isEmpty()) {
                    float ageInTicks = getBob(livingRenderer, entity, partialTick);
                    
                    // レンダリング用の一時姿勢スタックを作成し、バニラのLivingEntityRendererと同一の変換を適用
                    poseStack.pushPose();
                    
                    // 1. 胴体のY軸回転 (setupRotations) - 軸反転の前に適用することで回転方向の逆転を防ぐ
                    poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f - bodyYaw));
                    
                    // 2. X,Yフリップ
                    poseStack.scale(-1.0f, -1.0f, 1.0f);
                    
                    // 3. レンダラー固有のスケール（スライムの大きさなど）の適用
                    try {
                        invokeRendererScale(livingRenderer, entity, poseStack, partialTick);
                    } catch (Exception e) {
                        if (entity instanceof net.minecraft.world.entity.AgeableMob && ((net.minecraft.world.entity.AgeableMob) entity).isBaby()) {
                            poseStack.scale(0.5f, 0.5f, 0.5f);
                        }
                    }
                    
                    // 4. 基準位置調整
                    poseStack.translate(0.0f, -1.501f, 0.0f);
                    
                    for (Object layer : layers) {
                        if (layer instanceof net.minecraft.client.renderer.entity.layers.RenderLayer) {
                            net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, net.minecraft.client.model.EntityModel<LivingEntity>> renderLayer = 
                                (net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, net.minecraft.client.model.EntityModel<LivingEntity>>) layer;
                            
                            renderLayer.render(poseStack, bufferSource, packedLight, entity, limbSwing, limbSwingAmount, partialTick, ageInTicks, headYawDegrees, headPitchDegrees);
                        }
                    }
                    
                    poseStack.popPose();
                }
            }
        } catch (Exception e) {
            // ignore layer rendering failures
        }

        return true;
    }

    public static void clearQueue() {
        BATCHES.clear();
    }

    private static void bindEntityTexture(net.minecraft.resources.ResourceLocation texture) {
        RenderSystem.setShaderTexture(0, texture);
        net.minecraft.client.renderer.texture.AbstractTexture tex = 
            net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(texture);
        if (tex != null) {
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(tex.getId());
        }
    }

    public static void flush(PoseStack poseStack, Matrix4f projMatrix) {
        if (!initialized || BATCHES.isEmpty()) return;

        // 共通レンダリングステート設定
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        // 色反転バグを防ぐため Color Logic Op を無効化
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);

        // 標準的なアルファブレンドを構成
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        GL20.glUseProgram(mdiProgram);

        // Projection Matrix
        FloatBuffer projBuf = BufferUtils.createFloatBuffer(16);
        projMatrix.get(projBuf);
        GL20.glUniformMatrix4fv(locProjMatrix, false, projBuf);

        GL30.glBindVertexArray(vao);

        // 各モブ種別ごとにバッチ描画を実行
        for (Map.Entry<BatchKey, List<QueuedInstance>> entry : BATCHES.entrySet()) {
            BatchKey key = entry.getKey();
            List<QueuedInstance> list = entry.getValue();
            if (list.isEmpty()) continue;

            EntityMeshInfo mesh = MESHES.get(key.registryName);
            if (mesh == null) continue;

            int totalInstances = list.size();

            bindEntityTexture(key.texture);

            // 静的な階層情報を uniform 配列にアップロード (1バッチにつき1回のみ)
            GL20.glUniform1iv(locParentIds, mesh.parentIds);
            FloatBuffer transBuf = BufferUtils.createFloatBuffer(48);
            transBuf.put(mesh.localTranslations);
            transBuf.flip();
            GL20.glUniform3fv(locLocalTranslations, transBuf);

            // SSBOデータのパック (Std430レイアウト: 1インスタンス=624バイト)
            ByteBuffer ssboData = BufferUtils.createByteBuffer(totalInstances * 624);
            ssboData.order(ByteOrder.nativeOrder());
            FloatBuffer floatView = ssboData.asFloatBuffer();
            for (QueuedInstance q : list) {
                // modelViewMatrix (16 floats)
                floatView.put(q.modelView.m00()); floatView.put(q.modelView.m01()); floatView.put(q.modelView.m02()); floatView.put(q.modelView.m03());
                floatView.put(q.modelView.m10()); floatView.put(q.modelView.m11()); floatView.put(q.modelView.m12()); floatView.put(q.modelView.m13());
                floatView.put(q.modelView.m20()); floatView.put(q.modelView.m21()); floatView.put(q.modelView.m22()); floatView.put(q.modelView.m23());
                floatView.put(q.modelView.m30()); floatView.put(q.modelView.m31()); floatView.put(q.modelView.m32()); floatView.put(q.modelView.m33());
                
                // boneRotations[16] & boneTranslations[16] (16 * 8 = 128 floats)
                for (int i = 0; i < 16; i++) {
                    floatView.put(q.boneRotations[i * 4]);
                    floatView.put(q.boneRotations[i * 4 + 1]);
                    floatView.put(q.boneRotations[i * 4 + 2]);
                    floatView.put(q.boneRotations[i * 4 + 3]);

                    floatView.put(q.boneTranslations[i * 4]);
                    floatView.put(q.boneTranslations[i * 4 + 1]);
                    floatView.put(q.boneTranslations[i * 4 + 2]);
                    floatView.put(q.boneTranslations[i * 4 + 3]);
                }

                // color (4 floats)
                floatView.put(q.r); floatView.put(q.g); floatView.put(q.b); floatView.put(q.a);

                // overlayColor (4 floats)
                floatView.put(q.overlayR); floatView.put(q.overlayG); floatView.put(q.overlayB); floatView.put(q.overlayA);

                // lightCoords (4 floats)
                floatView.put(q.blockLight); floatView.put(q.skyLight); floatView.put(0.0f); floatView.put(0.0f);
            }
            ssboData.position(totalInstances * 624);
            ssboData.flip();

            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, ssboData, GL15.GL_DYNAMIC_DRAW);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, ssbo);

            // MDI コマンドバッファの構築
            ByteBuffer indirectData = BufferUtils.createByteBuffer(20);
            indirectData.order(ByteOrder.nativeOrder());
            IntBuffer intView = indirectData.asIntBuffer();
            intView.put(mesh.indexCount);    // count
            intView.put(totalInstances);     // instanceCount
            intView.put(mesh.firstIndex);    // firstIndex
            intView.put(mesh.vertexOffset);  // baseVertex
            intView.put(0);                  // baseInstance
            indirectData.position(20);
            indirectData.flip();

            GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
            GL15.glBufferData(GL43.GL_DRAW_INDIRECT_BUFFER, indirectData, GL15.GL_DYNAMIC_DRAW);

            // MDI 描画呼び出し
            GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, 0, 1, 0);
        }

        // 後処理
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL20.glUseProgram(0);

        BATCHES.clear();
    }

    public static void cleanup() {
        if (mdiProgram != -1) {
            GL20.glDeleteProgram(mdiProgram);
            mdiProgram = -1;
        }
        if (vao != -1) {
            GL30.glDeleteVertexArrays(vao);
            vao = -1;
        }
        if (sharedVbo != -1) {
            GL15.glDeleteBuffers(sharedVbo);
            sharedVbo = -1;
        }
        if (sharedEbo != -1) {
            GL15.glDeleteBuffers(sharedEbo);
            sharedEbo = -1;
        }
        if (indirectBuffer != -1) {
            GL15.glDeleteBuffers(indirectBuffer);
            indirectBuffer = -1;
        }
        if (ssbo != -1) {
            GL15.glDeleteBuffers(ssbo);
            ssbo = -1;
        }
        initialized = false;
        ALL_VERTICES.clear();
        ALL_INDICES.clear();
        MESHES.clear();
        BATCHES.clear();
    }

    private static List<?> getLayersList(net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> renderer) {
        try {
            java.lang.reflect.Field layersField = net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getDeclaredField("f_115291_");
            layersField.setAccessible(true);
            return (List<?>) layersField.get(renderer);
        } catch (Exception e) {
            try {
                java.lang.reflect.Field layersField = net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getDeclaredField("layers");
                layersField.setAccessible(true);
                return (List<?>) layersField.get(renderer);
            } catch (Exception e2) {
                return java.util.Collections.emptyList();
            }
        }
    }

    private static float getBob(net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> renderer, LivingEntity entity, float partialTicks) {
        try {
            java.lang.reflect.Method getBobMethod = net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getDeclaredMethod(
                "m_6930_", net.minecraft.world.entity.LivingEntity.class, float.class
            );
            getBobMethod.setAccessible(true);
            return (Float) getBobMethod.invoke(renderer, entity, partialTicks);
        } catch (Exception e) {
            try {
                java.lang.reflect.Method getBobMethod = net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getDeclaredMethod(
                    "getBob", net.minecraft.world.entity.LivingEntity.class, float.class
                );
                getBobMethod.setAccessible(true);
                return (Float) getBobMethod.invoke(renderer, entity, partialTicks);
            } catch (Exception e2) {
                return (float) entity.tickCount + partialTicks;
            }
        }
    }

    private static void invokeRendererScale(net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> renderer,
                                             LivingEntity entity, PoseStack poseStack, float partialTick) {
        try {
            java.lang.reflect.Method scaleMethod = net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getDeclaredMethod(
                "m_7546_", net.minecraft.world.entity.LivingEntity.class, PoseStack.class, float.class
            );
            scaleMethod.setAccessible(true);
            scaleMethod.invoke(renderer, entity, poseStack, partialTick);
        } catch (Exception e) {
            try {
                java.lang.reflect.Method scaleMethod = net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getDeclaredMethod(
                    "scale", net.minecraft.world.entity.LivingEntity.class, PoseStack.class, float.class
                );
                scaleMethod.setAccessible(true);
                scaleMethod.invoke(renderer, entity, poseStack, partialTick);
            } catch (Exception e2) {
                // fallback
            }
        }
    }
}
