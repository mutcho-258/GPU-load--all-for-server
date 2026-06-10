# GPU Load (Minecraft Forge Mod)

This mod is a groundbreaking optimization mod that achieves dramatic performance improvements by completely offloading Minecraft’s computationally intensive tasks—such as terrain noise generation and mob AI calculations—to the GPU (Compute Shader) for parallel processing.

## Key Features
* **Faster chunk generation via GPU acceleration**: Complex noise calculations are processed in bulk by the GPU, preventing server tick drops.
* **GPU offloading of utility AI**: Heavy AI processes, such as mob target search and danger avoidance, are parallelized on the GPU.
* **Ultimate hybrid configuration (supports both client and server)**:
  * **Single-player (Client)**: Automatically reuses Minecraft’s existing OpenGL environment to run safely without conflicts.
  * **Dedicated Server (Fully Headless)**: Even in a screenless CUI dedicated server environment, it builds a native OpenGL context internally and executes powerful GPU computations in the background.

## Installation and Notes

## # Installation on the Client (Single-Player)
Just like any other mod, simply place it in the `mods/` folder to make it work. No additional configuration or libraries are required.

### Installation on a Dedicated Server
This mod can be installed on a server as a single JAR file, but it exhibits the following special behavior only upon first startup.
1. Place this mod in the `mods/` folder and start the server.
2. When the server starts, the dedicated library (LWJGL) required for headless GPU computing will be automatically extracted into the `mods/` folder.
3. **A warning message stating “YOU MUST RESTART THE SERVER NOW TO LOAD THEM.” will appear, and the server will force-close temporarily.**
4. **Restart the server immediately.** The extracted libraries will be loaded, and GPU acceleration will be fully operational.

### Since this mod was created using various AI systems, there is a possibility that some unexpected behavior has not been fully eliminated.

## Configuration
Configuration files are generated in the `config/gpuloader/` directory.

* `gpuloader-server.toml` (Server-side settings, or per-world settings for single-player)
  * `enableTerrainNoise`: Enable GPU processing for terrain generation (Default: true)
  * `enableGpuAi`: Enable GPU processing for mob AI (default: true)
  * `debugTerrainNoise`: Whether to display detailed latency logs for GPU chunk generation and warnings about fallback to the CPU (default: false)

## License
MIT License (see the LICENSE file for details)



以下日本語で説明

# GPU Load (Minecraft Forge Mod)

このModは、Minecraftの非常に重い計算タスク（地形のノイズ生成やモブAIの演算など）を、GPU（Compute Shader）に完全にオフロードして並列処理させることで、圧倒的なパフォーマンス向上を実現する画期的な最適化Modです。

## 主な特徴
* **GPUアクセラレーションによるチャンク生成の高速化**: 複雑なノイズ計算をGPUで一括処理し、サーバーのTick落ちを防ぎます。
* **Utility AIのGPUオフロード**: モブのターゲット検索や危険回避などの重いAI処理をGPUで並列計算します。
* **究極のハイブリッド構成 (クライアント / サーバー両対応)**:
  * **シングルプレイ (クライアント)**: マイクラ本体が持つ既存のOpenGL環境を自動で再利用し、競合を起こさずに安全に動作します。
  * **専用サーバー (完全ヘッドレス)**: 画面の無いCUIの専用サーバー環境であっても、内部でネイティブのOpenGLコンテキストを構築し、強力なGPU演算をバックグラウンドで実行します。

## 導入方法と注意点

### クライアント（シングルプレイ）への導入
通常のModと同様に、`mods/` フォルダに入れるだけで動作します。余計な設定や追加ライブラリは一切不要です。

### 専用サーバー（Dedicated Server）への導入
本Modは単一のJARファイルでサーバーにも導入可能ですが、初回起動時のみ以下の特殊な挙動をします。
1. `mods/` フォルダに本Modを入れてサーバーを起動します。
2. サーバーが起動すると、ヘッドレスGPU演算に必要な専用ライブラリ（LWJGL）が `mods/` フォルダ内に自動展開されます。
3. **「YOU MUST RESTART THE SERVER NOW TO LOAD THEM.」という警告メッセージが出て、サーバーが一旦強制終了します。**
4. そのまま**もう一度サーバーを起動**してください。展開されたライブラリが読み込まれ、GPUアクセラレーションがフル稼働します。

## 設定 (Configuration)
設定ファイルは `config/gpuloader/` 内に生成されます。

* `gpuloader-server.toml` (サーバー側、またはシングルプレイのワールド単位設定)
  * `enableTerrainNoise`: 地形生成のGPU処理を有効化 (デフォルト: true)
  * `enableGpuAi`: モブAIのGPU処理を有効化 (デフォルト: true)
  * `debugTerrainNoise`: GPUチャンク生成の詳細なレイテンシや、CPUへのフォールバック警告ログを表示するかどうか (デフォルト: false)

## ライセンス
MIT License (詳細は LICENSE ファイルを参照)
