# SonyPods ColorOS

專為 **ColorOS 16 / 17**（基於 LibXposed API 102）打造的 Sony 耳機原生系統整合模組。直接將 Sony Tandem V1/V2 耳機接入 ColorOS 內建耳機管線，無縫補齊雙耳與充電盒電量、流體雲狀態膠囊、原生開蓋配對彈窗、音量條降噪切換與耳機詳情頁進階控制。

- **包名**：`io.github.nicochanyo.sonypods`
- **建議作用域**：
  - `com.android.bluetooth`（藍牙）
  - `com.heytap.mydevices`（裝置服務 / 設備空間）
  - `com.oplus.wirelesssettings`（無線設定）
  - `com.oplus.melody`（OPPO 歡律）
  - `com.android.settings`（系統設定）
  - `com.android.systemui`（系統介面）
  - `com.heytap.accessory`（配件服務）
  - `com.sony.songpal.mdr`（Sony Sound Connect，選填：用於開啟官方 App 時自動讓位）
- **適用系統**：ColorOS 16 / 17（Android 16 / 17）
- **Xposed API**：LibXposed API 102
- **開源協議**：GPL-3.0（詳見 [LICENSE](LICENSE)）

---

## 主要功能

### 1. ColorOS 原生管線與流體雲整合
- **三段即時電量**：透過 Sony Tandem V1/V2 協定即時同步左耳、右耳與充電盒電量及充電狀態，支援雙耳一前一後非同步出盒同步與流體雲即時熱更新。
- **流體雲狀態膠囊**：耳機連線時自動觸發 ColorOS 原生頂部流體雲膠囊，顯示耳機高解析去背圖示、型號與雙耳電量。
- **系統耳機詳情頁與裝置空間**：解除第三方耳機廠商白名單限制，在藍牙裝置詳情、控制中心藍牙明細卡與裝置空間直接呈現原生耳機控制面板。
- **Sony 官方去背圖示庫**：內建 Sony 官方耳機透明去背圖示（依耳機型號與配色代碼自動匹配），並支援自訂 PNG 圖示覆寫。

### 2. 原生降噪、音量條與進階音效控制
- **多入口降噪切換**：支援從控制中心、音量條耳機降噪面板、藍牙詳情頁與磁貼副標題即時切換 **降噪 ／ 環境音 ／ 關閉**。
- **耳機詳情頁原生擴充控制**：
  - **環境音設定**：1 ~ 20 級通透強度微調與「專注語音」開關。
  - **附加功能**：直接開關「說話時自動暫停（Speak-to-Chat）」與「DSEE 音質還原」。
  - **藍牙音訊編碼**：直接在詳情頁一鍵切換 `LDAC` 與 `AAC` 高音質編碼。

### 3. BLE 高速配對彈窗與智慧讓位
- **配對模式高速彈窗**：硬體級 BLE 掃描偵測進入配對模式的 Sony 耳機，即時喚起 ColorOS 原生底部配對卡片，一鍵完成經典藍牙配對與連線，並支援選擇「僅配對模式」或「配對與連線電量」觸發模式。
- **屏蔽重複原生配對框**：僅針對 Sony 耳機自動攔截 Google Fast Pair 與系統預設配對確認框，**完全不影響任何非 Sony 耳機或其他藍牙裝置正常配對**。
- **Sound Connect 自動讓位**：開啟 Sony 官方 `Sound Connect` App 時自動釋放 RFCOMM 通道，離開後自動恢復接管。

---

## 简体中文说明

专为 **ColorOS 16 / 17**（基于 LibXposed API 102）设计的 Sony 耳机原生系统整合模块。将 Sony Tandem V1/V2 耳机直接接入 ColorOS 内置耳机管线，补齐双耳与充电盒电量、流体云状态胶囊、原生配对弹窗、音量条降噪切换及耳机详情页高级控制。

- **包名**：`io.github.nicochanyo.sonypods`
- **推荐作用域**：`com.android.bluetooth`、`com.heytap.mydevices`、`com.oplus.wirelesssettings`、`com.oplus.melody`、`com.android.settings`、`com.android.systemui`、`com.heytap.accessory`、`com.sony.songpal.mdr`
- **适用系统**：ColorOS 16 / 17（Android 16 / 17）
- **使用提示**：在 LSPosed 中启用模块后，可直接在模块 App「系统整合」页点击「重启核心作用域」立即生效，无需重启手机。

---

## English

A native **ColorOS 16 / 17** Xposed module (LibXposed API 102) that bridges Sony Tandem V1/V2 headphones directly into ColorOS's built-in headset pipeline, enabling native dual-earbud & case battery levels, Fluid Cloud capsules, fast BLE pairing popups, volume-slider ANC controls, and detail-page audio settings.

- **Package Name**: `io.github.nicochanyo.sonypods`
- **Scopes**: `com.android.bluetooth`, `com.heytap.mydevices`, `com.oplus.wirelesssettings`, `com.oplus.melody`, `com.android.settings`, `com.android.systemui`, `com.heytap.accessory`, `com.sony.songpal.mdr`
- **Requirements**: ColorOS 16 / 17, LSPosed with API 102 support

---

## 鳴謝 / Credits
- 作者與維護：`o永夜o` (`@nicochanyo`)
- Sony 耳機通訊與指令參考：[Mercury000 / SonyPods](https://github.com/Mercury000/SonyPods)
- ColorOS 系統耳機整合架構參考：[ByteJuno / HuaweiPods-ColorOS](https://github.com/ByteJuno/HuaweiPods-ColorOS)

> 本模組於 GitHub 與 LSPosed 倉庫完全免費開源提供，請勿在任何第三方平台或群組付費購買，謹防受騙。

---

## 開源協議 / License

本專案採用 **GNU General Public License v3.0 (GPL-3.0)** 開源協議，完整條款請參閱 [LICENSE](LICENSE)。

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see the [LICENSE](LICENSE) file for details.
