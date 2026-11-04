# ValueTable

**简体中文** | [English](README.en.md)

一个 Eclipse 调试插件：把调试变量或监视表达式显示成表格。这样 `List<Employee>`、`Map`、`double[][]` 这类数据可以靠滚动和排序直接看，而不必在「变量」视图里一层层展开节点。

## 功能

* **任何变量都能变表格。** 在「变量」视图、Inspect 弹窗或监视表达式里选中变量，执行 *Show as Table*（`Ctrl+Alt+V`）。数组、`Map`、集合、以及自定义对象的 `List` 都支持。
* **对象列表无需配置。** 列就是元素类型的实例字段（含父类，排除静态字段）。无参 getter 可以作为列，但需要在偏好页显式打开——每个 getter 单元格都是一次对目标 VM 的调用。
* **列选择器。** 表格窗口里的 *Columns...* 会按类型写一条规则（`com.demo.Employee#id,name,salary`），可以精确控制显示哪些成员、按什么顺序。
* **分页读取，不整体复制。** 首屏 100 × 100 个单元格，之后滚动条到达末端才读下一页。10000 × 10000 的矩阵，代价只跟屏幕上的内容有关。
* **冻结行号。** 行号是一个贴在左侧的独立表格——SWT 本身不支持冻结列。
* **PyCharm 风格着色。** 数值列按自身取值范围做红/白/蓝热力图，其他列可选斑马纹。
* **单元格编辑（默认关闭）。** *Enable editing* 默认不勾，避免误双击改了程序状态。`null` 行显示为灰色且不可编辑。
* **Excel 风格复制。** 拖出一个矩形后 `Ctrl+C`，按制表符分隔复制；没有选中时复制整张已加载的表。
* **UI 线程不做任何读取。** 每次读取都是 `Job`，并按调试目标串行化，目标慢的时候不会把 Eclipse 卡住。

## 安全

读取一个单元格可能意味着在目标 VM 里执行代码，这是唯一能让调试会话挂死的操作。插件把它当作一等公民处理：

* 方法调用可以整体关闭；
* 一份固定的阻塞/终止方法清单（`wait`、`notify`、`join`、`sleep`、`lock`、`close`、`shutdown`、`exit` 等）永不调用，且不可关闭；
* 支持用户自定义黑名单；
* 每次调用前，守卫会检查该对象的锁是否被另一个**挂起**线程持有。JDI 用 `INVOKE_SINGLE_THREADED` 求值，这种调用永远不会返回——于是跳过并报告。

被跳过的调用只会影响某个值显示成什么样，绝不会有静默行为：表格下方的警告条会写出来。

## 环境要求

* Eclipse 2023-06 或更新（开发与验证基于 2025-06），JavaSE-11 及以上
* 必须带 JDT：这是一个 JDT 调试插件，没有 JDT 无法工作

## 安装

1. 把 `com.value.table_1.0.0.jar` 放进 `<eclipse>/dropins/`。
2. 完全重启 Eclipse。（在 Eclipse 运行中替换 jar 会让那个实例出错，直到重启为止——窗口右下角显示当前 jar 的构建时间，是判断「现在跑的是哪个 jar」最快的方式。）

也可以把本工程当作插件工程导入 Eclipse，用 *Export > Deployable plug-ins and fragments* 导出。

## 使用

| 位置 | 操作 |
| --- | --- |
| 「变量」视图，右键 | *Show as Table* |
| 「变量」视图，工具栏 | ValueTable 按钮 |
| 窗口任意位置 | `Ctrl+Alt+V` |
| 「表达式」视图，双击一行 | 打开该值的表格 |
| 表格窗口，`Ctrl+C` | 复制选中的矩形区域为 TSV |
| 表格窗口，双击单元格 | 编辑（需勾选 *Enable editing*） |

## 偏好设置

`Window > Preferences > Java > Debug > ValueTable`

* **Rules** —— `class & arrayGetter & rowName & colName` 规则表。只有既不是数组、也不是 `Iterable`/`Iterator` 的类型才需要；页面里的 *Help* 说明了语法，包括用 `[m1,m2()]` 表示「一行对应这几个成员」，这正是让 `Map` 读成两列的办法。
* **Columns per type** —— 列选择器写下的那些行。故意保留成可编辑文本，方便在工作区之间复制。
* **Calls into the debug target** —— 方法调用开关、getter 列、锁守卫、黑名单、超时、行数上限。
* **Language** —— 跟随系统 / 中文 / English，启动时生效。

## 构建

本仓库只包含插件工程本身，构建走 Eclipse PDE：

1. *File > Import > Existing Projects into Workspace*，选择本目录；
2. *Project > Export > Deployable plug-ins and fragments*，导出为 jar；
3. 把 jar 放进 `<eclipse>/dropins/` 并完全重启。

作者使用的脚本化构建（编译、打包、写入构建时间、自动安装）和离线测试在开发工作区中维护，不随本仓库发布。

## 目录结构

```
src/com/value/table/
  model/                     调试模型访问、规则、分页 —— 不含任何控件
  show/                      窗口与对话框 —— 不在 UI 线程碰 JDI
  preferences/  handlers/  util/
  messages.properties        英文文案（基础 bundle）
  messages_zh_CN.properties  中文文案
plugin.properties            平台从 plugin.xml 读取的文案 —— 仅英文
help.txt / help_zh_CN.txt    规则语法说明，一种语言一个文件
icons/  META-INF/  plugin.xml  build.properties
```

## 多语言说明

1. 复制 `messages.properties` 为 `messages_<locale>.properties` 并翻译值，键名必须完全一致（离线测试会比较键集合）。
2. 复制 `help.txt` 为 `help_<locale>.txt`。
3. 如果新语言需要特殊回退处理，在 `Messages#systemLocale` 或偏好页里补上（中文就需要：所有 `zh_*` 都映射到 `zh_CN`，避免中国香港、中国澳门、中国台湾回退成英文）。

`plugin.properties` 是故意不翻译的。它由扩展注册表读取，而注册表按平台级 `-nl` 语言选择译本——既不是本插件偏好页里选的语言，也无法在不重启 IDE 的情况下改变。翻译它会出现「英文表格窗口配中文右键菜单」或反过来的割裂，所以入口保持英文、只有窗口跟随偏好。该文件必须保持 ISO-8859-1：`Properties.load` 读的是字节，不是 UTF-8。

## 已知限制

* JDI 调用没有超时、也无法取消。锁守卫能预测常见的死锁，但其他原因造成的阻塞仍会卡住它自己的那个 job——只影响那一张表，不影响 Eclipse。
* 拖动选区越过最后一行时不会继续加载。
* `List<Animal>` 里装 `Dog` 对象时只显示 Animal 的列；如果加 Dog 的列，在 Cat 行上必然是空的。

## 许可

Eclipse Public License 2.0 —— 见 [LICENSE](LICENSE)。
