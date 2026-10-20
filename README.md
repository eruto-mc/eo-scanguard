# eo_scanguard — 爆発MODのチャンク走査に、サーバ起動時へ自動で答える

**サーバ専用**（`side=SERVER`）。コードは1クラスだけ。

## 何を解決するか

Explosion Overhaul は爆発の演出のために「レッドストーンランプ・尖った鍾乳石・ガラス」の位置を
索引化する。その開始の可否を **OP がゲーム内でプロンプトに答える**しか道が無く、
しかも走査後は「既存データを読むか／取り直すか」を**毎回**聞かれる。

- 出るのは **OP だけ**（`showing scan prompt to OP`）。一般の部員には出ない
- `/explosionoverhaul rescanchunks` は `showRescanPrompt()` を呼ぶだけ＝**プロンプトを出し直す**
- config に**プロンプトだけ止めるスイッチは無い**（`[Scan]` の4項目を全部読んで確認）

運営の答えは毎回同じなので、機械に持たせる。

## やること

`ServerStartedEvent` で:

- 保存済みの索引があれば `BlockIndexManager.loadExistingData()`
- 無ければ `BlockIndexManager.startManualScan()`

プロンプトは「まだ決まっていない」ときだけ出るので、**先に決めてしまえば出ない**。

⚠ **すべてリフレクションで呼ぶ。** Explosion Overhaul を抜いてもこの MOD が起動を止めないため
（当部は MOD の抜き差しが多い）。元の MOD のバージョンが変わってメソッド名が変わった場合も、
**プロンプトが復活するだけ**で済む。

## 走査の設定は別の場所

`maxScanThreads` と `cpuUsagePercent` は Explosion Overhaul 側の設定であって、
本MODは触らない。
⚠ `maxScanThreads = 0` は「控えめ」ではなく**全コア**。

## 実機で確認済み（2026-08-03）

```
[eo_scanguard] チャンク走査の可否を自動で答えた: startManualScan（保存済みの索引: なし）
```

Docker 起動テスト OK（mods 303 / broken 0）。
