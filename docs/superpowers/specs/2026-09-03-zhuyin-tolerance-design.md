# 注音容錯與組字修正設計

日期：2026-09-03（延伸 2026-08-30 注音輸入法）

## 目標

改善注音實際輸入時的兩個痛點：
1. **相鄰鍵誤觸**：大千鍵盤鍵窄（首列 11 鍵），常按到隔壁鍵——在 Rime 拼寫層加入鍵盤幾何感知的容錯。
2. **組字中不好修正**：目前組字中按方向鍵會把組字上屏——改為在預編輯內移動游標、就地修正。

## 元件

### 1. 相鄰鍵容錯（Rime fuzz 規則）

- `ZhuyinKeymap` 新增：
  ```kotlin
  /** 大千鍵盤的物理網格（與 zh_bopomofo.yaml 的行對應） */
  private val KEY_GRID = listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./")
  /** 同行相鄰的鍵對（無序、去重、只含 RIME_ALPHABET 內的鍵，不含空白） */
  fun adjacentKeyPairs(): List<Pair<Char, Char>>
  ```
  只取同行相鄰 `(i, i+1)`；原先納入的上下鄰近（半鍵位錯位對齊）已依真機實測拿掉，詳見下方「真機實測記錄」。
  純函式，可單測（代表對：'1'-'2'、'u'-'i'、'.'-'/'、'0'-'-'；非相鄰對：'1'-'0'、'q'-'l'；上下鄰近對現在應斷言為 false）。
- `ChineseIMESettings` 新增 `ZhuyinAdjacentToleranceSetting = SettingsKey(booleanPreferencesKey("ChineseIME_zhuyin_adjacent_tolerance"), false)`（預設關閉），
  中文設定頁加開關（`userSettingToggleDataStore`，字串 `chinese_setting_zhuyin_tolerance`）。
- `ChineseIME.writeCustomizationFile`：`Configuration` 增加 `zhuyinTolerance: Boolean` 欄位；當 schema 以 `futo_bopomofo` 開頭且開啟時，寫入：
  ```yaml
      speller/algebra/+:
          - fuzz|^([^~]*)x([^~]*)$|${1}~y${2}|
          - fuzz|^([^~]*)y([^~]*)$|${1}~x${2}|
          ...（每個相鄰鍵對兩條，方向各一）
          - xform|~||
  ```
  fuzz 產生的拼寫品質低於精確拼寫，候選排名靠後，不影響正常輸入。
- **每個音節只容許一次替換（marker 機制）**：Rime 的拼寫代數會把每條規則套用到*所有既有拼寫，包含先前推導出來的*。
  若寫成單純的 `fuzz|x|y|`，替換會沿著相鄰鍵圖鏈式傳遞（u→i，推導出的 i 又命中 i→o…）並在各個位置上相乘，
  規則數與推導拼寫數爆炸——真機上 `rime::SchemaUpdate::Run` 在 RSS 衝到 1.3 GB 後 SIGABRT，輸入法陷入「部署→崩潰→重啟」迴圈。
  因此每次替換都插入 marker 字元 `~`（不在 speller alphabet 內、也不是正規表達式的元字元），
  而所有 pattern 都以 `[^~]*` 排除它：已含 marker 的拼寫再也命中不了任何 fuzz 規則，故**一個拼寫最多只會被替換一次**。
  所有 fuzz 規則之後再附一條 `xform|~||` 把 marker 去掉，推導出的拼寫仍是真實按鍵序列。
  `${1}`/`${2}` 分組引用在本版 Rime 可用（隨附的 `luna_tones` 規則即使用 `${1}1` 並能正常部署）。
- 部署代價：真機驗收標準：**部署不崩潰**、詞庫部署時間 ≤ 15 秒且正常拼寫的首選不變；超標則退化為「只做同行相鄰」（見下方實測記錄，此退化已套用為正式行為）。

#### 真機實測記錄

用完整鄰居集合（同行 + 錯位下一行，共 95 對）部署：沒有崩潰，但建置耗時約 2 分 10 秒、prism 檔案 26.7 MB，
且正常輸入時汙染了首選候選——例如 ㄋㄧˇ 的候選列前段出現「那／哪／裏／里」；一次誤觸的救援候選則被埋在數十個精確匹配之下，
使用者幾乎看不到它。三項都不可接受，因此改為只用同行相鄰鍵對（KEY_GRID 各列不變，只砍掉跨列規則），
並把 `ZhuyinAdjacentToleranceSetting` 預設改為關閉，直到有更小的規則集或排序調整能解決汙染問題。

### 2. 組字中游標修正（IME navigator）

- `ChineseIME.onEvent` 的 `EVENT_TYPE_DOWN_UP_KEYEVENT` 分支：在 `isBopomofo && rawInput.text.isNotEmpty()`（以 rawInput 判組字，與按鍵協程 FIFO 一致）時：
  - `KEYCODE_DPAD_LEFT` → `coroScope.launch { rime.processX11Code(XK_Left) }`，`return`；
  - `KEYCODE_DPAD_RIGHT` → 同理 `XK_Right`；
  - 其他鍵維持既有行為（`interruptInput()` + `sendDownUpKeyEvent`）。
- X11 keysym：`icu.astronot233.rime.X11Keys.XK_Left` / `XK_Right`（已確認存在於 aar）。
- librime 的 `navigator` processor（方案 engine 已含）會在預編輯內移動游標；退格與新輸入自然發生在游標處。
- `rawInput` 的限制：`rawInput` 假設游標在尾端。游標離開尾端後 `rawInput` 與實際輸入序列不再一致——
  處理：按下 LEFT 時 `rawInput.clear()` 並記 `caretAdjusted = true`；此後：
  - `handleBopomofoKey` 的「是否組字中」一律改看 `rime.preeditFlow.value`，且不再更新 `rawInput`；
    空白鍵在組字中一律視為一聲（不做「已有聲調→選字」判斷）；聲調鍵只在完全沒有組字時忽略。
  - 預編輯浮窗的 `normalized` 傳入哨兵字元 `"\uFFFF"`，使浮窗的可編輯門檻不通過（游標模式下以鍵盤就地修正為主）。
  - `caretAdjusted` 在 preedit 清空（`subscribeToRimePreedit`）與 `onFinishInput`/`interruptInput` 時復位。
  方向鍵攔截的組字判斷用 `rawInput.text.isNotEmpty() || rime.preeditFlow.value.isNotEmpty()`（LEFT 清掉 rawInput 後仍可繼續用方向鍵）。
- 非 bopomofo hint 的行為不變。

## 測試

- 單元：`adjacentKeyPairs` 對稱、無重複、不含空白、同行代表對抽查（'1'-'2'、'u'-'i'、'.'-'/'、'0'-'-'）、
  下一行對現在應為 false（'u'-'j'、'u'-'h'、'-'-'p'、'2'-'q'）；
  fuzz 規則文本生成（給定小鄰居表 → 期望 yaml 行）。
- 真機（已依「真機實測記錄」改為只驗證同行相鄰的縮小規則集，開關預設關閉，需手動開啟後測試）：
  1. 想按 ㄧ(`u`) 誤按同排的 `t`(ㄔ)：帶 ㄧ 的字仍出正確候選（排名靠後但可見）；關閉開關後不再出現。
  2. 部署時間：開啟開關後首次喚起 ≤ 15 秒完成（logcat Deploy 間隔）——此為縮小後的同行規則集（約一半的鍵對數）下的驗收標準，全鄰居集合（同行+下一行，95 對）實測約 2 分 10 秒、prism 26.7 MB，已不採用。
  3. 正常輸入 ㄋㄧˇㄏㄠˇ 首選仍為「你好」。
  4. ㄓㄨㄥㄨㄣ 組字中按 ← ←、退格、按 ㄩ：預編輯變 ㄓㄨㄥㄩㄣ（游標處修正），候選更新；→ 回尾端繼續輸入正常。
  5. 非組字時方向鍵仍移動宿主游標；拼音布局行為不變。

## 不做

proximity 座標校正、非標準鍵位、候選排序調整、預編輯浮窗在游標模式下的同步。
