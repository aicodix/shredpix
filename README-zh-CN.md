# Shredpix 简体中文资源（v1.22）

此提交为 Shredpix 官方 v1.22 增加简体中文 Android 字符串资源，未修改传输算法或其他运行逻辑。

## 文件
- `app/src/main/res/values-zh-rCN/strings.xml`：简体中文字符串资源。

## 验证状态
- 根据官方 v1.22 的 `values/strings.xml` 对照资源名称及数组条目。
- 需要在完整 Android 工程中执行 Gradle 构建并安装到设备验证；本次提交未声称已完成 APK 构建或真机测试。

## 说明
- 资源目录采用 Android 的简体中文（中国）限定符 `values-zh-rCN`。
- Android 会按系统语言自动选择匹配的本地化资源；设备语言不是简体中文时会回退到默认语言。
