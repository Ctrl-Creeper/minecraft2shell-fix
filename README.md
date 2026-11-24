# minecraft2shell-fix

A client-side mitigation for the **Minecraft font resource-pack RCE** ("minecraft2shell"):
it keeps the TrueType bytecode interpreter away from fonts that carry the exploit primitive,
**without changing how legitimate fonts render**.

> ⚠️ **Verification status: not run against a live Minecraft client.** Everything below was
> verified with Java-only simulations (JVM bytecode injection, the real LWJGL FreeType bindings,
> and the published proof-of-concept font pack) — see [Verification](#verification).

---

## English

### The bug in one paragraph

A server can send a resource pack that contains a crafted `.ttf` font. Minecraft 26.3 renders
fonts through FreeType **2.14.3** (bundled in LWJGL 3.4.3). In that build, a nested composite
glyph whose `SHZ` instruction moves points past the physical point buffer causes an out-of-bounds
write inside the TrueType interpreter (`ttinterp.c`), and the font's own bytecode (`IDEF`) turns it
into an ASLR-aware control-flow transfer. The published proof of concept is
[`bikini/minecraft2shell`](https://github.com/bikini/minecraft2shell) (Windows, Java 26.3 only).

Upstream FreeType fixed the `Ins_SHZ` range check in April 2026 (Mozilla bug 2032043) and shipped
it in FreeType **2.15.0** (2026-10-12). Minecraft still ships 2.14.3, so this project is a stopgap.

### How the fix works

The bug lives in native code, so the project does not try to patch C. It cuts the chain one layer
above, at the two Java entry points Minecraft actually uses (verified with `javap` on the 26.3
client jar):

| Minecraft call site | LWJGL method | what we do |
| --- | --- | --- |
| `TrueTypeGlyphProviderDefinition.load(...)` | `FreeType.FT_New_Memory_Face(long, ByteBuffer, long, PointerBuffer)` | lint the font bytes once per face |
| `TrueTypeGlyphProvider.loadGlyph(...)` | `FreeType.FT_Load_Glyph(FT_Face, int, int)` | add `FT_LOAD_NO_HINTING` **only for flagged faces** |

Every glyph-program execution path in `ttgload.c` is gated by `IS_HINTED(load_flags)`, so a face
loaded with `FT_LOAD_NO_HINTING` never runs the interpreter — no `SHZ`, no `IDEF`, no pivot.
Faces that were not flagged keep the caller's flags untouched, so their rendering is bit-for-bit
identical to vanilla.

The lint itself is a small TrueType table parser (`core`): it decodes the instruction streams of
`fpgm`, `prep` and every glyph (PUSH payloads are skipped correctly, so payload bytes are never
mistaken for opcodes) and looks for `IDEF` — the instruction-definition primitive that legitimate
fonts do not use and that the exploit needs to hijack an existing opcode.

### Install

**1. Vanilla launcher — javaagent**

Add to the profile's JVM arguments:

```
-javaagent:/path/to/m2sfix-agent-1.0.0.jar
```

**2. Fabric — mod jar**

Drop `m2sfix-mod-1.0.0.jar` into `.minecraft/mods/`. It contains `fabric.mod.json`, the Mixin
config and the shared core (no Fabric API dependency).

**3. Forge / NeoForge**

The same jar also carries `META-INF/mods.toml` and the `MixinConfigs` manifest entry, because the
hooks target a library class and nothing has to be remapped. If your loader refuses the jar, use the
javaagent path instead — it works on every loader.

The agent and the mod are equivalent; installing both is harmless but pointless.

### Configuration

System properties (`-D...` on the JVM command line; in modded launchers, the JVM arguments field):

| property | default | meaning |
| --- | --- | --- |
| `m2sfix.enabled` | `true` | master switch |
| `m2sfix.policy` | `hintless` | `hintless` = keep the font, drop hinting · `block` = reject the face (`FT_Err_Invalid_File_Format`) · `off` = do nothing |
| `m2sfix.unparsable` | `hintless` | what to do with a font the scanner cannot parse (`hintless` / `allow`) |
| `m2sfix.log` | `true` | one line per flagged font |
| `m2sfix.sha256.allow` | *(empty)* | comma-separated SHA-256 of fonts to always treat as clean |

### Is it working?

You should see, once per flagged font, on stderr:

```
[m2sfix] agent active (policy=HINTLESS)
[m2sfix] flagged font 11500c6f…a8f150d731 (SUSPICIOUS): IDEF instruction in fpgm (1 occurrence)
```

No line means no font was flagged — that is the normal case for a regular server.

### Verification

What was actually run on Linux (JDK 26, LWJGL 3.4.3 = the exact build Minecraft 26.3 bundles):

- **`mvn test` — 32 tests green** (24 core, 8 agent). The agent tests install the real transformer
  into a running JVM against stub classes with the exact LWJGL descriptors and assert: flagged font
  gets `flags 0 → 2`, clean font keeps `flags 0`, `block` policy rejects the face, `off` policy
  changes nothing, null/short buffers and failed face creation are ignored.
- **Real proof-of-concept fonts:** `tools/verify-poc-pack.sh` downloads the published
  `resource-pack.zip` (SHA-1 `f2e2859318152e32fc331d2291c2ec40ffb3c372`) and scans it —
  **144 / 144 fonts flagged** (`IDEF instruction in fpgm`).
- **False positives:** every font installed on this machine — **460 files, 0 flagged**.
- **Real FreeType 2.14.3, real PoC font, real agent jar:** the guard flagged the font
  (`[m2sfix] flagged font …`), the rewritten flags were observed (`0 → 2`), the glyphs then
  rendered and the process exited cleanly. The same run without the agent also completed, i.e. this
  harness does **not** reproduce the full Windows-only ROP chain — the exploit was never executed
  on purpose.

**Not verified:** a live Minecraft 26.3 client on Windows, the Fabric/Forge jar inside a real
loader, and the counterfactual ("without the fix it crashes") — deliberately not run. Treat this as
a reviewed, tested stopgap, not a vendor-certified fix.

### Scope and limits

- Signature-based: it flags fonts that carry `IDEF`. A future variant that reaches the same
  out-of-bounds write without `IDEF` would not be flagged (use `m2sfix.policy=block` for a stricter
  stance, or update FreeType).
- The real fix is upstream: FreeType ≥ 2.15.0, i.e. whatever Minecraft ships next.
- Untested on macOS/ARM; the same FreeType build is used there, so treat it the same way.

### Build

```
mvn package            # core, agent (shaded ASM), mod (core + Mixin metadata)
mvn test               # 32 tests
tools/verify-poc-pack.sh
```

Requires JDK 21+ and Maven. No Minecraft jar, no Gradle, no Loom.

### License

MIT.

---

## 中文

### 這是什麼

Minecraft 的「資源包字型」RCE（`bikini/minecraft2shell`）的**客戶端緩解方案**：
把 TrueType bytecode 直譯器擋在「帶有攻擊原語的字型」之外，**正常字型的渲染完全不變**。

> ⚠️ **驗證狀態：沒有在真正的 Minecraft 客戶端上跑過。** 所有驗證都是 Java 層模擬
> （JVM 注入、真的 LWJGL FreeType 綁定、公開的 PoC 字型包），詳見下方「驗證」。

### 漏洞一句話

伺服器可以送一個含特製 `.ttf` 的資源包。Minecraft 26.3 用 LWJGL 3.4.3 內建的 FreeType
**2.14.3** 畫字型；該版本的 `SHZ` 指令在 nested composite glyph 上會把點移出實體點緩衝區，
造成 interpreter 內的越界寫，再用字型自己的 bytecode（`IDEF`）把它變成無視 ASLR 的控制流轉移。
官方 PoC：`bikini/minecraft2shell`（只針對 Windows + 26.3）。上游 FreeType 已在 2026 年 4 月修掉
`Ins_SHZ` 的範圍檢查（Mozilla bug 2032043），並於 **2.15.0**（2026-10-12）釋出；Minecraft 還停在
2.14.3，所以這是一個過渡方案。

### 怎麼切斷

漏洞在原生層，所以不去改 C，而是在 Minecraft 真正使用的兩個 Java 入口動手
（用 26.3 client jar 的 `javap` 驗證過簽名）：

- `FreeType.FT_New_Memory_Face(long, ByteBuffer, long, PointerBuffer)` → 每個 face 只掃一次字型
- `FreeType.FT_Load_Glyph(FT_Face, int, int)` → **只對被標記的 face** 加上 `FT_LOAD_NO_HINTING`

`ttgload.c` 裡所有 glyph program 的執行路徑都被 `IS_HINTED(load_flags)` 擋著，所以關掉 hinting
就不會跑直譯器 —— 沒有 `SHZ`、沒有 `IDEF`、沒有 pivot。沒被標記的 face 一個 flag 都不動，
渲染與原版逐位元相同。

掃描器（`core`）是自己寫的 TrueType 表解析器：正確跳過 PUSH 系列指令的內嵌資料
（避免把資料 byte 誤判成 opcode），掃 `fpgm`、`prep` 與每個 glyph 的指令流，找 `IDEF`
—— 正常字型不會用這個指令，而 PoC 必須用它來覆寫既有 opcode。

### 安裝

- **原版啟動器**：在 profile 的 JVM 參數加 `-javaagent:/path/to/m2sfix-agent-1.0.0.jar`
- **Fabric**：把 `m2sfix-mod-1.0.0.jar` 丟進 `.minecraft/mods/`（自帶 `fabric.mod.json` 與 Mixin 設定，不需 Fabric API）
- **Forge / NeoForge**：同一顆 jar 也帶 `mods.toml` 與 manifest 的 `MixinConfigs`（掛點是函式庫類別，不用 remap）；若你的版本拒載，直接改用 javaagent 那條路

agent 與 mod 等價，兩個都裝不會出錯但也沒必要。

### 設定

系統屬性（`-D...`）：

- `m2sfix.enabled`（預設 `true`）：總開關
- `m2sfix.policy`（預設 `hintless`）：`hintless` 保留字型但關 hinting／`block` 直接拒收該 face／`off` 不做事
- `m2sfix.unparsable`（預設 `hintless`）：掃不動的字型怎麼處理（`hintless` / `allow`）
- `m2sfix.log`（預設 `true`）：每個被標記字型印一行
- `m2sfix.sha256.allow`：白名單（逗號分隔 SHA-256）

### 怎麼知道有沒有生效

stderr 會出現（每個被標記字型一行）：

```
[m2sfix] flagged font 11500c6f…a8f150d731 (SUSPICIOUS): IDEF instruction in fpgm (1 occurrence)
```

沒出現＝沒有任何字型被標記，這是正常伺服器的常態。

### 驗證（實際跑過的）

- `mvn test`：**32 條全綠**（core 24、agent 8）。agent 測試是真的把 transformer 裝進 JVM，
  對「簽名與 LWJGL 完全相同」的 stub 類別斷言：被標記字型 `flags 0 → 2`、正常字型維持 `0`、
  `block` 政策拒收 face、`off` 政策不動任何東西、null/短緩衝與建 face 失敗都被安全忽略。
- **真的 PoC 字型**：`tools/verify-poc-pack.sh` 抓公開的 `resource-pack.zip`
  （SHA-1 `f2e2859318152e32fc331d2291c2ec40ffb3c372`），**144/144 全數標記**
  （`IDEF instruction in fpgm`）。
- **誤判測試**：本機所有系統字型 **460 個，0 個被標記**。
- **真 FreeType 2.14.3 + 真 PoC 字型 + 真 agent jar**：守衛標記了該字型、觀測到 flags 由 `0` 改為 `2`、
  glyph 正常畫出、行程正常結束。同一支程式在**不掛 agent** 時也跑得完 —— 也就是說這支 harness
  **不會**重現那條只在 Windows 成立的 ROP 鏈（沒有刻意去觸發漏洞）。

**沒驗證**：Windows 上真正的 Minecraft 26.3 客戶端、jar 在真 Fabric/Forge loader 裡、
以及「不裝就會被打穿」的反事實對照（刻意不跑）。請把它當成「已 review 且測試過的過渡方案」，
不是官方認證的修補。

### 限制

- 這是特徵偵測：辨識 `IDEF`。若之後出現不靠 `IDEF` 的同型攻擊就抓不到（要更嚴可改
  `m2sfix.policy=block`，或等 FreeType 更新）。
- 真正的修法在上游：FreeType ≥ 2.15.0，也就是等 Minecraft 換版本。
- macOS/ARM 沒測；那裡用的是同一版 FreeType，請同標準看待。

### 建置

```
mvn package     # core、agent（shade ASM）、mod（core + Mixin metadata）
mvn test        # 32 條測試
```

需要 JDK 21+ 與 Maven；不需要 Minecraft jar、不用 Gradle、不用 Loom。

### 授權

MIT。
