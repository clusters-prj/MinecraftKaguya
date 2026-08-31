package com.clustersprj.fjeskinbridge;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionSkinApplyEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.skin.Skin;
import org.geysermc.geyser.api.skin.SkinGeometry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * マーケットプレイス(Web/fjew)で購入・使用中のスキンを、Bedrock観測者(Switch/PS等を含む)向けに
 * Geyser APIで強制上書きするGeyser拡張(Extension)。
 * <p>
 * Geyserは既定で「Javaプレイヤーが持つスキン」をBedrock観測者向けに自動変換するが、
 * 「Bedrockプレイヤー自身の見た目」は端末側で選択された実際のBedrockスキンをそのまま中継する。
 * 購入したスキンを所有者がJava/Bedrockどちらであってもbedrock観測者に強制表示するには、
 * {@link SessionSkinApplyEvent} で一律に上書きする必要がある。
 * </p>
 * <p>
 * Minecraftサーバー側(FJEconomyのPaperプラグイン)と同じく、マーケットプレイスへHTTP APIを
 * 一切呼び出さず、共有MariaDB(fjeconomyデータベース)を直接読み込むだけで完結させる。
 * </p>
 */
public class FJESkinBridgeExtension implements Extension {

    private SkinDbClient skinDbClient;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        try {
            ExtensionConfig config = new ExtensionConfig(dataFolder());
            this.skinDbClient = new SkinDbClient(config);
            logger().info("FJESkinBridge を初期化しました");
        } catch (IOException e) {
            logger().severe("設定ファイルの読み込みに失敗しました: " + e.getMessage());
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (skinDbClient != null) skinDbClient.shutdown();
        }));
    }

    @Subscribe
    public void onSkinApply(SessionSkinApplyEvent event) {
        if (skinDbClient == null) return;

        Optional<SkinDbClient.SkinPayload> payload = skinDbClient.getActiveSkin(event.uuid());
        if (payload.isEmpty()) return;

        try {
            byte[] argbData = decodePngToArgbBytes(payload.get().pngData());
            event.skin(new Skin("fjeskinbridge:" + event.uuid(), argbData));
            event.geometry("slim".equals(payload.get().model()) ? SkinGeometry.SLIM : SkinGeometry.WIDE);
        } catch (IOException e) {
            logger().warning("スキンPNGのデコードに失敗しました (uuid=" + event.uuid() + "): " + e.getMessage());
        }
    }

    /**
     * PNGバイト列を、Geyserが要求する生ARGBバイト列(1ピクセルあたりR,G,B,Aの4バイト、行優先・
     * 左上から右下へ)に変換する。Geyser本体の {@code SkinProvider#bufferedImageToImageData}
     * と同じレイアウトに合わせている。
     */
    private byte[] decodePngToArgbBytes(byte[] pngData) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngData));
        if (image == null) throw new IOException("PNGとしてデコードできませんでした");

        ByteArrayOutputStream out = new ByteArrayOutputStream(image.getWidth() * image.getHeight() * 4);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgba = image.getRGB(x, y);
                out.write((rgba >> 16) & 0xFF); // R
                out.write((rgba >> 8) & 0xFF);  // G
                out.write(rgba & 0xFF);         // B
                out.write((rgba >> 24) & 0xFF); // A
            }
        }
        return out.toByteArray();
    }
}
