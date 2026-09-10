# CLAUDE.md

本文件为 Claude Code 在本仓库中工作时提供指引。

## 项目概览

- 类型：Maven 单模块 Java 项目（JDK 8）
- 根包：`com.qian.categorize`
- 源码：Java 8 语法，编码 UTF-8
- 测试：JUnit 5（Jupiter）

## 常用命令

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 清理后重新构建（切换 JDK 或出现 class 版本冲突时先 clean）
mvn clean test

# 打包到 target/
mvn package

# 安装到本地 Maven 仓库
mvn install
```

注意：本机 `mvn` 默认运行在 JDK 8 上；如需用更高版本 JDK 构建，显式指定：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
```

## 目录结构

```
src/main/java/com/qian/categorize/   # 生产代码
src/main/resources/                  # 配置与资源文件
src/test/java/com/qian/categorize/   # 测试代码
src/test/resources/                  # 测试资源
target/                              # 构建输出（不提交）
```

## 编码约定

- 新类放在根包 `com.qian.categorize` 下，按功能建子包。
- 测试类与被测类同包路径，命名以 `Test` 结尾。
- 不要提交 `target/` 目录。
- 代码缩进使用 4 个空格。

## JDK 8 限制

- 不能使用 Java 9+ API（如 `List.of`、`var`、`Record`、`Stream.toList` 等）。
- pom 中编译配置使用 `source`/`target`，不要使用 JDK 9+ 才支持的 `maven.compiler.release`。
- 新增依赖前确认其支持 Java 8。
