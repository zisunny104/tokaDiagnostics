# tokaDiagnostics

0.1.2 啟動時就建立 reports 並顯示絕對路徑，新增 `diag status`，修正沒有追蹤卻提示已匯出的誤導訊息。必須先執行 `diag start` 才會收集資料，不會回溯開始追蹤前的事件。

0.1.1 修正登入初期 UUID 尚未建立時，收送封包事件觸發空值例外的問題；登入前事件不列入以玩家 UUID 啟動的追蹤。已以未初始化 UUID 的 Player 測試收送事件與結束追蹤，並通過隔離核心載入及匯出測試。更新時移除舊版 JAR，避免兩版同時載入。

Nukkit 的限時連線診斷插件，Java 21。用來收集客戶端顯示 `Block` 等斷線問題的伺服器端線索；不保證單靠插件就能找出客戶端拒絕封包的原因。

## 使用

把 `output/tokaDiagnostics-0.1.3.jar` 放入伺服器 plugins，正常重新啟動，避免使用 reload。管理權限為 `tokaDiagnostics.admin`，預設 OP；權限插件可以另行授權。

```text
diag status
diag start zisunny104 120
diag mark zisunny104 before-teleport
diag mark zisunny104 glass-area
diag export zisunny104
```

0.1.3 預設自動從第一個收送事件追蹤連線，不需先輸入指令，也不需等待 UUID。每條連線自動追蹤一次、最多 120 秒，同時最多兩位（額滿的新連線可能無法擷取）；不重複自動延長同一條連線。最近 128 條已完成連線保留防重啟記號。登入中與登入後以物件身分區分，避免 UUID 尚未建立時互相混用。手動 start 可以再次開始。

遊戲中指令前加 `/`。手動指令的對象必須在線；離線、到期或插件關閉會自動匯出，不需要斷線後再輸入指令。後台會顯示 `plugins/tokaDiagnostics/reports/incident-*.zip` 的路徑。每份報告包含 UTC 時間、插件與核心版本、每秒位置、收送封包類型，以及 UpdateBlock 的 runtime ID／座標與 LevelChunk 的區塊／長度摘要。

同時最多兩位、10–300 秒、每位保留最近 2048 筆；溢出會記錄丟棄筆數。背景匯出佇列最多四份、磁碟最多 50 份，達上限請自行移出舊報告。大量封包可能讓早期事件被覆蓋，請在重現前才開始。

不擷取封包原始內容、登入 JWT、聊天內容、IP 或資料庫密碼。標記文字由管理者輸入，請勿放機密；世界名稱與座標仍可能透露私人建築位置，公開前請檢查。

## 提交問題

附上 ZIP、客戶端錯誤截圖、操作步驟、客戶端版本及正在執行的核心 JAR SHA-256。截圖有連線識別資訊，公開前可以遮蔽。插件觀察到的事件不等於封包已完成編碼或送達；目前不記錄 RakNet 重送、壓縮後資料及客戶端解析錯誤，也不會自行修改世界或核心。若摘要仍不足，再針對可重現的封包在核心加入診斷，避免長期全量抓包。

2026-10-16 的兩次截圖時間為台灣 17:04:51 與 17:59:54；`ClientDisconnection-90 / Block` 本身尚不能證明特定方塊或核心缺陷。

## 編譯與進度

使用 Maven、Java 21，以及本機已安裝的 Nukkit Maven artifact：`mvn package`。產物位於 target，交付副本放在專案 output 及 plugin-modernization/output（不進版控）。目前為首版診斷工具，需實際重現斷線才能分析根因；尚未宣稱完成正式服驗證或核心修復。

整體拆分只維護在 [zero 主文件](https://github.com/zisunny104/zero/blob/master/docs/ARCHITECTURE.md)。
