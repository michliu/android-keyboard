# 注音輸出台灣字形設計

日期：2026-10-23

## 背景

注音方案用 luna_pinyin 詞庫，它與聯想詞用的 `essay.txt` 都採 OpenCC 的傳統字形（爲、喫、衆、裏），台灣慣用的是為、吃、眾、裡。離線評估（Common Voice zh-TW 19,144 句、86,626 個情境，小麥注音台灣讀音）中，「為」有 535 次根本不在 Rime 前 8 個候選裡（Rime 給的是「爲」）。

在 Rime 加上 OpenCC `t2tw` 轉換後：Rime 本身第一位正確率 76.8% → 78.0%；加上依前文排序與讀音篩選後 87.9% → 89.0%（單音節 77.9% → 80.0%）。

## 設計

1. **Rime 方案**：`futo_bopomofo.schema.yaml`（`futo_bopomofo_terra` 經由 include 共用）的 filters 在 `simplifier` 之後加 `simplifier@zh_tw`，設定 `opencc_config: t2tw.json`、`option_name: futo_zh_tw`。詞庫 zip 已附 `opencc/t2tw.json`。
2. **開關**：App 寫入的 `<schema>.custom.yaml` 只對注音方案（`futo_bopomofo*`）加上 `futo_zh_tw` 開關，`reset` 在簡體輸出時為 0、否則為 1（t2tw 會把簡體正字「着」轉成「著」）。拼音、筆畫方案不變。
3. **對照表**：新增 `TaiwanVariants`（Kotlin），內含 OpenCC `TWVariants.txt` 的 40 組一對一字形（Apache-2.0），提供 `toTaiwan(text)`。致謝頁加 OpenCC。
4. **聯想詞**：`ChineseAssociation.parse` 建索引前先把 `essay.txt` 的詞轉成台灣字形，上屏「為」可查到原本「爲」開頭的詞。
5. **讀音表**：`ChineseCharReadings` 讀到傳統字形的字時，也為它的台灣字形記同樣的讀音。
6. `BundledRimeAssets.VERSION` 加 1，讓既有安裝更新方案檔。

## 測試

- 單元測試：`TaiwanVariants.toTaiwan`；聯想索引以「爲了」建立後 `lookup("為")` 含「了」；讀音表「爲	wei」後 `has("為", "wei")`。
- 電腦上以 librime 執行修改後的方案：ㄨㄟˋ → 為、ㄔˉ → 吃、ㄓㄨㄥˋ 含「眾」、ㄌㄧˇ → 裡。
- 真機：打 ㄨㄟˋ 第一位是「為」、上屏後有聯想詞；開啟簡體輸出時不套用 t2tw。
