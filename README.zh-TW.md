<div align="center">

<img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Rolithax Launcher" width="96">

# Rolithax Launcher

**一個 App，同時管理遠端 MSLX Daemon 與手機本機上的 Minecraft Java 伺服器。**

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Android CI](https://github.com/WLudy1012/RolithaxLauncher/actions/workflows/android.yml/badge.svg)](https://github.com/WLudy1012/RolithaxLauncher/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/WLudy1012/RolithaxLauncher?include_prereleases&label=release)](https://github.com/WLudy1012/RolithaxLauncher/releases)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84)

[简体中文](README.md) · **繁體中文** · [English](README.en.md)

</div>

Rolithax Launcher 是 [MSLX Daemon](https://github.com/MSLTeam/MSLX) 的**第三方** Android 用戶端，以 Kotlin 與 Jetpack Compose 打造。
它把「連線遠端 Daemon 管理實例」與「直接在手機上開伺服器」放進同一份實例清單與主控台，並針對觸控螢幕重新設計了操作流程。

> 本應用程式與 MSLTeam 沒有任何隸屬或關聯關係，使用前請先閱讀[免責聲明](#免責聲明)。
> 套件名稱 `com.wludy.rolithax.launcher`。更換套件名稱與簽章後，舊版需全新安裝，舊資料可透過應用程式內的加密匯出／匯入遷移。

本應用程式整合 MSLAPI 下載服務：**本服务由MSL开服器提供**（本服務由 MSL 開服器提供）。<a href="https://mslmc.cn"><img src="./app/src/main/res/drawable-nodpi/msl_logo.png" alt="MSL 開服器 Logo" width="32"></a> [mslmc.cn](https://mslmc.cn)

## 下載

可從 [GitHub Releases](https://github.com/WLudy1012/RolithaxLauncher/releases) 或 [CNB 鏡像](https://cnb.cool/WLudy/RolithaxLauncher/-/releases)（中國大陸網路較快）下載，每個版本提供兩種安裝包：

| 安裝包 | 說明 |
| --- | --- |
| `app-release.apk` 完整版 | 內嵌 Java 17 執行環境，本機開服可離線使用 |
| `app-release-lite.apk` 精簡版 | 體積較小，首次本機開服需連網下載約 36 MB 的執行環境 |

兩個安裝包簽章相同，可互相覆蓋安裝。應用程式內也能直接檢查並安裝更新，見[更新與版本](#更新與版本)。

## 功能

**連線與管理**
- 同時儲存多台 Daemon，所有入口都以**實例**定址，本機實例與各 Daemon 的實例合併在同一份清單中。
- 掃描 Daemon 頁面產生的 QR Code 即可配對；配對依賴本專案的 [Daemon 外掛](https://github.com/WLudy1012/Rolithax-MSLX-plugin)，也可以手動填寫位址與 API Key。
- 啟動、停止、重新啟動、強制結束、備份；尚未同意 EULA 時會自動引導。實例可置頂，刪除需二次確認。

**主控台**
- 透過 SignalR 即時顯示日誌，並以增量方式解析 ANSI 顏色與樣式；可傳送指令、自動捲動、一鍵回到最新。
- 遠端與本機實例共用同一套主控台介面。

**建立與資源中心**
- 建立精靈涵蓋基本資訊、伺服器核心、Java 環境、資源配置與確認，支援線上核心庫、遠端下載、本機上傳與整合包。
- 資源中心：瀏覽 Modrinth 的模組、外掛、整合包與資料包，依目標實例的核心與遊戲版本篩選；統一檢查相依與衝突後一次安裝。
- 目標為遠端 Daemon 時，由 Daemon 所在的機器直接下載並安裝，手機不做中轉。

**本機開服**
- 不依賴 Daemon、Termux 或 proot，直接在手機上執行 Minecraft Java 伺服器，詳見[本機開服](#本機開服)。

**設定與安全**
- 檔案管理、外掛與模組管理、`server.properties` 編輯、加密備份與遷移。
- 毛玻璃介面、動態取色與自訂背景；深色主題；橫向與平板使用側邊導覽列。
- 執行日誌可檢視、複製、匯出並自動遮蔽敏感資訊；當機後可一鍵提交 Issue。

## 快速開始

**連線遠端 Daemon**

1. 在電腦上啟動 MSLX Daemon，準備好它的位址與 API Key。
2. 開啟 App，在「連線守護程式」頁填入位址（例如 `https://192.168.1.100:1027`）與 API Key 後連線。
   若已安裝 Rolithax 外掛，也可以直接掃描外掛頁面上的 QR Code。
3. 進入實例清單，點任一實例即可檢視日誌、傳送指令、啟動或停止。

**直接在手機上開伺服器**

1. 點底部 Dock 中央的「+」，目標選「本機」。
2. 選擇伺服器核心與版本並完成精靈；首次使用時會視需要安裝 Java 執行環境。
3. 啟動實例，在主控台中照常操作。

## 本機開服

實例資料夾有兩種放法，資料夾名稱在兩個位置之間全域唯一：

- **公用目錄**（預設）：`/storage/emulated/0/MSLX/servers/<實例名>`，可用檔案管理員直接放入核心、模組與備份。需要 Android 11+ 的「所有檔案存取」權限；未授權時不會中斷開服，而是自動改建到私有目錄並提示。
- **應用程式私有目錄**：`files/mslx/servers/<實例名>`，無須授權，但只能透過 App 內的檔案管理讀寫。

兩種引擎：

| 引擎 | 原理 | 限制 |
| --- | --- | --- |
| 基準（行程內 JVM） | NDK `dlopen libjvm.so` 並建立 JVM，無須任何授權 | 一個行程只能建立一個 JVM，停止後需完全結束 App 才能再次啟動 |
| 增強（Shizuku） | 以 shell 權限啟動真正的 `java` 子行程 | 需要 Shizuku 授權；支援多實例並行、原地重啟與跨 Java 版本，未授權時自動回退為基準引擎 |

可在設定中安裝多個 Java 執行環境並依實例選用，啟動前會進行版本檢查（執行環境低於核心要求時直接攔下並給出建議）：

| 遊戲版本 | 建議執行環境 |
| --- | --- |
| 1.16 及以下 | Java 8 |
| 1.17 – 1.20.4 | Java 17（完整版內嵌） |
| 1.20.5+ | Java 21 |
| 26.x+ | Java 25 |

所有執行環境皆校驗 SHA-256，下載來源依「CNB 鏡像 → GitHub Release → 上游」依序回退，缺少可信摘要時一律失敗關閉。執行期間由前景服務與常駐通知保持運作，可直接從通知停止。

## 安全

- 預設強制 HTTPS：未帶通訊協定的位址會自動補上 `https://`，明文 `http://` 預設升級為 HTTPS；只有在連線頁明確允許並確認風險後才保留明文，且僅限可信任的內部網路。
- 自簽憑證僅對你自行設定的 Daemon 生效；MSLAPI、GitHub 等公開服務仍使用系統信任鏈。
- API Key 以 Android Keystore AES-GCM 加密儲存，不隨系統備份；應用程式內匯出／匯入使用獨立的密碼加密封存檔，不會匯出 Keystore 金鑰。
- 下載一律串流處理並校驗摘要，解壓縮會限制路徑、單檔大小、總大小與項目數；執行中的實例禁止刪除或遷移。
- 日誌只記錄遮蔽後的資訊，不含位址、路徑、Token 與 API Key。

## 更新與版本

- **穩定版**：tag 無後綴（如 `v1.8.0`）。**測試版**：tag 帶 `-Beta`。**強制更新**：tag 帶 `-Force`，用戶端無法略過。
- 設定頁的「更新頻道」預設為穩定版；測試頻道會同時接收穩定版與 Beta。更新會在應用程式內下載並校驗後交給系統安裝程式。
- 更新優先從 CNB 鏡像下載，失敗時回退至 GitHub Releases。
- 版本依數字逐段比較，因此正式版的版本號必須高於同號的 Beta，否則 Beta 使用者不會收到它。

## 建置

需求：JDK 17+、Android SDK Platform 35、NDK 28.2.13676358。

```bash
./gradlew :app:compileDebugKotlin     # 編譯檢查
./gradlew :app:testDebugUnitTest      # 單元測試
./gradlew assembleDebug               # Debug APK
./gradlew assembleRelease             # 簽章 Release，需要 keystore.properties
./gradlew assembleRelease -PwithoutJre=true   # 精簡版，不內嵌 JRE
```

首次同步需要連網。中國大陸網路可設定 `MSLX_MAVEN_GOOGLE_MIRROR` 與 `MSLX_MAVEN_CENTRAL_MIRROR` 以使用鏡像倉庫。
內嵌 JRE 以 `fetch-jre-assets.ps1` 取得，`LD_PRELOAD` 墊片以 `build-shim.ps1` 重新編譯。

## 專案結構

```text
app/src/main/java/com/wludy/rolithax/launcher/
├── RolithaxApplication.kt   Application 與相依容器
├── MainActivity.kt          單一 Activity、免責閘門、更新與當機入口
├── localengine/             NativeVm 與 JNI 宣告
├── data/
│   ├── remote/              Retrofit、SignalR、網路用戶端
│   ├── localengine/         本機實例、JRE、引擎、保活
│   ├── resources/           資源 Provider、Room、Paging、安裝
│   └── ...                  倉庫、設定、加密、備份、日誌
└── ui/                      home / instances / console / create / servers / settings / ...
app/src/main/cpp/            行程內 JVM 的 Native 橋接
```

架構、介面契約、安全限制與發佈流程見 [`develop/agent/`](develop/agent/README.md)（簡體中文）。

## 相關倉庫

- [Rolithax-MSLX-plugin](https://github.com/WLudy1012/Rolithax-MSLX-plugin)：Daemon 外掛，提供掃碼配對、實例圖示與資源遠端直裝。
- [RolithaxLauncher-website](https://github.com/WLudy1012/RolithaxLauncher-website)：專案官網與下載頁。

## 回報問題

請至 [Issues](https://github.com/WLudy1012/RolithaxLauncher/issues) 提交，並附上版本號與已遮蔽的日誌（設定 → 執行日誌）。當機後應用程式會提示一鍵提交。

## 開源授權

以 [GNU AGPL-3.0](LICENSE) 開源，與上游 [MSLX](https://github.com/MSLTeam/MSLX) 一致。Copyright (C) 2026 WLudy1012。
第三方元件與內嵌執行環境的授權見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，也可在應用程式內「設定 → 關於 → 合規與授權」離線檢視。

## 免責聲明

本應用程式是**獨立的第三方應用程式**，與 MSLTeam 不存在任何責任、關聯、代理或隸屬關係。管理伺服器實例可能涉及資料修改、檔案讀寫與行程啟停，公用目錄授權與 Shizuku（shell 層級）授權皆屬高權限，由此產生的一切後果由使用者自行承擔。完整聲明見 [DISCLAIMER.txt](DISCLAIMER.txt)（簡體中文），首次啟動時需閱讀並同意。
