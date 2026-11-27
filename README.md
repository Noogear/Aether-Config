# aether-config

基于 **Java 21** 的现代化配置框架，面向通用 Java 应用与 Minecraft（Paper / Folia）插件生态设计。

融合了**不可变 `record` 快照**、**单趟 SnakeYAML AST（保留行号与注释）**、**声明式绑定与命令式视图混用**、**精准源码定位诊断**、**原子热重载回滚**、**多文件目录加载**以及**函数式版本迁移链**。

---

## 目录

- [模块结构与体积](#模块结构与体积)
- [安装引入](#安装引入)
- [快速上手](#快速上手)
- [注解参考手册](#注解参考手册)
- [内置类型与自定义 Codec](#内置类型与自定义-codec)
- [复杂结构实战](#复杂结构实战)
  - [1. 自定义物品配置（简写 / 完整双形态）](#1-自定义物品配置简写--完整双形态)
  - [2. 多层嵌套与动态键（套娃结构）](#2-多层嵌套与动态键套娃结构)
  - [3. Map / List / MapList 与多态列表](#3-map--list--maplist-与多态列表)
- [目录级配置（DirectoryConfig）](#目录级配置directoryconfig)
- [环境变量与跨键引用插值](#环境变量与跨键引用插值)
- [版本检查与函数式迁移链](#版本检查与函数式迁移链)
- [原子热重载与诊断输出](#原子热重载与诊断输出)
- [Paper / Adventure 扩展](#paper--adventure-扩展)
- [构建与发布](#构建与发布)

---

## 模块结构与体积

| 模块 | 职责 | 外部运行期依赖 |
| :--- | :--- | :--- |
| `aether-config-core` | 核心引擎：AST 节点树、诊断系统、`TypeRef`/`CodecRegistry`、Record/POJO 绑定、`ConfigHandle`、`DirectoryConfig`、插值、迁移链 | **零外部依赖**（仅编译期 `jetbrains-annotations`） |
| `aether-config-yaml` | 基于 SnakeYAML 2.2 `compose`/`serialize` 的 YAML 适配器（单趟解析保留行列号、注释与引号样式） | `org.yaml:snakeyaml:2.2` |
| `aether-config-paper` | Paper / Bukkit / Adventure 可选扩展（`Material`、`Particle`、`NamespacedKey`、`Component`、`Color`、`Sound` 等） | `paper-api` (`compileOnly`) |

---

## 安装引入

先在本地发布或通过 Composite Build（`includeBuild`）接入：

```groovy
dependencies {
    implementation 'com.aether:aether-config-core:2.0.0-SNAPSHOT'
    implementation 'com.aether:aether-config-yaml:2.0.0-SNAPSHOT'

    // 可选：Paper / Folia 插件扩展
    implementation 'com.aether:aether-config-paper:2.0.0-SNAPSHOT'
}
```

> **提示**：Paper / Folia 服务端运行时已自带 SnakeYAML。在插件 `shadowJar` 打包时可按需排除 `org.yaml:snakeyaml` 以进一步减小插件体积。

---

## 快速上手

### 1. 用 Java `record`（或普通 POJO）声明配置结构

```java
import com.aether.config.annotation.*;
import java.time.Duration;

@Header({
    "MyApp 主配置文件",
    "支持修改后热重载"
})
@Version(value = 2, key = "config-version", autoBackup = true)
public record AppConfig(
        @Comment("数据库连接设置")
        DatabaseConfig database,

        @Comment("定时任务间隔")
        @Inline("支持 10 ticks = 0.5 秒")
        @Range(min = 1, max = 72000, clamp = true)
        @Default("10")
        int intervalTicks,

        @Default("30s")
        Duration timeout,

        @NonBlank
        @Pattern("^[a-z0-9_]+$")
        @Default("app_")
        String tablePrefix
) {
    public record DatabaseConfig(
            @NonBlank @Default("localhost") String host,
            @Range(min = 1, max = 65535, clamp = false) @Default("3306") int port
    ) {}
}
```

### 2. 创建 `ConfigManager` 并加载

```java
ConfigManager manager = ConfigManager.builder()
        .format(YamlFormat.create())
        .syncMode(SyncMode.PATCH_MISSING)       // 首次生成带注释默认文件；升级时只补全缺失键并保留用户注释
        .unknownKeys(UnknownKeyPolicy.WARN)     // 未知键发告警（含拼写建议），不误删用户数据
        .interpolation(InterpolationMode.ALL)   // 启用 ${env:...} / ${ref:...} 插值
        .build();

ConfigHandle<AppConfig> handle = manager.load(AppConfig.class, dataDir.resolve("config.yml"));
AppConfig cfg = handle.get();

// 若有警告或错误，可打印结构化报告
if (!handle.lastDiagnostics().isEmpty()) {
    System.out.println(handle.lastDiagnostics().formatAll());
}
```

首次运行将自动生成带完整注释的 YAML：

```yaml
# MyApp 主配置文件
# 支持修改后热重载
# 数据库连接设置
database:
  host: localhost
  port: 3306
# 定时任务间隔
interval-ticks: 10 # 支持 10 ticks = 0.5 秒
timeout: 30s
table-prefix: app_
config-version: 2
```

### 3. 兼容传统可变 POJO 与生命周期钩子

如果不方便使用 `record`，任何带无参构造函数的普通类都可直接绑定（无需强制继承基类），字段初始值即为默认值：

```java
public class LegacySettings {
    @Comment("显示名称")
    private String title = "默认名称";

    @Range(min = 1, max = 6, clamp = true)
    private int rows = 3;

    @Ignore
    private transient int computedSlots;

    @PreLoad
    void beforeBind() { /* 反序列化前重置内部集合等 */ }

    @PostLoad
    void afterBind() {
        this.computedSlots = rows * 9;
    }
}
```

---

## 注解参考手册

| 注解 | 适用目标 | 说明 |
| :--- | :--- | :--- |
| `@Header({"..."})` | 类 / Record | 在配置文件顶部生成文件头注释 |
| `@Comment({"..."})` | 字段 / Record 组件 | 在配置键上方生成块注释 |
| `@Inline({"..."})` | 字段 / Record 组件 | 在配置键或标量值右侧生成行内注释 |
| `@Key("custom-key")` | 字段 / Record 组件 | 显式覆盖键名（跳过 `NamingStrategy` 转换） |
| `@Default("...")` | 字段 / Record 组件 | Record 组件缺失时的默认文本值（经由 `CodecRegistry` 解码） |
| `@Range(min, max, clamp = true)` | 数值字段 / 组件 | 数值范围校验；`clamp = true` 自动截断并警告，`false` 报 `ERROR` |
| `@NonBlank` | `String` 字段 / 组件 | 不允许为 `null` 或空白字符串 |
| `@Pattern(value, message)` | `String` 字段 / 组件 | 正则表达式校验 |
| `@Check(MyValidator.class)` | 字段 / 组件 | 自定义实现 `Check.ConfigValidator<T>` 的校验与修正逻辑 |
| `@Ignore` | POJO 字段 | 忽略该字段，不参与读取与写回 |
| `@Version(value, key, autoBackup)` | 根类型 / 字段 / 组件 | 声明式架构版本号与自动备份开关 |
| `@DefaultResources({"..."})` | 目录条目类型 | 为 `DirectoryConfig` 声明默认释放的 Classpath 资源路径 |
| `@PreLoad` / `@PostLoad` | 方法 | 绑定前/后的生命周期钩子（支持无参、`ConfigDiagnostics` 或自定义上下文参数） |

---

## 内置类型与自定义 Codec

### 内置支持类型
* **基础类型**：`String`（允许空串，非空用 `@NonBlank`）、`boolean`/`Boolean`、`int`/`Integer`、`long`/`Long`、`short`/`Short`、`byte`/`Byte`、`float`/`Float`、`double`/`Double`、`BigInteger`、`BigDecimal`
* **常用标准类型**：`UUID`、`Pattern`、`Path`、`URI`、`Duration`（支持 `500ms`、`10s`、`5m`、`2h`、`1d`、`20t`/`ticks` 与 `PT10S`）
* **泛型自动合成**：任意 `Enum`（忽略大小写并容忍 `-` 与 `_`）、`Optional<T>`、`List<T>`、`Set<T>`、`Map<K, V>`（保留顺序且支持多层嵌套泛型，如 `Map<String, List<Integer>>`）
* **树视图直通**：`ConfigSection`、`ConfigNode`

### 自定义双向 Codec 与多态组合子 `oneOf`

```java
public record RangeSpec(int x, int y, int z) {
    public static final ConfigCodec<RangeSpec> CODEC = ConfigCodec.oneOf(
            // 变体 1：标量简写 range: 7
            val -> {
                int r = val.requireInt();
                return new RangeSpec(r, r, r);
            },
            // 变体 2：三维对象 range: { x: 8, y: 64, z: 8 }
            val -> {
                ConfigSection sec = val.asSection();
                if (sec.isEmpty()) {
                    throw new ConfigDecodeException("expected range scalar or {x, y, z}");
                }
                return new RangeSpec(
                        sec.getInt("x", 5, 0, 512),
                        sec.getInt("y", 5, 0, 512),
                        sec.getInt("z", 5, 0, 512)
                );
            }
    );
}
```

---

## 复杂结构实战

### 1. 自定义物品配置（简写 / 完整双形态）

```java
public record ItemSpec(
        Material material,
        @Range(min = 1, max = 64) @Default("1") int amount,
        Optional<Component> name,
        List<Component> lore,
        @Key("custom-model-data") Optional<Integer> customModelData,
        Map<String, Integer> enchantments,
        ConfigSection extra // 开放式扩展字段（如 PDC / 自定义属性）
) {}
```

若只需完整对象格式，直接作为 `record` 即可自动绑定；若希望同时支持 `reward: DIAMOND` 与 `{ material: DIAMOND_SWORD, name: ... }`，可注册 `ConfigCodec.oneOf(...)` 接入 `ConfigManager.builder().registerCodec(ItemSpec.class, ...)`。

### 2. 多层嵌套与动态键（套娃结构）

* **层数固定、键名动态**（如 `categories.<id>.items.<id>.tiers.<tier>`）：直接嵌套 `Map<String, SubRecord>`。
* **无限层级递归树**（如菜单树、权限树）：直接用自引用 `record`：
  ```java
  public record MenuNode(@Default("") String title, Map<String, MenuNode> children) {}
  ```
* **完全自由的动态结构**：在 `record` 中嵌入 `ConfigSection` 字段，或直接使用命令式 `ConfigSection` 递归解析：
  ```java
  void walk(ConfigSection sec) {
      for (String key : sec.keys()) {
          if (sec.isSection(key)) {
              walk(sec.section(key));
          } else if (sec.isList(key)) {
              List<ConfigSection> list = sec.getSectionList(key);
          } else {
              // 键名本身带点号（如 "1.21.4"）也可用 valueExact 安全读取
              String val = sec.valueExact(key).asString("");
          }
      }
  }
  ```

### 3. `Map` / `List` / `MapList` 与多态列表

```java
// 1. 声明式 Record 直接绑定各种泛型与 MapList
public record RewardConfig(
        Map<String, List<Integer>> dropTables,
        List<Map<String, Object>> rawActions,
        @Key("raw-actions") List<ConfigSection> actionSections, // 推荐：保留源码位置与校验能力
        List<Action> typedActions                               // 推荐：强类型多态列表
) {}

// 2. 命令式 ConfigSection 读取
List<ConfigSection> sections = root.getSectionList("raw-actions");
Map<String, List<Integer>> drops = root.decode(
        "drop-tables", new TypeRef<Map<String, List<Integer>>>() {}, Map.of()
);
List<Action> actions = root.decodeList("typed-actions", Action.CODEC);
```

---

## 目录级配置（DirectoryConfig）

适合加载 `chests/*.yml`、`items/**/*.yml` 等模块化配置目录，支持**反射绑定模式**和**工厂模式**，并内置增量文件缓存（未改动文件不重复解析）：

```java
// 模式 A：反射自动绑定 + 查重跳过 + 默认模板
DirectoryConfig<ItemSpec> items = manager.directory(dataDir.resolve("items"))
        .bind(ItemSpec.class)
        .rootKey("items")                                             // 仅读取顶层 items 节点，跳过无该键的文件
        .recursive()                                                  // 递归扫描子目录
        .duplicatePolicy(DirectoryConfig.DuplicateKeyPolicy.ERROR_AND_SKIP) // 重复 ID 报错并跳过（也支持 LAST_WRITE_WINS / KEEP_FIRST）
        .defaults(PaperDiagnostics.resourceProvider(plugin),
                  DirectoryConfig.DefaultSeedPolicy.ONLY_WHEN_EMPTY,
                  "items/starter.yml")
        .load();

ItemSpec item = items.get("my_sword");

// 模式 B：自定义 Factory（适合多 pass 依赖解析或复杂分发）
DirectoryConfig<MyProfile> profiles = manager.directory(dataDir.resolve("profiles"))
        .factory((id, section) -> MyProfileParser.parse(id, section))
        .rootKey("profiles")
        .recursive()
        .load();

// 增量热重载（仅重解析修改/新增/删除的文件）
ReloadResult<Map<String, ItemSpec>> result = items.reload();
```

---

## 环境变量与跨键引用插值

通过 `InterpolationMode` 开启加载期 `${...}` 占位符解析（不影响业务常用的单花括号运行期占位符如 `{player}`）：

* `${env:DB_HOST:localhost}`：读取环境变量 `DB_HOST`，未设置时回退到 `localhost`
* `${sys:server.port:25565}`：读取 JVM 系统属性
* `${ref:database.host:localhost}`：引用同一配置文档中的其他节点（`InterpolationMode.ALL`）
* `${NAME:fallback}`：依次尝试同文档键 → 环境变量 → 系统属性 → 默认值
* `\${LITERAL}`：转义输出字面量 `${LITERAL}`

> **安全性保证**：插值只发生在内存解码视图中，执行 `PATCH_MISSING` 补全磁盘文件时**不会把密钥明文写回 YAML**，原文件中的 `${env:...}` 占位符会完整保留。

---

## 版本检查与函数式迁移链

使用 `@Version` 搭配 `MigrationChain` 在保留用户注释与数据的前提下完成结构演进：

```java
MigrationChain migrations = MigrationChain.builder()
        .step(1, 2, doc -> {
            doc.rename("old-db-host", "database.host");
            doc.remove("deprecated-flag");
        })
        .step(2, 3, doc -> doc.copy("database.host", "replica.host"))
        .build();

ConfigManager manager = ConfigManager.builder()
        .format(YamlFormat.create())
        .migrations(AppConfig.class, migrations)
        .build();
```

当磁盘文件版本低于 Schema 版本时：
1. 自动将旧文件备份到同级 `backups/` 目录（如 `backups/config_v1_20261127_133000.yml`）；
2. 按版本号顺次在 `ConfigDocument` AST 上执行迁移步骤（保留注释）；
3. 更新版本号并结合 `PATCH_MISSING` 补全新增键的默认值与注释。

---

## 原子热重载与诊断输出

`ConfigHandle<T>` 提供无锁读、线程安全原子切换以及变更回调：

```java
handle.onReload((oldCfg, newCfg) -> {
    logger.info("端口已变更为: " + newCfg.database().port());
});

// 全量重载（若 YAML 语法错误或严格校验失败，自动回滚保留旧快照）
ReloadResult<AppConfig> res = handle.reload();

// 智能重载（对比 mtime 与文件大小，未变动直接跳过磁盘读取与解析）
ReloadResult<AppConfig> smart = handle.smartReload();

// 异步重载
handle.reloadAsync().thenAccept(r -> { ... });

// 代码修改后保存回磁盘（合并保留原有注释）
handle.save(modifiedConfig);
```

所有错误与警告都会携带 **1-based 行列号、源码上下文箭头及拼写建议**：

```text
[WARN] [Schema] config.yml:9:1 (typo-setting)
  9 | typo-setting: true
    | ^ Unknown configuration key 'typo-setting'.
  Hint: Did you mean 'table-prefix'?
```

---

## Paper / Adventure 扩展

引入 `aether-config-paper` 后可直接使用：

```java
ConfigManager manager = ConfigManager.builder()
        .format(YamlFormat.create())
        .codecs(PaperCodecs::registerDefaults)
        .build();
```

* **`PaperCodecs` 内置 Codec**：`Material`、`Particle`、`NamespacedKey`、Adventure `Component`（MiniMessage 双向序列化）、`TextColor`、Bukkit `Color`（支持 `#RRGGBB` / `#AARRGGBB` / `r,g,b` / `transparent`）、`Sound`、`PotionEffectType`
* **`PaperDiagnostics`**：`PaperDiagnostics.flush(diagnostics, plugin.getComponentLogger())` 与 `PaperDiagnostics.send(diagnostics, sender)`（将着色诊断信息直接发给执行 `/reload` 的管理员）
* **`PaperDiagnostics.resourceProvider(plugin)`**：一键适配 `Plugin::getResource`

---

## 构建与发布

```powershell
# 运行全模块单元测试与构建检查
.\gradlew.bat clean check

# 发布到本地 Maven 仓库 (~/.m2)
.\gradlew.bat publishToMavenLocal
```
