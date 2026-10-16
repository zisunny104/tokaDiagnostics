# tokaDiagnostics

Nukkit 連線診斷插件，Java 21。協助分析客戶端 `Block` 斷線等問題；不會修改世界或核心，也不會把最後一筆封包當作已確認的元凶。

## 安裝與使用

將 `output/tokaDiagnostics-0.2.0.jar` 放入 plugins，移出所有舊版 tokaDiagnostics JAR，正常停止後重新啟動，避免 reload。啟動即建立 `plugins/tokaDiagnostics/reports/`，並在後台顯示絕對路徑。

**預設自動追蹤，無須搶著輸入指令。** 從第一個收送封包事件開始，不等待 UUID；同時最多兩條連線，每條自動追蹤一次、最多 120 秒。斷線、到期或插件關閉時背景匯出 ZIP。超過兩位時部分連線不會被追蹤；超過 120 秒的同一條連線不會自動延長，必要時可手動再次開始。

```text
diag status
diag start zisunny104 120
diag mark zisunny104 before-teleport
diag export zisunny104
```

遊戲內加 `/`；管理權限為 `tokaDiagnostics.admin`，預設 OP，也可透過權限插件授權。手動 start 的秒數為 10–300，對象必須在線。標記請勿輸入密碼或機密。

## 0.2.0 報告格式

ZIP 包含 `trace.txt` 與解讀方式 `README.txt`。時間使用 UTC，另有單調時鐘計算的 elapsedMs，方便對照事件先後。

| 段落 | 保留內容與上限 |
| --- | --- |
| lifecycle | 登入關鍵封包、JOIN、傳送事件（原因／是否取消／前後世界及座標）、每秒位置／tick／heap、標記與結束；最近 512 筆 |
| first-packets | 最初 64 筆封包摘要，之後不覆蓋 |
| incoming | 收到的封包，最近 512 筆 |
| chunks | LevelChunk 摘要，最近 512 筆 |
| blocks | UpdateBlock 摘要，最近 2048 筆 |
| block-entities | BlockEntityData 摘要，最近 512 筆 |
| other-outgoing | 其他送出封包，最近 256 筆 |
| packet-totals | 整段追蹤的封包數、取消數、已知 payload 長度總和及最大值；類型過多時合併 OTHER_TYPES |
| per-second-counts | 每秒／每類封包數，最多 301 個時間桶，每桶超過 32 類合併統計 |

每個段落分別顯示 dropped。**某一類明細被覆蓋，不會洗掉其他段落，封包總量仍保留。** 明細仍是有上限的，不能宣稱保存全部封包。分段內容不是全域排序，需用時間／elapsedMs 對照。

- Login／RequestNetworkSettings 記錄協定號碼，不讀取登入 token。
- UpdateBlock 記錄座標、runtime ID、layer、flags。
- LevelChunk 記錄座標、維度、section 數、cache／requestSubChunks／blob 數、payload 長度。
- BlockEntityData 記錄座標及 NBT 長度，不記錄 NBT 內容。
- 區塊與方塊實體另記 `crc32First4096`：只對前 4096 bytes 計算的指紋，方便比對重複資料；不是完整雜湊、有效性驗證或網路封包校驗。
- 環境記錄核心顯示版本、Java、插件版本；匯出執行緒計算核心來源 JAR 的 `coreJarOnDiskSha256`。這是匯出時磁碟上的檔案，不代表可以偵測執行期間遭替換的 JAR，請避免運行中覆蓋核心。

## 限制、效能與隱私

記憶體明細與統計均有上限；封包執行緒不寫檔、不解析 NBT，不呼叫封包 encode，也不對整份 payload 計算雜湊。ZIP 和核心 JAR SHA-256 由單一背景執行緒處理，等待佇列最多四份。reports 最多 50 份 ZIP，達上限會明確記錄匯出失敗，請先移出舊報告，不會自動刪除證據。

沒有保存原始封包、登入 JWT、聊天、IP 或資料庫密碼。世界名稱、座標和自訂標記仍可能透露私人資訊，公開前請檢查。

封包事件只代表插件觀察到該事件，不證明編碼完成、實際送達或客戶端接受；不擷取 RakNet 重送、壓縮後資料或客戶端解析錯誤。取消狀態是此監聽器觀察當下的狀態。傳送事件也只是請求，請搭配之後的 CONTEXT 確認位置。需要時應進一步針對核心編碼流程與客戶端 Content Log 驗證。

## 版本與驗證

- 0.2.0：分流保留、全程及每秒統計、傳送時間線、協定與區塊／方塊實體指紋、磁碟核心 JAR SHA-256。
- 0.1.3：登入前即自動追蹤，以連線物件身分區分尚無 UUID 的玩家。
- 0.1.2：啟動建立 reports、status 指令及正確的無資料提示。
- 0.1.1：修正登入前 UUID 空值例外。

本機測試包含一萬筆方塊實體事件洪峰仍保留其他類型證據、明細上限／累計計數、字串處理、指紋取樣邊界，以及隔離核心中兩條未初始化 UUID 連線的自動追蹤與 ZIP 匯出。尚未完成新版正式服的斷線重現，沒有宣稱已修復 Block 根因。

使用 Maven 與 Java 21：`mvn package`，或執行 `build.ps1`。JAR 複製到專案 output 與 plugin-modernization/output，不進版控。

整體拆分只維護在 [zero 主文件](https://github.com/zisunny104/zero/blob/master/docs/ARCHITECTURE.md)。
