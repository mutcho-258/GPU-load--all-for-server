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

    public static int lastFrameRenderedEntities = 0;
    public static int lastFrameBatches = 0;

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

    public static class BatchKey implements Comparable<BatchKey> {
        public final int layerOrder; // 0: 肌, 1: バイオーム服, 2: 職業服, 3: レベルバッジ
        public final net.minecraft.resources.ResourceLocation texture;
        public final String registryName;

        public BatchKey(int layerOrder, net.minecraft.resources.ResourceLocation texture, String registryName) {
            this.layerOrder = layerOrder;
            this.texture = texture;
            this.registryName = registryName;
        }

        @Override
        public int compareTo(BatchKey o) {
            int cmp = Integer.compare(this.layerOrder, o.layerOrder);
            if (cmp != 0) return cmp;
            cmp = this.registryName.compareTo(o.registryName);
            if (cmp != 0) return cmp;
            return this.texture.compareTo(o.texture);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BatchKey batchKey = (BatchKey) o;
            return layerOrder == batchKey.layerOrder && texture.equals(batchKey.texture) && registryName.equals(batchKey.registryName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(layerOrder, texture, registryName);
        }
    }

    // テクスチャとモブ種別ごとのレンダリングバッチ (layerOrder順に描画するためTreeMapを使用)
    private static final java.util.NavigableMap<BatchKey, List<QueuedInstance>> BATCHES = new java.util.TreeMap<>();

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
            "uniform sampler2D lightMapSampler;\n" +
            "\n" +
            "void main() {\n" +
            "    vec4 texColor = texture(textureSampler, fragTexCoord);\n" +
            "    if (texColor.a < 0.1) {\n" +
            "        discard;\n" +
            "    }\n" +
            "    \n" +
            "    // バニラ準拠のライトマップサンプリング (x: blockLight 0..15, y: skyLight 0..15)\n" +
            "    ivec2 lightCoord = clamp(ivec2(round(fragLight)), 0, 15);\n" +
            "    vec4 lightColor = texelFetch(lightMapSampler, lightCoord, 0);\n" +
            "    \n" +
            "    // 安全フォールバック: 万一ライトマップテクスチャが未バインド（黒/透明）の場合の保険\n" +
            "    if (lightColor.a < 0.1 || dot(lightColor.rgb, vec3(1.0)) < 0.001) {\n" +
            "        float b = fragLight.x / 15.0;\n" +
            "        float s = fragLight.y / 15.0;\n" +
            "        lightColor = vec4(vec3(max(0.2, max(b * 0.9, s * 0.6))), 1.0);\n" +
            "    }\n" +
            "    \n" +
            "    // 自然な立体感（陰影）のためのディフューズ計算\n" +
            "    vec3 normal = normalize(fragNormal);\n" +
            "    vec3 lightDir = normalize(vec3(0.2, 1.0, -0.7));\n" +
            "    float diff = max(dot(normal, lightDir), 0.0);\n" +
            "    float diffuseFactor = 0.6 + 0.4 * diff;\n" +
            "    \n" +
            "    vec3 finalRgb = texColor.rgb * fragColor.rgb * lightColor.rgb * diffuseFactor;\n" +
            "    \n" +
            "    if (fragOverlay.a > 0.0) {\n" +
            "        finalRgb = mix(finalRgb, fragOverlay.rgb, fragOverlay.a);\n" +
            "    }\n" +
            "    \n" +
            "    outColor = vec4(finalRgb, texColor.a * fragColor.a);\n" +
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
            GL20.glUniform1i(locTexSampler, 0); // テクスチャユニット0
        }
        int locLightSampler = GL20.glGetUniformLocation(mdiProgram, "lightMapSampler");
        if (locLightSampler != -1) {
            GL20.glUniform1i(locLightSampler, 2); // テクスチャユニット2
        }
        GL20.glUseProgram(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, net.minecraft.client.model.geom.ModelPart> getChildren(net.minecraft.client.model.geom.ModelPart part) {
        try {
            java.lang.reflect.Field childrenField;
            try {
                childrenField = net.minecraft.client.model.geom.ModelPart.class.getDeclaredField("children");
            } catch (NoSuchFieldException e) {
                childrenField = net.minecraft.client.model.geom.ModelPart.class.getDeclaredField("f_104213_");
            }
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
            
            return extractMeshFromModel(registryName, model);
        } catch (Exception e) {
            LOGGER.error("Failed to extract model mesh for " + registryName, e);
            return null;
        }
    }

    /**
     * スライムの外側ゲルレイヤー（SlimeOuterLayer）のモデルメッシュを抽出します。
     */
    private static EntityMeshInfo getOrExtractSlimeOuterMesh(String outerRegistryName, LivingEntity entity) {
        EntityMeshInfo info = MESHES.get(outerRegistryName);
        if (info != null) return info;

        try {
            net.minecraft.client.renderer.entity.EntityRenderer<? super LivingEntity> renderer = 
                net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (!(renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer)) {
                return null;
            }
            List<?> layers = getLayersList((net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?>) renderer);
            if (layers != null) {
                for (Object layer : layers) {
                    if (layer instanceof net.minecraft.client.renderer.entity.layers.SlimeOuterLayer) {
                        Class<?> clazz = layer.getClass();
                        while (clazz != null && clazz != Object.class) {
                            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                                if (net.minecraft.client.model.EntityModel.class.isAssignableFrom(field.getType())) {
                                    field.setAccessible(true);
                                    Object outerModel = field.get(layer);
                                    if (outerModel != null) {
                                        return extractMeshFromModel(outerRegistryName, outerModel);
                                    }
                                }
                            }
                            clazz = clazz.getSuperclass();
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to extract slime outer mesh", e);
        }
        return null;
    }

    private static EntityMeshInfo extractMeshFromModel(String registryName, Object model) {
        EntityMeshInfo info = MESHES.get(registryName);
        if (info != null) return info;

        try {
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
            java.lang.reflect.Field cubesField;
            try {
                cubesField = net.minecraft.client.model.geom.ModelPart.class.getDeclaredField("cubes");
            } catch (NoSuchFieldException e) {
                cubesField = net.minecraft.client.model.geom.ModelPart.class.getDeclaredField("f_104212_");
            }
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

        // バニラのVAOキャッシュと同期させるため GlStateManager を経由してバインド
        com.mojang.blaze3d.platform.GlStateManager._glBindVertexArray(vao);

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

        com.mojang.blaze3d.vertex.BufferUploader.reset();
        com.mojang.blaze3d.vertex.VertexBuffer.unbind();
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

        // レンダラーの取得
        net.minecraft.client.renderer.entity.EntityRenderer<? super LivingEntity> entityRenderer = 
            net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> livingRenderer = 
            (entityRenderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer) ?
            (net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?>) entityRenderer : null;

        float ageInTicks = (livingRenderer != null) ? getBob(livingRenderer, entity, partialTick) : ((float)entity.tickCount + partialTick);

        // CPU上で一度 setupAnim を走らせ、ModelPartの各回転角（xRot, yRot, zRot, x, y, z）と visible を計算
        // resetPose() を先に呼ぶことで、前エンティティの layer.render() が引き起こした hatVisible(false) 等の
        // モデル状態変更（head.visible=false など）をリセットする
        if (livingRenderer != null) {
            try {
                net.minecraft.client.model.EntityModel<LivingEntity> model = 
                    (net.minecraft.client.model.EntityModel<LivingEntity>) livingRenderer.getModel();
                if (model instanceof net.minecraft.client.model.VillagerHeadModel) {
                    ((net.minecraft.client.model.VillagerHeadModel) model).hatVisible(true);
                }
                resetAllPartsVisible(model);
                model.attackTime = entity.getAttackAnim(partialTick);
                model.riding = entity.isPassenger() && entity.getVehicle() != null && entity.getVehicle().shouldRiderSit();
                model.young = entity.isBaby();
                model.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTick);
                model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, headYawDegrees, headPitchDegrees);
            } catch (Exception e) {
                // ignore setupAnim call failures
            }
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

        // 光量アンパック (バニラ準拠で 0..15 の範囲を取得)
        float blockLight = (float) net.minecraft.client.renderer.LightTexture.block(packedLight);
        float skyLight = (float) net.minecraft.client.renderer.LightTexture.sky(packedLight);

        // バニラと100%同一の姿勢変換（睡眠位置オフセット、死亡演出/Dinnerbone/激流/氷漬け等の setupRotations、X/Yフリップ、固有scale、基準位置）を適用
        PoseStack tempStack = new PoseStack();
        tempStack.last().pose().set(poseStack.last().pose());
        applyEntityTransforms(tempStack, livingRenderer, entity, partialTick, bodyYaw, ageInTicks);
        Matrix4f modelView = new Matrix4f(tempStack.last().pose());

        BatchKey baseKey = new BatchKey(0, texture, registryName);
        QueuedInstance instance = new QueuedInstance(modelView, boneRotations, boneTranslations, r, g, b, 1.0f, overlayR, overlayG, overlayB, overlayA, blockLight, skyLight);
        BATCHES.computeIfAbsent(baseKey, k -> new ArrayList<>()).add(instance);

        // 村人・ゾンビ村人の服・職業服・装飾レイヤーを直接 GPU MDI パイプラインに追加
        // （CPUとGPUの計算ズレによるZ-Fightingや深度埋もれを原理的に防ぎ、同一パイプラインで描画する）
        if (entity instanceof net.minecraft.world.entity.npc.Villager) {
            net.minecraft.world.entity.npc.Villager villager = (net.minecraft.world.entity.npc.Villager) entity;
            net.minecraft.world.entity.npc.VillagerData data = villager.getVillagerData();
            
            // 1. バイオーム服 (layerOrder = 1)
            net.minecraft.resources.ResourceLocation typeKey = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType());
            net.minecraft.resources.ResourceLocation typeTex = getVillagerResourceLocation("type", typeKey);
            BATCHES.computeIfAbsent(new BatchKey(1, typeTex, registryName), k -> new ArrayList<>()).add(instance);

            // 2. 職業服 (layerOrder = 2)
            if (data.getProfession() != net.minecraft.world.entity.npc.VillagerProfession.NONE && !villager.isBaby()) {
                net.minecraft.resources.ResourceLocation profKey = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession());
                net.minecraft.resources.ResourceLocation profTex = getVillagerResourceLocation("profession", profKey);
                BATCHES.computeIfAbsent(new BatchKey(2, profTex, registryName), k -> new ArrayList<>()).add(instance);

                // 3. 職業レベルバッジ (layerOrder = 3)
                if (data.getProfession() != net.minecraft.world.entity.npc.VillagerProfession.NITWIT) {
                    int level = net.minecraft.util.Mth.clamp(data.getLevel(), 1, 5);
                    net.minecraft.resources.ResourceLocation levelKey = LEVEL_LOCATIONS.get(level);
                    if (levelKey != null) {
                        net.minecraft.resources.ResourceLocation levelTex = getVillagerResourceLocation("profession_level", levelKey);
                        BATCHES.computeIfAbsent(new BatchKey(3, levelTex, registryName), k -> new ArrayList<>()).add(instance);
                    }
                }
            }
        } else if (entity instanceof net.minecraft.world.entity.monster.ZombieVillager) {
            net.minecraft.world.entity.monster.ZombieVillager zombieVillager = (net.minecraft.world.entity.monster.ZombieVillager) entity;
            net.minecraft.world.entity.npc.VillagerData data = zombieVillager.getVillagerData();
            
            net.minecraft.resources.ResourceLocation typeKey = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType());
            net.minecraft.resources.ResourceLocation typeTex = getZombieVillagerResourceLocation("type", typeKey);
            BATCHES.computeIfAbsent(new BatchKey(1, typeTex, registryName), k -> new ArrayList<>()).add(instance);

            if (data.getProfession() != net.minecraft.world.entity.npc.VillagerProfession.NONE && !zombieVillager.isBaby()) {
                net.minecraft.resources.ResourceLocation profKey = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession());
                net.minecraft.resources.ResourceLocation profTex = getZombieVillagerResourceLocation("profession", profKey);
                BATCHES.computeIfAbsent(new BatchKey(2, profTex, registryName), k -> new ArrayList<>()).add(instance);

                int level = net.minecraft.util.Mth.clamp(data.getLevel(), 1, 5);
                net.minecraft.resources.ResourceLocation levelKey = LEVEL_LOCATIONS.get(level);
                if (levelKey != null) {
                    net.minecraft.resources.ResourceLocation levelTex = getZombieVillagerResourceLocation("profession_level", levelKey);
                    BATCHES.computeIfAbsent(new BatchKey(3, levelTex, registryName), k -> new ArrayList<>()).add(instance);
                }
            }
        } else if (entity instanceof net.minecraft.world.entity.monster.Slime && !(entity instanceof net.minecraft.world.entity.monster.MagmaCube)) {
            // スライムの外側ゲルレイヤーを直接 GPU MDI パイプラインに追加 (layerOrder = 1)
            // （CPUとGPUの計算ズレや深度競合を完全に排除し、本体コア描画の直後に半透明描画する）
            String outerRegistryName = registryName + "_outer";
            EntityMeshInfo outerMesh = getOrExtractSlimeOuterMesh(outerRegistryName, entity);
            if (outerMesh != null) {
                BATCHES.computeIfAbsent(new BatchKey(1, texture, outerRegistryName), k -> new ArrayList<>()).add(instance);
            }
        }

        // CPU側のRenderLayers（防具、手持ちアイテム、頭のブロックなど）のみをバニラコードでレンダリング
        if (livingRenderer != null) {
            try {
                List<?> layers = getLayersList(livingRenderer);
                if (layers != null && !layers.isEmpty()) {
                    poseStack.pushPose();
                    applyEntityTransforms(poseStack, livingRenderer, entity, partialTick, bodyYaw, ageInTicks);

                    for (Object layer : layers) {
                        if (layer instanceof net.minecraft.client.renderer.entity.layers.RenderLayer) {
                            // 村人の服およびスライムの外側ゲルはGPU MDI側で一括描画するため、バニラ側ではスキップ
                            if (layer instanceof net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer ||
                                layer instanceof net.minecraft.client.renderer.entity.layers.SlimeOuterLayer) {
                                continue;
                            }

                            net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, net.minecraft.client.model.EntityModel<LivingEntity>> renderLayer = 
                                (net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, net.minecraft.client.model.EntityModel<LivingEntity>>) layer;
                            
                            renderLayer.render(poseStack, bufferSource, packedLight, entity, limbSwing, limbSwingAmount, partialTick, ageInTicks, headYawDegrees, headPitchDegrees);
                        }
                    }
                    
                    poseStack.popPose();
                }
            } catch (Exception e) {
                // ignore layer rendering failures
            }
        }

        return true;
    }

    public static void clearQueue() {
        BATCHES.clear();
    }

    private static void bindEntityTexture(net.minecraft.resources.ResourceLocation texture) {
        RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        RenderSystem.setShaderTexture(0, texture);
        net.minecraft.client.renderer.texture.AbstractTexture tex = 
            net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(texture);
        if (tex != null) {
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(tex.getId());
        }
    }

    public static void flush(PoseStack poseStack, Matrix4f projMatrix) {
        if (!initialized || BATCHES.isEmpty()) return;

        // バニラのライトマップテクスチャを有効化 (テクスチャユニット2にバインド)
        RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
        net.minecraft.client.Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);

        // 共通レンダリングステート設定
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        // 色反転バグを防ぐため Color Logic Op を無効化
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);

        // 標準的なアルファブレンドを構成
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // バニラのシェーダーキャッシュと同期させるため ProgramManager を経由してバインド
        com.mojang.blaze3d.shaders.ProgramManager.glUseProgram(mdiProgram);

        // Projection Matrix
        FloatBuffer projBuf = BufferUtils.createFloatBuffer(16);
        projMatrix.get(projBuf);
        GL20.glUniformMatrix4fv(locProjMatrix, false, projBuf);

        // バニラのVAOキャッシュと同期させるため GlStateManager を経由してバインド
        com.mojang.blaze3d.platform.GlStateManager._glBindVertexArray(vao);

        int frameTotal = 0;
        int batchTotal = 0;

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

            frameTotal += totalInstances;
            batchTotal++;
        }

        lastFrameRenderedEntities = frameTotal;
        lastFrameBatches = batchTotal;

        // 後処理
        RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
        net.minecraft.client.Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        com.mojang.blaze3d.vertex.BufferUploader.reset();
        com.mojang.blaze3d.vertex.VertexBuffer.unbind();
        GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        // バニラのシェーダー管理キャッシュと同期
        com.mojang.blaze3d.shaders.ProgramManager.glUseProgram(0);

        // バニラのRenderSystemが期待するテクスチャユニット0をアクティブに戻す
        RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);

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

    /**
     * EntityModel のすべての ModelPart の visible を true に強制リセットします。
     * VillagerProfessionLayer などが hatVisible(false) で head.visible=false にしたまま終わる問題への対策。
     * EntityModel.resetPose() が存在しない場合のフォールバックとして使用します。
     */
    @SuppressWarnings("unused")
    private static void resetAllPartsVisible(net.minecraft.client.model.EntityModel<?> model) {
        Class<?> clazz = model.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (net.minecraft.client.model.geom.ModelPart.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        net.minecraft.client.model.geom.ModelPart part = (net.minecraft.client.model.geom.ModelPart) field.get(model);
                        if (part != null) {
                            setPartVisibleRecursive(part, true);
                        }
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static void setPartVisibleRecursive(net.minecraft.client.model.geom.ModelPart part, boolean visible) {
        part.visible = visible;
        for (net.minecraft.client.model.geom.ModelPart child : getChildren(part).values()) {
            setPartVisibleRecursive(child, visible);
        }
    }

    private static final Map<Integer, net.minecraft.resources.ResourceLocation> LEVEL_LOCATIONS = Map.of(
        1, new net.minecraft.resources.ResourceLocation("stone"),
        2, new net.minecraft.resources.ResourceLocation("iron"),
        3, new net.minecraft.resources.ResourceLocation("gold"),
        4, new net.minecraft.resources.ResourceLocation("emerald"),
        5, new net.minecraft.resources.ResourceLocation("diamond")
    );

    private static net.minecraft.resources.ResourceLocation getVillagerResourceLocation(String category, net.minecraft.resources.ResourceLocation key) {
        return new net.minecraft.resources.ResourceLocation(key.getNamespace(), "textures/entity/villager/" + category + "/" + key.getPath() + ".png");
    }

    private static net.minecraft.resources.ResourceLocation getZombieVillagerResourceLocation(String category, net.minecraft.resources.ResourceLocation key) {
        return new net.minecraft.resources.ResourceLocation(key.getNamespace(), "textures/entity/zombie_villager/" + category + "/" + key.getPath() + ".png");
    }

    private static void invokeRendererScale(net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> renderer,
                                             LivingEntity entity, PoseStack poseStack, float partialTick) {
        Class<?> clazz = renderer.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals("scale") || method.getName().equals("m_7546_")) {
                    if (method.getParameterCount() == 3) {
                        try {
                            method.setAccessible(true);
                            method.invoke(renderer, entity, poseStack, partialTick);
                            return;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * バニラの LivingEntityRenderer.render と100%同一の姿勢変換系列を適用します。
     * （睡眠位置オフセット、死亡演出/Dinnerbone/激流/氷漬け等の setupRotations、X/Yフリップ、固有scale、基準点調整）
     */
    public static void applyEntityTransforms(PoseStack poseStack,
                                             net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> renderer,
                                             LivingEntity entity, float partialTick, float bodyYaw, float ageInTicks) {
        // 1. 睡眠時のベッド基準平行移動
        if (entity.hasPose(net.minecraft.world.entity.Pose.SLEEPING)) {
            net.minecraft.core.Direction direction = entity.getBedOrientation();
            if (direction != null) {
                float eyeHeight = entity.getEyeHeight(net.minecraft.world.entity.Pose.STANDING) - 0.1f;
                poseStack.translate((float) (-direction.getStepX()) * eyeHeight, 0.0f, (float) (-direction.getStepZ()) * eyeHeight);
            }
        }

        // 2. setupRotations（死亡時の倒れる演出、胴体Y回転、Dinnerbone上下反転、激流、氷漬け揺れ等）
        boolean rotationsApplied = false;
        if (renderer != null) {
            rotationsApplied = invokeSetupRotations(renderer, entity, poseStack, ageInTicks, bodyYaw, partialTick);
        }
        if (!rotationsApplied) {
            applyFallbackRotations(entity, poseStack, ageInTicks, bodyYaw, partialTick);
        }

        // 3. X,Y軸フリップ (バニラ標準の右手系・モデル向き調整)
        poseStack.scale(-1.0f, -1.0f, 1.0f);

        // 4. レンダラー固有のスケール (スライムのサイズや子供モブのスケール等)
        if (renderer != null) {
            try {
                invokeRendererScale(renderer, entity, poseStack, partialTick);
            } catch (Exception e) {
                if (entity instanceof net.minecraft.world.entity.AgeableMob && ((net.minecraft.world.entity.AgeableMob) entity).isBaby()) {
                    poseStack.scale(0.5f, 0.5f, 0.5f);
                }
            }
        } else if (entity instanceof net.minecraft.world.entity.AgeableMob && ((net.minecraft.world.entity.AgeableMob) entity).isBaby()) {
            poseStack.scale(0.5f, 0.5f, 0.5f);
        }

        // 5. 基準位置調整
        poseStack.translate(0.0f, -1.501f, 0.0f);
    }

    private static boolean invokeSetupRotations(net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> renderer,
                                                LivingEntity entity, PoseStack poseStack,
                                                float ageInTicks, float bodyYaw, float partialTick) {
        Class<?> clazz = renderer.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals("setupRotations") || method.getName().equals("m_7523_")) {
                    if (method.getParameterCount() == 5) {
                        try {
                            method.setAccessible(true);
                            method.invoke(renderer, entity, poseStack, ageInTicks, bodyYaw, partialTick);
                            return true;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static void applyFallbackRotations(LivingEntity entity, PoseStack poseStack, float ageInTicks, float bodyYaw, float partialTick) {
        if (entity.isFullyFrozen()) {
            bodyYaw += (float)(Math.cos((double)entity.tickCount * 3.25) * Math.PI * 0.4f);
        }
        if (!entity.hasPose(net.minecraft.world.entity.Pose.SLEEPING)) {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f - bodyYaw));
        }
        if (entity.deathTime > 0) {
            float f = ((float)entity.deathTime + partialTick - 1.0f) / 20.0f * 1.6f;
            if ((f = net.minecraft.util.Mth.sqrt(f)) > 1.0f) {
                f = 1.0f;
            }
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(f * 90.0f));
        } else if (entity.isAutoSpinAttack()) {
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0f - entity.getXRot()));
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(((float)entity.tickCount + partialTick) * -75.0f));
        } else if (entity.hasPose(net.minecraft.world.entity.Pose.SLEEPING)) {
            net.minecraft.core.Direction direction = entity.getBedOrientation();
            float f1 = direction != null ? sleepDirectionToRotation(direction) : bodyYaw;
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(f1));
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90.0f));
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(270.0f));
        } else if (net.minecraft.client.renderer.entity.LivingEntityRenderer.isEntityUpsideDown(entity)) {
            poseStack.translate(0.0f, entity.getBbHeight() + 0.1f, 0.0f);
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0f));
        }
    }

    private static float sleepDirectionToRotation(net.minecraft.core.Direction direction) {
        switch (direction) {
            case SOUTH: return 90.0f;
            case WEST: return 0.0f;
            case NORTH: return 270.0f;
            case EAST: return 180.0f;
            default: return 0.0f;
        }
    }
}
