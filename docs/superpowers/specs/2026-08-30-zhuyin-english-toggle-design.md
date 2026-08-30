# 注音鍵盤中英切換設計

日期：2026-08-30（延伸 `2026-08-30-zhuyin-input-design.md`）

## 目標

在注音布局 `zh_bopomofo` 內提供「英」鍵，切到同一布局內的英文 QWERTY 頁直接輸入英文（不經 Rime、無聯想），
再以「中」鍵切回注音。仿 Gboard 注音鍵盤的中/英切換。

## 機制

- 布局的 `subKeyboards.Alphabet1` 是黏性的第二套字母鍵盤（日文 flick 的 ABC 頁即此機制）：
  按鍵 `code: -20`（`CODE_TO_ALPHA_1_LAYOUT`）切入，`code: -19`（`CODE_TO_ALPHA_0_LAYOUT`）切回。
  `altPages`（`$alt0`）是一次性頁面，不適用。
- 注音頁的按鍵只會產生 Unicode 注音符號（U+3105… 與聲調符號），因此在 `imeHint == "bopomofo"` 下
  抵達 `ChineseIME` 的任何 **ASCII 可見字元（0x21–0x7E）**都只可能來自英文頁或 `?123` 符號頁，
  一律不送 Rime、直接上屏（組字中先 `commitComposition()`）。這把既有的「ASCII 數字直通」推廣為全部 ASCII 可見字元，
  順帶修正 `?123` 頁的 `,` `.` `;` `/` `-` 會被 Rime 當成注音鍵（ㄝ ㄡ ㄤ ㄥ ㄦ）組字的問題。
- 空白、Enter、退格維持既有路徑（空白在組字中仍是一聲／選字）。

## 布局變更（`java/assets/layouts/Chinese/zh_bopomofo.yaml`，子模組分支 `zhuyin-layout`）

底列改為 7 鍵：`$symbols`、`英`（`type: base, spec: "英|_", code: -20, attributes: {style: Functional}`）、`，`、`$action`、`$space`、`。`（moreKeys 同前）、`$enter`。

新增：

```yaml
subKeyboards:
  Alphabet1:
    rows:
      - letters: q w e r t y u i o p
      - letters: a s d f g h j k l
      - letters: [$shift, z, x, c, v, b, n, m, $delete]
      - bottom:
        - $symbols
        - type: base
          spec: "中|_"
          code: -19
          attributes: { style: Functional }
        - $action
        - $space
        - type: base
          spec: '.'
          moreKeys: [',', '?', '!', "'"]
          attributes: { moreKeyMode: OnlyExplicit }
        - $enter
```

- 整體 `autoShift: false` 沿用，英文頁不自動大寫；`$shift` 手動大寫可用。
- `LayoutSpec.md` 的 bopomofo 段落補充一句：布局可用 `subKeyboards.Alphabet1` 提供英文頁，ASCII 字元由 IME 直接上屏。

## `ChineseIME` 變更

`handleBopomofoKey` 的數字分支條件由 `codePoint in '0'.code..'9'.code` 改為 `codePoint in 0x21..0x7E`，
其餘邏輯不變（`rawInput.clear()`；組字中 `commitComposition()`；`commitText(字元)`）。KDoc 同步改為
「ASCII 可見字元（來自英文頁或符號頁）不經 Rime」。非 bopomofo hint 行為不變。

## 測試

- `ZhuyinLayoutTest` 新增：
  - `subKeyboards[KeyboardLayoutKind.Alphabet1]` 存在，字母列鍵數 `[10, 9, 9]`（`$shift`/`$delete` 各算 1 鍵：`[10, 9, 9]`？— 以實際 `Row.keys.size` 為準：第三列含 shift、delete 共 9 鍵）。
  - 主鍵盤底列含 `BaseKey.code == -20` 的鍵；英文頁底列含 `code == -19` 的鍵。
- 真機：「英」→ 英文頁；輸入 `Hello,` 直接上屏；組字 ㄋㄧˇ 中按「英」再打 `a` → 先上屏「你」再 `a`；
  shift 大寫；「中」切回後注音正常；`?123` 頁按 `,` 得到 `,`；拼音布局行為不變。

## 不做

英文聯想／自動修正、英文頁自動大寫、Chinese QWERTY 布局的中英鍵、新增設定項。
