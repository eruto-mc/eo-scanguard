package net.erutobusiness.eoscanguard;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

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
 *   <li>保存済みの索引があれば {@code loadExistingData()}</li>
 *   <li>無ければ {@code startManualScan()}</li>
 * </ul>
 * を呼ぶ。プロンプトは「まだ決まっていない」ときだけ出るので、先に決めてしまえば出ない。
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
            Method hasSaveFile = mgr.getMethod("hasSaveFile", MinecraftServer.class);
            boolean saved = (Boolean) hasSaveFile.invoke(null, server);
            String call = saved ? "loadExistingData" : "startManualScan";
            mgr.getMethod(call).invoke(null);
            LOG.info("[eo_scanguard] チャンク走査の可否を自動で答えた: {}（保存済みの索引: {}）",
                    call, saved ? "あり" : "なし");
        } catch (ClassNotFoundException e) {
            LOG.info("[eo_scanguard] Explosion Overhaul が入っていないので何もしない");
        } catch (Throwable t) {
            // ⚠ ここで落ちてもサーバは止めない。上流の版が変わってメソッド名が変わったら
            //    プロンプトが復活するだけで済むようにする
            LOG.warn("[eo_scanguard] 自動応答に失敗した（上流の版が変わった？）: {}", t.toString());
        }
    }
}
