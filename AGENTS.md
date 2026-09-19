# Repository Guidelines

## 项目结构与模块组织

本仓库是企业微信智能机器人 WebSocket 长连接 Java SDK，采用单模块 Maven 布局。

- `src/main/java/com/wecom/aibot/`：客户端、连接管理、事件分发、流式会话、配置及媒体工具。
- `src/main/java/com/wecom/aibot/model/`：消息、事件和模板卡片的数据模型。
- `src/main/java/com/wecom/aibot/example/Main.java`：可运行的机器人示例。
- `pom.xml`：依赖与编译配置；`README.md`：公共 API 用法；`target/`：生成的构建产物。

当前没有测试目录或独立资源目录。新增测试放在 `src/test/java/`，并保持与被测类相同的包结构。

## 构建、测试与本地开发

使用 Java 8+ 和 Maven 3.6+，在仓库根目录执行：

- `mvn clean compile`：清理并编译源码，目标字节码版本为 Java 8。
- `mvn test`：运行测试生命周期；当前没有自动化测试，成功退出不代表行为已验证。
- `mvn clean package`：生成 SDK JAR 和源码 JAR，输出到 `target/`。
- `mvn install`：将构建结果安装到本地 Maven 仓库，供其他项目引用。
- `mvn compile exec:java -Dexec.mainClass=com.wecom.aibot.example.Main`：通过 Maven Exec 插件运行示例；该插件未在 POM 中预配置，首次执行可能需要下载。

示例运行前设置 `WECHAT_BOT_ID`、`WECHAT_BOT_SECRET`，并确保可以访问企业微信 WebSocket 服务。

## 编码风格与命名约定

采用 UTF-8、四个空格缩进和同行左花括号，保持 Java 8 语法兼容性。类名使用 `UpperCamelCase`，方法和字段使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。公共 API 使用 Javadoc 说明参数、异常和生命周期；注释沿用中文风格。

协议字段通过 Jackson 注解映射，修改模型时保留企业微信要求的 JSON 字段名。涉及连接、回调或请求跟踪的修改应保持线程安全与关闭操作的幂等性。当前没有配置格式化或静态检查工具。

## 测试指南

当前 POM 没有测试框架依赖，也没有覆盖率要求。引入自动化测试时，在 `pom.xml` 中明确配置框架及所需插件，测试类采用 `*Test` 命名，例如 `OptionsTest`。

优先验证凭证校验、JSON 序列化、事件注销、断线重连、流式超时和媒体解密边界。单元测试使用模拟服务或固定数据；需要真实凭证的联调单独记录。提交时说明执行的命令、结果和未验证路径。

## 提交与 Pull Request

现有提交采用类型前缀和中文摘要，例如 `feat: 企业微信智能机器人 Java SDK 初始版本`、`fix: 修复 code review 发现的 7 个问题`。沿用 `类型: 简明说明`，每次提交聚焦一个改动。

PR 描述应包含问题、行为变化、兼容性影响及验证结果；有关联 issue 时附上链接。公共 API 或配置发生变化时同步更新 `README.md` 和示例。协议问题可附脱敏消息样例或日志。

## 安全与代理协作

凭证使用环境变量或 `Options.setBotIdFile()`、`setSecretFile()` 加载；凭证文件权限建议为 `600`。真实密钥、凭证文件和未脱敏日志不得提交，日志输出沿用现有脱敏方式。

代理与贡献者沟通时始终使用中文。
