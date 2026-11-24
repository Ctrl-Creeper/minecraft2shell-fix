# minecraft2shell-fix

幫你的 Minecraft 擋掉「伺服器資源包字型」攻擊 —— **正常字型看起來完全一樣**。

> ⚠️ 這個工具**還沒在真正的 Minecraft 客戶端上實測過**（作者沒有 Windows 環境），
> 目前的驗證都是在 Java 層模擬完成。請當成「先擋一下」的過渡方案，不是官方修補。

### 免責聲明（請先讀）

- **可能誤判**：正常字型若被誤認為惡意，該字型的進階繪字會被關掉，外觀可能稍微變糊。
- **可能漏判**：這是特徵偵測（認 `IDEF` 指令）。換一種寫法的新變種可能完全偵測不到，攻擊照樣成立。
- **不保證**能阻擋任何特定攻擊、不保證相容任何 Minecraft 或模組版本、不保證適合任何用途。
- **風險自負**：使用本工具所產生的一切後果（包括仍被入侵、遊戲異常、存檔或資料損毀）由使用者自行承擔，**作者不負任何責任、不提供任何形式的擔保**。
- 真正的修法是等 Minecraft 更新到已修復的繪字引擎；這個工具只是過渡。
- 若你不同意以上任何一點，請不要安裝。

---

## 中文

### 這是什麼問題（白話）

Minecraft 允許伺服器發「資源包」給玩家，裡面可以放字型檔。有人做出了攻擊：**在字型裡藏一段惡意程式，
玩家一畫到那個字，電腦就被執行任意程式**（公開示範只是開個小算盤，但同樣可以裝後門）。

問題出在遊戲畫字用的那套繪字引擎，**Minecraft 官方目前還沒修**。

### 這個工具做什麼

遊戲載入字型時會先檢查一次：

- 一般字型 → 完全照舊，外觀、字距、清晰度都不變
- 有問題的字型 → 只把那個字型的「進階繪字功能」關掉，字還是顯示，但攻擊那條路就斷了

**而且會跳一個警告視窗告訴你：**

```
檢測到伺服器的資源包疑似包含惡意內容（minecraft2shell），已經攔截。
這類字型可以在你的電腦上執行任意程式，這個伺服器送來的樣本沒有被執行。

[我了解，繼續遊玩]  [中斷連線]
```

按「中斷連線」會直接離開那個伺服器。不需要任何設定，也不影響遊戲效能。

> 視窗裡**不會**顯示「它原本要執行什麼指令」。原因很實在：公開的那支攻擊程式把指令直接寫在
> 字型的程式碼裡組出來，144 個字型裡用任何編碼（ASCII／UTF-16／拆成 4 byte）都找不到
> `calc.exe`，沒有可靠辦法反推。與其顯示一個猜的，不如不顯示。

### 安裝（選一種就好）

**原版啟動器（官方啟動器）**

1. 到 [Releases](https://github.com/sunrisemc-tw/minecraft2shell-fix/releases) 下載 `m2sfix-agent-1.0.0.jar`，記住它放在哪
2. 啟動器 →「安裝檔」→ 你的版本 →「編輯」→「更多選項」
3. 在「JVM 參數」那欄最後面加上（路徑換成你放的位置）：
   ```
   -javaagent:/你的路徑/m2sfix-agent-1.0.0.jar
   ```
4. 儲存，啟動遊戲

**Fabric**

下載 `m2sfix-mod-1.0.0.jar`，丟進 `.minecraft/mods/` 就好（不需要 Fabric API）。

**Forge / NeoForge**

同一顆 `m2sfix-mod-1.0.0.jar` 丟進 `mods/`。如果啟動器說載入失敗，改用上面的
`-javaagent` 方式，那個在任何啟動器都能用。

### 怎麼知道有沒有生效

被攔截時會跳警告視窗；日誌裡也會有一行：

```
[m2sfix] flagged font 11500c6f… (SUSPICIOUS): IDEF instruction in fpgm
```

平常什麼都沒有＝正常，代表沒遇到有問題的字型。

### 需要注意的地方

- 它只認得「已知的那一類手法」；之後如果出現寫法不同的新變種，可能抓不到
- 真正的解法是等 Minecraft 更新繪字引擎，這個工具只是過渡
- 沒有在真正的遊戲客戶端實測過（警告視窗也還沒在真遊戲裡看過）
- macOS / ARM 沒測

### 授權

MIT。自由使用、修改、再散布。

### 想更深入了解

掛點位置、偵測原理、設定選項、測試數據 → **[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)**

---

## English

A client-side mitigation for the Minecraft "server resource pack font" attack — **legitimate fonts
look exactly the same**.

> ⚠️ **Not tested against a live Minecraft client** (no Windows environment here). All verification
> is Java-level simulation. Treat it as a stopgap, not a vendor fix.

### Disclaimer (read this first)

- **False positives are possible.** A legitimate font that trips the check loses its advanced glyph
  rendering, so it may look slightly blurrier.
- **Misses are possible.** This is signature-based (it looks for the `IDEF` instruction). A variant
  written differently will not be detected and the attack still works.
- **No guarantee** of stopping any particular attack, of compatibility with any Minecraft or mod
  version, or of fitness for any purpose.
- **Use at your own risk.** Any consequence of using this tool — including still being compromised,
  game misbehaviour, or damaged worlds/data — is the user's own; the author accepts **no liability**
  and provides **no warranty** of any kind.
- The real fix is an upstream Minecraft update; this is a stopgap.
- If you disagree with any of the above, do not install it.

### The problem, in plain words

A Minecraft server can send players a resource pack that contains a font. Someone figured out how to
hide a malicious program inside such a font: **the moment the game draws a character with it, the
player's computer can be made to run arbitrary code.** The flaw is in the font engine the game uses,
and **Minecraft has not shipped a fix yet.**

### What this does

Each font is checked once when it is loaded:

- normal font → untouched; identical look, spacing and sharpness
- suspicious font → only that font loses its "advanced glyph rendering" step; text still shows, but
  the attack path is gone

**And you get a dialog:**

```
This server's resource pack looks malicious (minecraft2shell). It was blocked and did not run;
fonts like this can execute arbitrary code on the client.

[Continue]  [Disconnect]
```

"Disconnect" leaves that server immediately. No configuration, no measurable performance cost.

> The dialog deliberately does **not** show "the command it would have run". The published exploit
> builds its command inside the font's own bytecode: `calc.exe` appears in none of the 144 shipped
> fonts in any encoding (ASCII, UTF-16, 4-byte split), so there is no reliable way to recover it.
> Showing a guess would be worse than showing nothing.

### Install (pick one)

**Vanilla launcher**

1. Download `m2sfix-agent-1.0.0.jar` from [Releases](https://github.com/sunrisemc-tw/minecraft2shell-fix/releases)
2. Launcher → Installations → your instance → Edit → More options
3. Append to "JVM arguments" (use your own path):
   ```
   -javaagent:/full/path/to/m2sfix-agent-1.0.0.jar
   ```
4. Save and launch

**Fabric**

Drop `m2sfix-mod-1.0.0.jar` into `.minecraft/mods/` (no Fabric API needed).

**Forge / NeoForge**

The same `m2sfix-mod-1.0.0.jar` goes into `mods/`. If your loader refuses it, use the `-javaagent`
route above — that works with every launcher.

### Did it work?

A warning dialog appears when something is blocked, and the log shows one line per suspicious font:

```
[m2sfix] flagged font 11500c6f… (SUSPICIOUS): IDEF instruction in fpgm
```

Nothing at all is the normal case.

### Caveats

- It recognises the known family of attacks; a differently built variant may slip through
- The real fix is an upstream Minecraft update — this is a stopgap
- Not tested in a real game client (the dialog has not been seen on screen either)
- Untested on macOS / ARM

### License

MIT.

### Going deeper

Hook points, detection logic, configuration and test data → **[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)**
