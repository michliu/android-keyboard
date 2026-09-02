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
  /** 同行相鄰與上下鄰近的鍵對（無序、去重、只含 RIME_ALPHABET 內的鍵，不含空白） */
  fun adjacentKeyPairs(): List<Pair<Char, Char>>
  ```
  垂直鄰居的判定：上下兩行中心對齊（行 r 的第 i 鍵，對到行 r+1 的第 i 與 i-1 鍵，符合實體鍵盤半鍵位錯位）。
  純函式，可單測（例：'u' 的鄰居含 'y','i','7','8','j','k'？——以實作的錯位規則為準，測試鎖定若干代表對）。
- `ChineseIMESettings` 新增 `ZhuyinAdjacentToleranceSetting = SettingsKey(booleanPreferencesKey("ChineseIME_zhuyin_adjacent_tolerance"), true)`，
  中文設定頁加開關（`userSettingToggleDataStore`，字串 `chinese_setting_zhuyin_tolerance`）。
- `ChineseIME.writeCustomizationFile`：`Configuration` 增加 `zhuyinTolerance: Boolean` 欄位；當 schema 以 `futo_bopomofo` 開頭且開啟時，寫入：
  ```yaml
      speller/algebra/+:
          - fuzz/x/y/
          - fuzz/y/x/
          ...（每個相鄰鍵對兩條）
  ```
  fuzz 產生的拼寫品質低於精確拼寫，候選排名靠後，不影響正常輸入。
- 部署代價：規則會鏈式作用使 prism 變大。真機驗收標準：詞庫部署時間 ≤ 15 秒且正常拼寫的首選不變；超標則退化為「只做同行相鄰」。

### 2. 組字中游標修正（IME navigator）

- `ChineseIME.onEvent` 的 `EVENT_TYPE_DOWN_UP_KEYEVENT` 分支：在 `isBopomofo && rawInput.text.isNotEmpty()`（以 rawInput 判組字，與按鍵協程 FIFO 一致）時：
  - `KEYCODE_DPAD_LEFT` → `coroScope.launch { rime.processX11Code(XK_LEFT) }`，`return`；
  - `KEYCODE_DPAD_RIGHT` → 同理 `XK_RIGHT`；
  - 其他鍵維持既有行為（`interruptInput()` + `sendDownUpKeyEvent`）。
- X11 keysym：優先用 aar `icu.astronot233.rime.X11Keys` 的 `XK_Left`/`XK_Right`；若無此常數，在 `ChineseIME` companion 定義
  `private const val XK_LEFT = 0xFF51`、`XK_RIGHT = 0xFF53`。
- librime 的 `navigator` processor（方案 engine 已含）會在預編輯內移動游標；退格與新輸入自然發生在游標處。
- `rawInput` 的限制：`rawInput` 假設游標在尾端。游標離開尾端後 `rawInput` 與實際輸入序列可能不一致——
  處理：按下 LEFT 時將 `rawInput.clear()` 並記 `cursorAdjusted = true`；此後預編輯浮窗顯示 Rime 預編輯（`normalized` 為空時
  浮窗編輯入口自然不可用），直到組字結束（preedit 清空時 `cursorAdjusted` 復位）。這是可接受的取捨：游標模式下以鍵盤直接修正為主。
- 非 bopomofo hint 的行為不變。

## 測試

- 單元：`adjacentKeyPairs` 對稱、無重複、不含空白、代表對抽查（'1'-'2'、'u'-'i'、'u'-'j'、'/'-'.'、'-'-'0'）；
  fuzz 規則文本生成（給定小鄰居表 → 期望 yaml 行）。
- 真機：
  1. 想按 ㄧ(`u`) 誤按 ㄩ(`m`)：ㄋㄩˇ 仍出「你」（排名靠後但可見）；關閉開關後不再出現。
  2. 部署時間：切開關後首次喚起 ≤ 15 秒完成（logcat Deploy 間隔）。
  3. 正常輸入 ㄋㄧˇㄏㄠˇ 首選仍為「你好」。
  4. ㄓㄨㄥㄨㄣ 組字中按 ← ←、退格、按 ㄩ：預編輯變 ㄓㄨㄥㄩㄣ（游標處修正），候選更新；→ 回尾端繼續輸入正常。
  5. 非組字時方向鍵仍移動宿主游標；拼音布局行為不變。

## 不做

proximity 座標校正、非標準鍵位、候選排序調整、預編輯浮窗在游標模式下的同步。
