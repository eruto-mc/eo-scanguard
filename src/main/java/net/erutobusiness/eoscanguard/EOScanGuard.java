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
            if (saved) {
                // 保存済みの索引があるなら読むだけ。走査し直さない
                mgr.getMethod("loadExistingData").invoke(null);
                LOG.info("[eo_scanguard] 保存済みの索引を読み込ませた（走査はしない）");
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
            LOG.info("[eo_scanguard] 保存済みの索引が無いので走査を直接開始した（forceServerScan）");
        } catch (ClassNotFoundException e) {
            LOG.info("[eo_scanguard] Explosion Overhaul が入っていないので何もしない");
        } catch (Throwable t) {
            // ⚠ ここで落ちてもサーバは止めない。上流の版が変わってメソッド名が変わったら
            //    プロンプトが復活するだけで済むようにする
            LOG.warn("[eo_scanguard] 自動応答に失敗した（上流の版が変わった？）: {}", t.toString());
        }
    }
}
