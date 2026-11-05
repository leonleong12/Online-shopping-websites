# NovaMart

NovaMart 是以 Spring Boot、Vue 3 和 SQL Server 建置的線上商店。前端採用 Vue 單頁應用，後端提供登入、商品、訂單、錢包、卡密、客服及營運統計 API。

## 技術需求

- Java 25
- Maven 3.9 或 IntelliJ IDEA 內建 Maven
- Node.js 20 或更新版本
- SQL Server

## 專案結構

```text
src/main/java/com/onlineshop/       Spring Boot API 與服務
src/main/resources/                  Spring 設定、SQL 與目前前端建置檔
src/test/java/com/onlineshop/        後端測試
frontend-orange/src/                 Vue 頁面、元件與樣式
frontend-orange/public/              前端公用資源
deploy/                              部署說明、資料庫升級腳本
migration_*.sql                      各功能的 SQL Server 遷移腳本
scripts/                             可重複執行的本機 API / 錢包整合測試
uploads/                             本機商品及首頁圖片，不納入 Git
```

## 本機開發與驗證

先在 `frontend-orange` 建置前端，建置會將正式靜態檔同步至 Spring Boot 資源目錄：

```powershell
cd frontend-orange
npm ci
npm test
npm run build
cd ..
mvn test
mvn package
```

啟動前請透過環境變數設定資料庫連線；不要把資料庫密碼、SMTP 授權碼或 AI API Key 寫入程式碼或提交至 GitHub。資料庫升級請依 `deploy/部署說明.md` 操作，僅在目標資料庫執行該版本要求的 SQL。一次性清庫 SQL 不納入 Git，日常部署時不要執行。

## 部署

產生的 JAR 位於 `target/online-shop-0.0.1-SNAPSHOT.jar`。伺服器需另外備妥 SQL Server、資料庫遷移以及 `uploads/` 中需要保留的圖片；本機圖片和資料庫內容不會隨 GitHub 原始碼或 JAR 自動搬移。詳細步驟見 `deploy/部署說明.md`。

## GitHub 注意事項

`.gitignore` 排除建置輸出、IDE 設定、執行記錄、本機設定、上傳圖片和封裝輸出。上傳前請確認沒有提交任何 `.env`、`application-local.yml`、資料庫備份、真實使用者資料或憑證。`uploads/` 請獨立備份與部署，不要因為被忽略就當作已保存。
