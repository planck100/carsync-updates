# 車機下載中心 0.2.0 測試版

原生 Android App，以同一個 APK 支援手機直式與車機橫式。最低 Android 6，target SDK 35。先在 Galaxy A55 Android 16 驗證，再確認車機畫面。此版本是獨立測試 App，套件為 `app.car.downloadcenter.test`。

## 使用

1. 從 GitHub Actions 的 `CarDownloadCenter-0.2.0-test` 成品下載並解壓縮 APK，首次可用 Send Anywhere 傳至車機。
2. 開啟後會讀取主分支 `download-center/catalog.json`。按「檢查更新」重新讀取。
3. 選擇 App，確認下載。下載後驗證 SHA-256、APK 套件名稱、版本、已安裝套件簽章與是否降版。
4. 必要時依系統提示允許下載中心「安裝未知應用程式」，返回再按「確認安裝」。Android 安裝畫面由使用者確認；返回後重新讀取已安裝版本。
5. 測試版下載期間請保持畫面開啟；旋轉或關閉畫面可能中止未完成下載，可重新下載。已驗證 APK 保留，安裝取消後可重試。

尚未做手機或車機實機測試。本機已完成 Java 編譯、D8、資源封裝、APK 簽章驗證及清單測試；Gradle/lint 因建置外掛無法取得而尚未通過。不能取代實機測試。

## 維護 App 清單

清單網址固定為：
`https://raw.githubusercontent.com/planck100/carsync-updates/main/download-center/catalog.json`

新增 App 只需要修改 `apps` 陣列，支援兩種來源：

- `source.type = json`：讀取該 App 已有更新 JSON，`fields` 對應遠端欄位名稱。
- `source.type = apk`：`source.url` 為 HTTPS APK 網址；項目提供 `packageName`、`versionName`、`sha256`，建議附 `versionCode`、`sizeBytes` 和 `releaseNotes`。
- `iconUrl` 可選，HTTPS 圖示上限 512 KiB；缺少時顯示 App 名稱。
- `status = pending` 保留項目並停用下載；確認套件及發布網址後再改為 `active`。
- 清單本身不包含任何登入權杖。所有下載使用 HTTPS；未知版本的清單格式會拒絕讀取。
- 無網路時可顯示上次成功讀取的清單與版本資訊，並明確標示離線狀態。

CarSync 沿用 `standalone-update.json`；主分支舊的 `carsync-update.json` 是另一個套件，不能混為更新。遠端缺少 versionCode 時僅標示「可下載」版本，不靠版本字串猜測新舊；下載後用 APK 真正的 versionCode 阻止降版。

神盾與啟動器已依 v3 APK 填入實際套件、版本、大小及 SHA-256；指定 shield-bridge-v3 Release 為預定發布位置。目前尚未發布，須取得使用者核准、上傳兩份 APK 並更新主分支清單後才能完成線上下載驗收。

## 測試簽章與限制

此 CI 產物使用 Android debug 簽章，僅供第一輪驗證。GitHub 不同 runner 產生的 debug key 可能不同，不能保證下一次測試 APK 可覆蓋。正式版需以私密保存、持續使用的 keystore 簽署；私鑰不得提交到公開倉庫。正式版使用不含 `.test` 的套件。下載中心不持有神盾、啟動器或 CarSync 的簽署私鑰。

本版不做背景下載、靜默安裝、多 App 自動排隊或遠端管理表單。每個 App 下載及安裝都由使用者發起；不需要 root，不會要求刪除簽章不符的原 App。首次安裝無法和本機既有簽章對照時，由清單 SHA-256 驗證內容與 Android 系統驗證 APK 簽章。

## 建置

Java 17、Gradle 8.11.1、Android SDK 35：
`cd download-center && gradle :app:assembleDebug :app:lintDebug`

工作流程只產生測試成品，不自動發布 release 或改動既有 CarSync APK。

下載中心作為安裝管理工具，需要辨識遠端清單中未預先列舉的 App，因此宣告 QUERY_ALL_PACKAGES，僅對此 lint 項目附具體理由，不停用其餘檢查。已安裝套件資料只在本機比對，不上傳。
