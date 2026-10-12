package net.erutobusiness.eoscanguard;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Explosion Overhaul の「Start chunk scanning?」プロンプトを出さずに、サーバ起動時へ自動で答える。
 *
 * <p>なぜ要るか: Explosion Overhaul は爆発演出のために「レッドストーンランプ・尖った鍾乳石・ガラス」の
 * 位置を索引化する。索引が無いと <b>自然生成のもの</b>（洞窟の鍾乳石・村の窓）は反応しなくなる
 * （自分で置いたブロックは {@code onBlockPlaced} が走査の可否を見ずに登録するので効く）。
 * ところが開始の可否は <b>OP がゲーム内でプロンプトに答える</b>しか道が無く、
 * 走査後も「既存データを読むか / 取り直すか」を毎回聞かれる。運営には毎回同じ答えしか無いので機械に持たせる。
 *
 * <p>やること: {@code ServerStartedEvent} で
 * <ul>
 *   <li>保存済みの索引があれば {@code loadExistingData()}（走査し直さない）</li>
 *   <li>無ければ {@code forceServerScan(server)}（プロンプトを介さず直接走らせる）</li>
 * </ul>
 *
 * <p>⚠ <b>{@code startManualScan()} を呼んではいけない</b>（2026-08-03 に踏んだ）。
 * あの入口は先頭で {@code waitingForUserInput && pendingScanLevel != null} を見ており、
 * この2つは <b>OP が参加してプロンプトが出た瞬間</b>に初めて真になる。
 * {@code ServerStartedEvent} の時点では誰も居ないので何もせず return し、
 * <b>ログには「答えた」と出るのに1チャンクも走査されない</b>という形になる。
 *
 * <p>⚠ <b>すべてリフレクションで呼ぶ。</b> Explosion Overhaul を抜いてもこの MOD が
 * 起動を止めないようにするため（当部は MOD の抜き差しが多い）。対象が見つからなければ黙って何もしない。
 */
@Mod(EOScanGuard.MODID)
public class EOScanGuard {
    public static final String MODID = "eo_scanguard";
    private static final Logger LOG = LoggerFactory.getLogger("eo_scanguard");
    private static final String TARGET = "com.vinlanx.explosionoverhaul.BlockIndexManager";

    public EOScanGuard() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            Class<?> mgr = Class.forName(TARGET);
            boolean saved = (Boolean) mgr.getMethod("hasSaveFile", MinecraftServer.class)
                    .invoke(null, server);
            if (saved && terrainGrewSinceLastScan(server)) {
                // ⚠ **索引より新しい地形があるなら、読むだけでは足りない**（2026-08-05 に実測）。
                //    `onChunkLoad` は Chunky が事前生成したチャンクを拾わない:
                //    16分ぶん（約7,600チャンク）走らせても、停止時の保存で索引は
                //    43,237 バイト・地上54件のまま1件も増えなかった。
                //    索引に載らない＝**自然生成の鍾乳石とガラスが爆発に反応しない**。
                //    （自分で置いたランプ等は onBlockPlaced が拾うので影響しない）
                //
                //    前回走査したときより地形（.mca の合計バイト数）が5%以上増えていれば走査し直す。
                //    走査は当部の設定で毎秒1,000チャンクなので、6万チャンクでも1分ほど。
                //    事前生成が終われば地形が増えなくなるので、この判定は自然に止む。
                mgr.getMethod("forceServerScan", MinecraftServer.class).invoke(null, server);
                writeMarker(server, terrainBytes(server));
                LOG.info("[eo_scanguard] 前回より地形が増えているので走査し直す（forceServerScan）");
                return;
            }
            if (saved) {
                // 保存済みの索引があるなら読むだけ。走査し直さない。
                //
                // ⚠⚠ **`loadExistingData()` も素で呼ぶと何もしない**（2026-08-05 に踏んだ。
                //    2026-08-03 に startManualScan() で踏んだのと同じ問題が、読み込み側にも在った）。
                //    先頭が `if (!waitingForLoadDecision) return;` で、この旗が立つのは
                //    **OP が参加して確認画面が出た瞬間**だけ。起動時は誰も居ないので素通りし、
                //    それでもこちらは「読み込ませた」と書いていた＝**嘘のログ**。
                //    実害は大きく、`serverScanDecisionMade` が false のままだと
                //    **`onChunkLoad` の索引追加まで止まる**（事前生成した地形が1件も索引に入らない）。
                //
                //    → OP が「読み込む」を選んだのと同じ状態を先に作ってから呼ぶ。
                setField(mgr, "pendingScanLevel", server.overworld());
                setField(mgr, "waitingForLoadDecision", true);
                mgr.getMethod("loadExistingData").invoke(null);

                // ⚠ **ログの文言で「やった」と言わない。** 旗を読み直して確かめる
                boolean decided = (Boolean) readField(mgr, "serverScanDecisionMade");
                if (decided) {
                    LOG.info("[eo_scanguard] 保存済みの索引を読み込ませた（走査はしない）。"
                            + "以後 onChunkLoad の索引追加も動く");
                } else {
                    LOG.error("[eo_scanguard] 索引を読ませたのに決定の旗が立っていない。"
                            + "OP へ確認画面が出る／新しい地形が索引されない状態のまま。"
                            + "上流の版が変わった可能性があるので mod-notes を見ること");
                }
                return;
            }
            // ⚠ ここで startManualScan() を呼んではいけない（2026-08-03 に踏んだ）。
            //   あの入口は先頭で `waitingForUserInput && pendingScanLevel != null` を見ており、
            //   この2つは **OP が参加してプロンプトが出た瞬間** に初めて真になる。
            //   ServerStartedEvent の時点では誰も居ないので **何もせず return** する。
            //   ログに「答えた」と出るのに1チャンクも走査されない、という形で気づけなかった。
            //
            //   forceServerScan(server) は resetScanState() ＋ startServerScan(server) で、
            //   **プロンプトを介さず直接走らせる**（`starting server-wide scan for all dimensions`）。
            mgr.getMethod("forceServerScan", MinecraftServer.class).invoke(null, server);
            writeMarker(server, terrainBytes(server));
            LOG.info("[eo_scanguard] 保存済みの索引が無いので走査を直接開始した（forceServerScan）");
        } catch (ClassNotFoundException e) {
            LOG.info("[eo_scanguard] Explosion Overhaul が入っていないので何もしない");
        } catch (Throwable t) {
            // ⚠ ここで落ちてもサーバは止めない。上流の版が変わってメソッド名が変わったら
            //    プロンプトが復活するだけで済むようにする
            LOG.warn("[eo_scanguard] 自動応答に失敗した（上流の版が変わった？）: {}", t.toString());
        }
    }

    /** 走査したときの地形の量を覚えておくファイル。⚠ 索引そのものの更新時刻は使えない（下記）。 */
    private static final String MARKER = "eo_scanguard-last-scan.txt";

    /**
     * 前回の走査より地形が増えているか。
     *
     * <p>⚠⚠ **索引ファイルの更新時刻で判定してはいけない**（2026-08-05 に一度そう書いて外した）。
     * 索引は**サーバ停止のたびに丸ごと書き直される**ので、中身が古いままでも
     * 更新時刻はいつも新しい。ワールドの保存と同じ瞬間に書かれるため、
     * region ファイルと比べても差が出ない。
     *
     * <p>そこで**地形の量**（region ファイル `.mca` の合計バイト数）を、走査したときの値として
     * 別ファイルに書いておき、次の起動でいまの値と比べる。5% 増えていれば走査し直す。
     * 事前生成が終われば増えなくなるので、この判定は自然に止む。
     */
    private static boolean terrainGrewSinceLastScan(MinecraftServer server) {
        try {
            long now = terrainBytes(server);
            long then = readMarker(server);
            if (now <= 0) return false;
            if (then <= 0) {
                // 記録が無い＝この仕組みを入れる前の索引。**一度は走査し直す**
                LOG.info("[eo_scanguard] 前回の走査時の地形量が記録されていない（地形 {} MB）", now / 1_000_000L);
                return true;
            }
            boolean grew = now > then + then / 20L;   // 5% 増
            LOG.info("[eo_scanguard] 地形量 前回 {} MB → いま {} MB（{}）",
                    then / 1_000_000L, now / 1_000_000L, grew ? "走査し直す" : "変化なし");
            return grew;
        } catch (Throwable t) {
            LOG.warn("[eo_scanguard] 地形量を比べられなかった: {}", t.toString());
            return false;
        }
    }

    /** ワールド配下の `.mca` の合計バイト数。 */
    private static long terrainBytes(MinecraftServer server) throws Exception {
        java.nio.file.Path world =
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        final long[] total = {0L};
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(world, 4)) {
            s.filter(p -> p.getFileName().toString().endsWith(".mca")).forEach(p -> {
                try {
                    total[0] += java.nio.file.Files.size(p);
                } catch (Exception ignored) {
                }
            });
        }
        return total[0];
    }

    private static java.nio.file.Path markerPath(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("config").resolve("explosionoverhaul").resolve(MARKER);
    }

    private static long readMarker(MinecraftServer server) {
        try {
            java.nio.file.Path p = markerPath(server);
            if (!java.nio.file.Files.exists(p)) return 0L;
            return Long.parseLong(new String(java.nio.file.Files.readAllBytes(p), "UTF-8").trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void writeMarker(MinecraftServer server, long bytes) {
        try {
            java.nio.file.Path p = markerPath(server);
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.write(p, String.valueOf(bytes).getBytes("UTF-8"));
        } catch (Throwable t) {
            LOG.warn("[eo_scanguard] 走査時の地形量を書けなかった: {}", t.toString());
        }
    }

    /** private な静的フィールドへ書く（上流に公開の入口が無いため）。 */
    private static void setField(Class<?> owner, String name, Object value) throws Exception {
        java.lang.reflect.Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    /** private な静的フィールドを読む（「やったつもり」を防ぐための確認用）。 */
    private static Object readField(Class<?> owner, String name) throws Exception {
        java.lang.reflect.Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }
}
