# 10 分钟拿到 APK（零安装）

APK 是编译产物，必须先构建一次。这里给两条路，**路线 A 不需要在本机装任何东西**。

---

## 路线 A：GitHub 网页上传，Actions 帮你编译（推荐，全程浏览器）

需要：一个免费 GitHub 账号。

### 1. 解压
把 `AlphaRelay-src.zip` 解压到任意文件夹，能看到 `app/`、`gradle/`、`settings.gradle.kts` 这些。

### 2. 建仓库
打开 <https://github.com> → 右上角 `+` → `New repository`
- Repository name: `AlphaRelay`
- 选 `Private` 或 `Public` 都行
- 其他不要勾，点 `Create repository`

### 3. 上传文件
在新仓库页面点 `Add file` → `Upload files`，把**解压出来的所有内容**（`app`、`gradle`、`tools`、`docs`、各个 .kts/.md 文件）一起拖进去 → 点 `Commit changes`。

> ⚠️ 网页拖拽经常漏掉 `.github` 这种点开头的文件夹。上传完刷新仓库首页确认一下：如果看不到 `.github`，用 `Add file` → `Create new file`，文件名填
> `.github/workflows/build-apk.yml`，把下面这段粘进去，再 Commit：

```yaml
name: build-apk
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.7'
      - run: gradle :app:assembleDebug --no-daemon --stacktrace
      - uses: actions/upload-artifact@v4
        with:
          name: AlphaRelay-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
```

### 4. 等构建、下 APK
仓库顶部点 `Actions` → 左边选 `build-apk` → 等 3~6 分钟变成绿勾 → 点进这次运行记录 → 页面最下面 `Artifacts` → 下载 `AlphaRelay-debug-apk.zip` → 解压得到 **`app-debug.apk`**。

> 私有仓库的 Actions 有免费额度（每月 2000 分钟），这次构建用 3~5 分钟。

### 5. 装到手机
把 APK 传到手机点开安装；系统提示「未知来源」时允许一次即可。

---

## 路线 B：Android Studio（装一次，以后随时改代码）

1. 装 Android Studio，向导里勾 `Android SDK`、`SDK Platform 34`、`Build-Tools 34`
2. `File → Open` 选解压出来的 `AlphaRelay` 目录（含 `settings.gradle.kts` 那一层）
3. 等 Sync 完成（约 1~2 GB 依赖）
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)`
5. 产物：`app/build/outputs/apk/debug/app-debug.apk`

Studio 自带 JDK 17，不需要单独装 Java。

---

## 路线 C：只有浏览器、也不想用 GitHub（Google Colab）

新建一个 Colab 笔记本，上传 `AlphaRelay-src.zip`，然后依次运行：

```python
!apt-get -qq install -y openjdk-17-jdk-headless unzip
!wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline.zip
!mkdir -p /opt/android-sdk/cmdline-tools && unzip -q cmdline.zip -d /opt/android-sdk/cmdline-tools
!mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
import os; os.environ['ANDROID_HOME'] = '/opt/android-sdk'; os.environ['JAVA_HOME'] = '/usr/lib/jvm/java-17-openjdk-amd64'
!yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
!/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" > /dev/null
!unzip -q AlphaRelay-src.zip -d /content/AlphaRelay
!cd /content/AlphaRelay && wget -q https://services.gradle.org/distributions/gradle-8.7-bin.zip && unzip -q gradle-8.7-bin.zip
!cd /content/AlphaRelay && ./gradle-8.7/bin/gradle :app:assembleDebug --no-daemon
from google.colab import files
files.download('/content/AlphaRelay/app/build/outputs/apk/debug/app-debug.apk')
```

（Colab 这条路依赖 Google 的下载地址，偶尔会变；变不通就走路线 A。）

---

## 构建失败怎么办

把报错原文贴给我，我改代码。常见的几类：

| 报错 | 修法 |
| --- | --- |
| `Minimum supported Gradle version is 8.7` | 换 Gradle 8.7+ |
| `Failed to find target with hash string 'android-34'` | 装 SDK Platform 34 |
| `Unresolved reference: repeatOnLifecycle` | 别删 `lifecycle-runtime-ktx` 依赖 |
| `Android Gradle plugin requires Java 17` | Gradle JDK 设成 17 |
