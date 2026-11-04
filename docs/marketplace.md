# Publishing to Eclipse Marketplace

Marketplace does not host files. A listing is a page that points at a **p2
update site**; the *Install* button hands that URL to Marketplace Client, which
reads `content.jar` from it and installs the feature. So this repository has to
produce a p2 repository somewhere with a stable HTTPS address before a listing
means anything.

```
tag v1.0.0 ──▶ GitHub Actions: ./mvnw -f releng/pom.xml package
            ──▶ gh-pages branch: content.jar artifacts.jar features/ plugins/
            ──▶ https://qimu55kai.github.io/com.value.table/
            ──▶ marketplace.eclipse.org listing points here
```

## What is in this repository for that

| Path | Role |
| --- | --- |
| `releng/pom.xml` | reactor and parent; Tycho 4.0.13, target platform `2025-06` |
| `releng/com.value.table.feature/feature.xml` | the installable unit Marketplace Client installs |
| `releng/com.value.table.site/category.xml` | what the site offers, and how *Install New Software* groups it |
| `releng/site-index.html` | the page the site URL shows a human |
| `.github/workflows/release.yml` | build and publish on every tag |

## One-time setup

1. **Push a tag** (`v1.0.0`) or run the workflow by hand: *Actions → Release →
   Run workflow*. It builds the site and pushes it to a `gh-pages` branch,
   which is what the steps below then serve.
2. **Enable Pages**: *Settings → Pages → Source: Deploy from a branch →
   `gh-pages` / `/ (root)`*. The site is live at
   `https://qimu55kai.github.io/com.value.table/`.
3. **Check it before registering anything**: in Eclipse,
   *Help → Install New Software… → Add*, paste that URL, and install
   **ValueTable**. If this fails, a Marketplace listing would fail the same way.

## Listing fields

| Field | Value |
| --- | --- |
| Title | `ValueTable` |
| Version | `1.0.0` (must match `Bundle-Version`) |
| Update site | `https://qimu55kai.github.io/com.value.table/` |
| License | Eclipse Public License 2.0 |
| Categories | Debug, Tools |
| Tags | `debug`, `table`, `variables`, `watch`, `jdt`, `matrix`, `list`, `map` |
| Company / author | `qimu55kai` |
| Source | `https://github.com/qimu55kai/com.value.table` |
| Support | `https://github.com/qimu55kai/com.value.table/issues` |
| Logo | `docs/marketplace/logo-128.png` |

## Short description

**English**

> Reads a debug variable or a watch expression as a table: lists of objects,
> maps, and multi-dimensional arrays, paged so a 10000 x 10000 matrix costs only
> what is on screen.

**中文**

> 把调试变量或监视表达式显示成表格：对象列表、Map、多维数组都支持；分页读取，10000×10000 的矩阵也只付出屏幕上那部分的代价。

## Long description (HTML)

**English**

```html
<p>Expanding nodes in the Variables view works until the data is a matrix or a
list of a few thousand rows. <b>ValueTable</b> shows the same variable as a
table you can scroll, sort with your eyes, and copy out.</p>

<h3>What it does</h3>
<ul>
  <li>Arrays, <code>Map</code>, collections, and lists of your own objects -
      <i>Show as Table</i> in the Variables view, or <code>Ctrl+Alt+V</code>.</li>
  <li>Columns are the element type's instance fields; no configuration needed.
      A column picker writes one rule per type when the defaults are not what
      you want.</li>
  <li>Paged: 100 x 100 cells first, the next page when a scroll bar reaches its
      end. A 10000 x 10000 matrix costs what is on screen.</li>
  <li>Range-based red/white/blue heat map on numeric columns, frozen row labels,
      rectangle copy as TSV, opt-in cell editing.</li>
  <li>Nothing runs on the UI thread - every read is a job, serialised per debug
      target.</li>
</ul>

<h3>It does not hang your debug session</h3>
<p>A getter column is a call into the target VM, which is the one thing that can
freeze a debugger. Calls can be switched off entirely; a fixed list of blocking
methods is never called and cannot be disabled; before each call the guard
checks whether a suspended thread already owns the object's monitor, because
that call could never return. A skipped call is reported under the table, never
silently.</p>

<h3>Requirements</h3>
<p>Eclipse 2023-06 or newer with JDT (this is a JDT debug plug-in), Java 17+.</p>

<h3>Install</h3>
<p>Drag this listing onto a running Eclipse, or add
<code>https://qimu55kai.github.io/com.value.table/</code> in
<i>Help &gt; Install New Software…</i>.</p>
```

**中文**

```html
<p>在「变量」视图里一层层展开节点，遇到矩阵或几千行的列表就不太行了。<b>ValueTable</b> 把同一个变量显示成可以滚动的表格。</p>

<h3>功能</h3>
<ul>
  <li>数组、<code>Map</code>、集合、自定义对象的列表都支持 —— 变量视图里的
      <i>Show as Table</i>，或 <code>Ctrl+Alt+V</code>。</li>
  <li>列就是元素类型的实例字段，无需配置；默认不合适时，列选择器会按类型写规则。</li>
  <li>分页读取：首屏 100×100 个单元格，滚动条到底再读下一页。10000×10000 的矩阵也只付出屏幕上那部分的代价。</li>
  <li>数值列按取值范围做红/白/蓝热力图，行号冻结，矩形区域复制为 TSV，单元格编辑默认关闭。</li>
  <li>UI 线程不做任何读取，每次读取都是按调试目标串行化的 job。</li>
</ul>

<h3>不会卡死调试会话</h3>
<p>getter 列意味着在目标 VM 里执行代码，这是唯一能让调试器冻住的操作。方法调用可以整体关闭；一份固定的阻塞方法清单永不调用且不可关闭；每次调用前守卫会检查对象锁是否被挂起线程持有——那种调用永远不会返回。被跳过的调用会写在表格下方的警告条里，不会静默。</p>

<h3>环境要求</h3>
<p>Eclipse 2023-06 或更新，必须带 JDT，Java 17+。</p>

<h3>安装</h3>
<p>把本条目拖到运行中的 Eclipse，或在 <i>Help &gt; Install New Software…</i> 里添加
<code>https://qimu55kai.github.io/com.value.table/</code>。</p>
```

## Screenshots

Marketplace shows them at 600 px wide; capture at 2x and scale down, in the
light theme, with the window maximised enough to show a full page.

1. **The table itself** - a `List<Employee>` or a numeric matrix, showing the
   frozen row labels, the heat map, and the horizontal scroll bar. This is the
   one that has to sell it.
2. **The way in** - Variables view with a variable selected and the context menu
   open on *Show as Table*.
3. **Columns...** - the per-type rule dialog, so the "no configuration needed,
   but it is configurable" claim is visible.

## Releasing a new version

1. Bump `Bundle-Version` in `META-INF/MANIFEST.MF`, the `<version>` in the root
   `pom.xml`, and the `version` in `releng/com.value.table.feature/feature.xml`.
   All three, or the site and the Marketplace entry disagree.
2. `git tag v1.0.1 && git push origin v1.0.1` - the workflow rebuilds and
   replaces the site contents.
3. Edit the listing on Marketplace and change the version field. The update site
   URL does not change, so existing users get the update from
   *Help → Check for Updates*.

## Notes

* The site holds one release at a time. p2 does not need older ones, and a
  repository that only grows is one nobody prunes.
* The jars are unsigned. Marketplace does not require signing, but some
  corporate Eclipse installs do - if that comes up, add
  `tycho-gpg-plugin` to `releng/pom.xml` rather than signing by hand.
